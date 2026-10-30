package app.livelingo.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.ktor.client.HttpClient
import java.io.File
import java.lang.ref.WeakReference
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * A process-owned session, independent of any Activity/composition. Its foreground service keeps
 * recording and Foundry streaming alive while the user switches apps or locks the screen.
 * Android may still terminate the whole process; no microphone capture is auto-restarted.
 */
class AndroidSessionHost internal constructor(context: Context) {
    private val appContext = context.applicationContext
    private val mic = AndroidMicrophone(appContext)
    private val textExporter = AndroidTextExporter(appContext)
    val services: PlatformServices = object : PlatformServices {
        override val storage: LocalStorage = AndroidStorage(appContext)
        override val microphone: Microphone = mic
        override val exporter: TextExporter = textExporter
        override fun currentTimeMillis(): Long = System.currentTimeMillis()
        override fun uniqueId(): String = UUID.randomUUID().toString()
        override fun createHttpClient(): HttpClient = createAndroidFoundryHttpClient()
    }
    val controller: AppController = AppController(services)

    internal fun attach(activity: ComponentActivity) {
        mic.attach(activity)
        textExporter.attach(activity)
    }
}

object AndroidSessionRegistry {
    @Volatile private var session: AndroidSessionHost? = null

    @Synchronized fun attach(activity: ComponentActivity): AndroidSessionHost {
        val existing = session ?: AndroidSessionHost(activity.applicationContext).also { session = it }
        existing.attach(activity)
        return existing
    }

    /** Invoked from the foreground service's Stop action or unexpected destruction. */
    fun stopFromNotification() {
        session?.controller?.stop()
    }
}

/** Backwards-compatible factory; MainActivity should use AndroidSessionRegistry for the controller. */
fun createAndroidServices(activity: ComponentActivity): PlatformServices =
    AndroidSessionRegistry.attach(activity).services

private class AndroidMicrophone(private val appContext: Context) : Microphone {
    private var permissionContinuation: CancellableContinuation<Boolean>? = null
    private var permissionLauncher: ActivityResultLauncher<Array<String>>? = null
    private var permissionActivity: WeakReference<ComponentActivity>? = null

    private val captureScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private class Recording(
        val generation: Long,
        val audio: AudioRecord,
        val onAudio: (ByteArray) -> Unit,
        val onError: (String) -> Unit
    ) {
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

    /** Register before the Activity reaches STARTED. Never retain the Activity beyond onDestroy. */
    fun attach(activity: ComponentActivity) {
        if (permissionActivity?.get() === activity) return
        permissionActivity = WeakReference(activity)
        permissionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            val granted = result[Manifest.permission.RECORD_AUDIO]
                ?: (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED)
            permissionContinuation?.let { if (it.isActive) it.resume(granted) }
            permissionContinuation = null
        }
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                if (permissionActivity?.get() === activity) {
                    permissionActivity = null
                    permissionLauncher = null
                    permissionContinuation?.cancel()
                    permissionContinuation = null
                }
                activity.lifecycle.removeObserver(this)
            }
        })
    }

    private suspend fun permitted(): Boolean {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED) return true
        val activity = permissionActivity?.get() ?: error("請先開啟 App 並授予麥克風權限。")
        check(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            "請先回到 App 才能授予麥克風權限。"
        }
        check(permissionContinuation == null) { "正在等待麥克風權限。" }
        return suspendCancellableCoroutine { continuation ->
            permissionContinuation = continuation
            continuation.invokeOnCancellation {
                if (permissionContinuation === continuation) permissionContinuation = null
            }
            val requested = if (Build.VERSION.SDK_INT >= 33)
                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            else arrayOf(Manifest.permission.RECORD_AUDIO)
            val launcher = permissionLauncher
            if (launcher != null) launcher.launch(requested)
            else {
                permissionContinuation = null
                continuation.resume(false)
            }
        }
    }

    /**
     * Prepare from the user's visible foreground action, before contacting Foundry.
     * Android 14+ forbids creating a microphone foreground service only after backgrounding.
     */
    override suspend fun prepare(): Unit = withContext(Dispatchers.Main.immediate) {
        check(permitted()) { "未取得麥克風權限，請到系統設定中允許。" }
        val activity = permissionActivity?.get()
        check(activity?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true) {
            "請在 App 畫面中開始翻譯，之後即可切到背景。"
        }
        check(recording == null) { "麥克風已在使用中。" }
        val ready = AndroidTranslationForegroundService.requestStart(appContext)
        try {
            withTimeout(5_000) { ready.await() }
        } catch (e: Exception) {
            AndroidTranslationForegroundService.requestStop(appContext)
            throw e
        }
        Unit
    }

    override suspend fun start(onAudio: (ByteArray) -> Unit, onError: (String) -> Unit): Unit =
        withContext(Dispatchers.Main.immediate) {
            check(recording == null) { "麥克風已在使用中。" }
            check(ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED) { "未取得麥克風權限。" }
            // The service was created while UI was visible; it remains active on Home/lock.
            check(AndroidTranslationForegroundService.isActive) { "背景翻譯服務已停止，請回到 App 重新開始。" }
            val minBuffer = AudioRecord.getMinBufferSize(
                24_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            check(minBuffer > 0) { "此裝置無法以 24 kHz 單聲道錄音。" }
            val chunkBytes = 4_800
            val audio = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(24_000)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minBuffer * 2, chunkBytes * 4))
                .build()
            if (audio.state != AudioRecord.STATE_INITIALIZED) {
                audio.release()
                error("無法初始化麥克風。")
            }
            try {
                audio.startRecording()
                check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "麥克風無法開始錄音。" }
            } catch (e: Exception) {
                audio.release()
                throw e
            }
            val session = synchronized(stateLock) {
                Recording(++generation, audio, onAudio, onError).also { recording = it }
            }
            session.job = captureScope.launch {
                session.worker = Thread.currentThread()
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                try {
                    val buffer = ByteArray(chunkBytes)
                    while (session.active.get()) {
                        val read = audio.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                        if (read > 0) {
                            check(read % 2 == 0) { "麥克風回傳不完整音訊樣本。" }
                            synchronized(session.callbackLock) {
                                if (session.deliveryAllowed.get() && generation == session.generation) {
                                    onAudio(buffer.copyOf(read))
                                }
                            }
                        } else if (session.active.get()) {
                            error("錄音中斷，請確認麥克風未被其他 App 使用。")
                        }
                    }
                } catch (e: Exception) {
                    if (session.active.getAndSet(false)) callbackScope.launch {
                        if (!session.explicitlyStopped.get() && generation == session.generation) {
                            onError(e.message ?: "麥克風錄音失敗。")
                        }
                    }
                } finally {
                    runCatching { audio.stop() }
                }
            }
            session.job?.invokeOnCompletion {
                runCatching { audio.stop() }
                audio.release()
                synchronized(stateLock) { if (recording === session) recording = null }
                session.done.countDown()
            }
            Unit
        }

    override fun stop() {
        val session = recording
        if (session != null) {
            session.explicitlyStopped.set(true)
            session.active.set(false)
            runCatching { session.audio.stop() }
            if (session.worker !== Thread.currentThread()) {
                runCatching { session.done.await(300, TimeUnit.MILLISECONDS) }
            }
            synchronized(session.callbackLock) { session.deliveryAllowed.set(false) }
            session.job?.cancel()
            synchronized(stateLock) { if (recording === session) recording = null }
        }
    }

    override fun releaseSession() {
        AndroidTranslationForegroundService.requestStop(appContext)
    }
}

private class AndroidStorage(context: Context) : LocalStorage {
    private val folder = File(context.filesDir, "livelingo").apply { mkdirs() }
    // Stable alias intentionally retained across app updates.
    private val alias = "livelingo.access-token.v1"

    private fun file(key: String): File {
        require(key.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) { "Invalid storage key" }
        return File(folder, key)
    }

    @Synchronized override fun read(key: String): String? = file(key).let { source ->
        if (!source.exists() && !File(source.path + ".bak").exists()) null
        else AtomicFile(source).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    @Synchronized override fun write(key: String, value: String) {
        val atomic = AtomicFile(file(key))
        val output = atomic.startWrite()
        try { output.write(value.toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (e: Exception) { atomic.failWrite(output); throw e }
    }

    @Synchronized override fun delete(key: String) { AtomicFile(file(key)).delete() }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    @Synchronized override fun readSecret(key: String): String? {
        val stored = read("secret-$key") ?: return null
        val parts = stored.split(':', limit = 2)
        check(parts.size == 2) { "安全儲存資料損毀，請重新設定 Foundry API key。" }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            String(doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        }
    }

    @Synchronized override fun writeSecret(key: String, value: String) {
        if (value.isEmpty()) { delete("secret-$key"); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        write("secret-$key", Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP))
    }
}

private class AndroidTextExporter(private val appContext: Context) : TextExporter {
    @Volatile private var foregroundActivity: WeakReference<ComponentActivity>? = null

    fun attach(activity: ComponentActivity) {
        foregroundActivity = WeakReference(activity)
    }

    override fun export(fileName: String, mimeType: String, text: String) {
        val activity = foregroundActivity?.get()?.takeIf {
            it.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        } ?: error("請回到 App 再匯出逐字稿。")
        val folder = File(appContext.cacheDir, "exports").apply { mkdirs() }
        folder.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }
            ?.forEach { it.delete() }
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "transcript.txt" }
        val target = File(folder, safeName)
        target.writeText(text, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.exports", target)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri("逐字稿", uri)
        }
        activity.startActivity(Intent.createChooser(intent, "匯出逐字稿"))
    }
}
