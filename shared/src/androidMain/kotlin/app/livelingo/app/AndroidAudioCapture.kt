package app.livelingo.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import app.livelingo.core.AudioSource
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.*

/** Android permits playback capture only for these media usages, never voice calls. */
internal fun playbackCaptureUsages(source: AudioSource): List<Int> {
    require(source == AudioSource.DEVICE_PLAYBACK) { "此音源不支援播放擷取。" }
    return listOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN)
}

internal class AndroidMicrophone(private val appContext: Context) : Microphone {
    override fun reportsAudioLevel(): Boolean = true
    private var permissionContinuation: CancellableContinuation<Boolean>? = null
    private var projectionContinuation: CancellableContinuation<Intent?>? = null
    private var permissionLauncher: ActivityResultLauncher<Array<String>>? = null
    private var projectionLauncher: ActivityResultLauncher<Intent>? = null
    private var permissionPending = false
    private var projectionPending = false
    private var activityRef: WeakReference<ComponentActivity>? = null
    private val captureScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var preparedSource: AudioSource? = null
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var statusCallback: ((AudioInputStatus) -> Unit)? = null
    private var interruptionCallback: ((String) -> Unit)? = null

    private class Recording(val generation: Long, val audio: AudioRecord) {
        val active = AtomicBoolean(true)
        val explicitlyStopped = AtomicBoolean(false)
        val deliveryAllowed = AtomicBoolean(true)
        val callbackLock = Any()
        val done = CountDownLatch(1)
        @Volatile var worker: Thread? = null
        var job: Job? = null
    }
    private val stateLock = Any()
    @Volatile private var recording: Recording? = null
    @Volatile private var generation = 0L

    override fun availableSources(): List<AudioSource> =
        if (Build.VERSION.SDK_INT >= 29) listOf(AudioSource.MICROPHONE, AudioSource.DEVICE_PLAYBACK) else listOf(AudioSource.MICROPHONE)

    override fun unavailableReason(source: AudioSource): String? = when {
        source.isPlayback && Build.VERSION.SDK_INT < 29 -> "裝置播放音訊需要 Android 10 以上，請改用麥克風。"
        !source.selectable -> "此音源已移除，請重新選擇麥克風或裝置播放音訊。"
        else -> null
    }

    /** Both launchers are registered in stable order before STARTED, also after Activity recreation. */
    fun attach(activity: ComponentActivity) {
        if (activityRef?.get() === activity) return
        activityRef = WeakReference(activity)
        permissionLauncher = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            permissionPending = false
            val granted = result[Manifest.permission.RECORD_AUDIO] ?: hasRecordPermission()
            val waiter = permissionContinuation
            permissionContinuation = null
            if (waiter?.isActive == true) waiter.resume(granted)
        }
        projectionLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            projectionPending = false
            val waiter = projectionContinuation
            projectionContinuation = null
            if (waiter?.isActive == true)
                waiter.resume(result.data.takeIf { result.resultCode == Activity.RESULT_OK })
            // No live waiter means cancellation: discard this one-time consent, never reuse it.
        }
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                if (activityRef?.get() === activity) {
                    activityRef = null
                    permissionLauncher = null
                    projectionLauncher = null
                    permissionContinuation?.cancel()
                    projectionContinuation?.cancel()
                    permissionContinuation = null
                    projectionContinuation = null
                }
                activity.lifecycle.removeObserver(this)
            }
        })
    }

    private fun hasRecordPermission() = ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requireVisible() {
        if (activityRef?.get()?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) != true)
            throw AudioCaptureFailure("請在 App 畫面中開始翻譯，之後即可切到背景。")
    }

    private suspend fun permitted(): Boolean {
        if (hasRecordPermission()) return true
        requireVisible()
        if (permissionPending) throw AudioCaptureFailure("請先完成或關閉上一個錄音授權視窗，再開始翻譯。")
        val launcher = permissionLauncher ?: throw AudioCaptureFailure("請重新開啟 App 後授予錄音權限。")
        return suspendCancellableCoroutine { continuation ->
            permissionContinuation = continuation
            permissionPending = true
            continuation.invokeOnCancellation { if (permissionContinuation === continuation) permissionContinuation = null }
            try {
                launcher.launch(if (Build.VERSION.SDK_INT >= 33)
                    arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
                else arrayOf(Manifest.permission.RECORD_AUDIO))
            } catch (failure: Exception) {
                permissionPending = false
                permissionContinuation = null
                throw failure
            }
        }
    }

    private suspend fun requestProjectionConsent(manager: MediaProjectionManager): Intent? {
        requireVisible()
        if (projectionPending) throw AudioCaptureFailure("請先完成或關閉上一個音訊擷取授權視窗，再開始翻譯。")
        val launcher = projectionLauncher ?: throw AudioCaptureFailure("請重新開啟 App 後授予音訊擷取權限。")
        // Audio-only capture. No virtual display, screenshot, image surface or screen encoder is created.
        val intent = if (Build.VERSION.SDK_INT >= 34)
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        else manager.createScreenCaptureIntent()
        return suspendCancellableCoroutine { continuation ->
            projectionContinuation = continuation
            projectionPending = true
            continuation.invokeOnCancellation { if (projectionContinuation === continuation) projectionContinuation = null }
            try { launcher.launch(intent) }
            catch (failure: Exception) {
                projectionPending = false
                projectionContinuation = null
                throw failure
            }
        }
    }

    override suspend fun prepareSource(source: AudioSource, onStatus: (AudioInputStatus) -> Unit, onError: (String) -> Unit): Unit = withContext(Dispatchers.Main.immediate) {
        unavailableReason(source)?.let { throw AudioCaptureFailure(it) }
        check(recording == null && preparedSource == null)
        statusCallback = onStatus
        interruptionCallback = onError
        if (!permitted()) throw AudioCaptureFailure("未取得錄音權限。Android 擷取播放音訊也需要這項權限；請到系統設定中允許。")
        ensureActive()
        val manager = appContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val consent = if (source.isPlayback) requestProjectionConsent(manager)
            ?: throw AudioCaptureFailure("未授權音訊擷取，尚未連線或開始收音。可重新開始，或選擇麥克風。") else null
        ensureActive()
        requireVisible()
        // Android 14+ requires consent -> mediaProjection FGS -> getMediaProjection, in this order.
        val ready = AndroidTranslationForegroundService.requestStart(appContext, source)
        withTimeout(5_000) { ready.await() }
        ensureActive()
        if (consent != null) {
            val granted = manager.getMediaProjection(Activity.RESULT_OK, consent)
                ?: throw AudioCaptureFailure("音訊擷取授權已失效，請重新開始並授權。")
            projection = granted
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    if (projection !== granted) return
                    val interrupted = interruptionCallback
                    stop()
                    interrupted?.invoke("系統已停止播放音訊擷取，可能是鎖定螢幕、撤回授權或其他 App 開始分享。已收到的字幕會保留，請回到 App 重新開始。")
                }
            }
            projectionCallback = callback
            granted.registerCallback(callback, Handler(Looper.getMainLooper()))
        }
        preparedSource = source
    }

    override suspend fun start(onAudio: (ByteArray) -> Unit, onError: (String) -> Unit): Unit = withContext(Dispatchers.Main.immediate) {
        check(recording == null)
        val source = preparedSource ?: throw AudioCaptureFailure("音源尚未準備完成，請重新開始。")
        if (!hasRecordPermission()) throw AudioCaptureFailure("錄音權限已撤回，請重新授權。")
        if (!AndroidTranslationForegroundService.isActive) throw AudioCaptureFailure("背景翻譯服務已停止，請回到 App 重新開始。")
        val minBuffer = AudioRecord.getMinBufferSize(24_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) throw AudioCaptureFailure("此裝置無法以 24 kHz 單聲道擷取音訊。")
        val chunkBytes = 4_800
        val builder = AudioRecord.Builder()
            .setAudioFormat(AudioFormat.Builder().setSampleRate(24_000)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minBuffer * 2, chunkBytes * 4))
        if (source.isPlayback) {
            check(Build.VERSION.SDK_INT >= 29)
            val grant = projection ?: throw AudioCaptureFailure("音訊擷取授權已結束，請重新開始並授權。")
            val config = AudioPlaybackCaptureConfiguration.Builder(grant)
            playbackCaptureUsages(source).forEach { config.addMatchingUsage(it) }
            builder.setAudioPlaybackCaptureConfig(config.build())
        } else builder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        val audio = builder.build()
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            throw AudioCaptureFailure("無法初始化所選音源，請確認此裝置支援音訊擷取。")
        }
        try {
            audio.startRecording()
            if (audio.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw AudioCaptureFailure("所選音源無法開始收音。")
        } catch (failure: Exception) { audio.release(); throw failure }
        val session = synchronized(stateLock) { Recording(++generation, audio).also { recording = it } }
        val meter = AudioSignalMeter(source)
        val reportStatus = statusCallback
        session.job = captureScope.launch {
            session.worker = Thread.currentThread()
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            try {
                val buffer = ByteArray(chunkBytes)
                while (session.active.get()) {
                    val read = audio.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (read > 0) {
                        check(read % 2 == 0)
                        synchronized(session.callbackLock) {
                            if (session.deliveryAllowed.get() && generation == session.generation) onAudio(buffer.copyOf(read))
                        }
                        meter.accept(buffer, read)?.let { status -> callbackScope.launch {
                            if (session.active.get() && recording === session) reportStatus?.invoke(status)
                        } }
                    } else if (session.active.get()) error("capture interrupted")
                }
            } catch (_: Exception) {
                if (session.active.getAndSet(false)) callbackScope.launch {
                    if (!session.explicitlyStopped.get() && generation == session.generation)
                        onError("所選音源的收音已中斷，請確認音訊擷取授權或麥克風是否被其他 App 使用。")
                }
            } finally { runCatching { audio.stop() } }
        }
        session.job?.invokeOnCompletion {
            runCatching { audio.stop() }
            audio.release()
            synchronized(stateLock) { if (recording === session) recording = null }
            session.done.countDown()
        }
    }

    override fun stop() {
        permissionContinuation?.cancel()
        projectionContinuation?.cancel()
        val session = recording
        if (session != null) {
            session.explicitlyStopped.set(true)
            session.active.set(false)
            runCatching { session.audio.stop() }
            if (session.worker !== Thread.currentThread()) runCatching { session.done.await(300, TimeUnit.MILLISECONDS) }
            synchronized(session.callbackLock) { session.deliveryAllowed.set(false) }
            session.job?.cancel()
            synchronized(stateLock) { if (recording === session) recording = null }
        }
        val grant = projection
        projection = null // Invalidate callbacks before stopping the projection intentionally.
        projectionCallback?.let { callback -> runCatching { grant?.unregisterCallback(callback) } }
        projectionCallback = null
        runCatching { grant?.stop() }
        preparedSource = null
        statusCallback = null
        interruptionCallback = null
    }

    override fun releaseSession() {
        stop()
        AndroidTranslationForegroundService.requestStop(appContext)
    }
}
