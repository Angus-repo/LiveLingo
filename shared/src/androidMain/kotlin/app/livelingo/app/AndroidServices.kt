package app.livelingo.app

import android.Manifest
import android.content.Intent
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import java.io.File
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Call once during Activity.onCreate, before the Activity reaches STARTED. */
fun createAndroidServices(activity: ComponentActivity): PlatformServices {
    val mic = AndroidMicrophone(activity)
    return object : PlatformServices {
        override val storage: LocalStorage = AndroidStorage(activity)
        override val microphone: Microphone = mic
        override val exporter: TextExporter = AndroidTextExporter(activity)
        override fun currentTimeMillis(): Long = System.currentTimeMillis()
        override fun uniqueId(): String = UUID.randomUUID().toString()
        override fun createHttpClient(): HttpClient = HttpClient(OkHttp) {
            // Never forward the device-held Foundry credential to a redirect destination.
            followRedirects = false
            install(WebSockets) { maxFrameSize = 1_048_576 }
            engine {
                config {
                    followRedirects(false)
                    followSslRedirects(false)
                    pingInterval(20, TimeUnit.SECONDS)
                    connectTimeout(15, TimeUnit.SECONDS)
                }
            }
        }
    }
}

private class AndroidMicrophone(private val activity: ComponentActivity) : Microphone, DefaultLifecycleObserver {
    private var permissionContinuation: CancellableContinuation<Boolean>? = null
    private val requestPermission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionContinuation?.let { if (it.isActive) it.resume(granted) }
        permissionContinuation = null
    }
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

    init { activity.lifecycle.addObserver(this) }

    private suspend fun permitted(): Boolean {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true
        check(permissionContinuation == null) { "正在等待麥克風權限。" }
        return suspendCancellableCoroutine { continuation ->
            permissionContinuation = continuation
            continuation.invokeOnCancellation { permissionContinuation = null }
            requestPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override suspend fun start(onAudio: (ByteArray) -> Unit, onError: (String) -> Unit): Unit = withContext(Dispatchers.Main.immediate) {
        check(recording == null) { "麥克風已在使用中。" }
        check(permitted()) { "未取得麥克風權限，請在系統設定中允許。" }
        check(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { "請回到 App 後再啟動翻譯。" }
        val minBuffer = AudioRecord.getMinBufferSize(24_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minBuffer > 0) { "此裝置無法以 24 kHz 單聲道錄音。" }
        // 24,000 samples/sec × 2 bytes × 100ms. Only raw PCM reaches the server.
        val chunkBytes = 4_800
        val audio = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(24_000)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
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
        session.job = activity.lifecycleScope.launch(Dispatchers.IO) {
            session.worker = Thread.currentThread()
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            try {
                val buffer = ByteArray(chunkBytes)
                while (session.active.get()) {
                    val read = audio.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    // stop() can unblock a final positive short read. Deliver it before drain completes.
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
                if (session.active.getAndSet(false)) activity.lifecycleScope.launch(Dispatchers.Main.immediate) {
                    if (!session.explicitlyStopped.get() && generation == session.generation) {
                        onError(e.message ?: "麥克風錄音失敗。")
                    }
                }
            } finally {
                runCatching { audio.stop() }
            }
        }
        // Completion also runs if the coroutine is cancelled before it can enter its body.
        session.job?.invokeOnCompletion {
            runCatching { audio.stop() }
            audio.release()
            synchronized(stateLock) { if (recording === session) recording = null }
            session.done.countDown()
        }
        Unit
    }

    override fun stop() {
        val session = recording ?: return
        session.explicitlyStopped.set(true)
        session.active.set(false)
        runCatching { session.audio.stop() }
        // READ_BLOCKING normally returns immediately after stop; cap foreground waiting at 300ms.
        // Closing delivery under the same lock guarantees no callback after this method returns.
        if (session.worker !== Thread.currentThread()) {
            runCatching { session.done.await(300, TimeUnit.MILLISECONDS) }
        }
        synchronized(session.callbackLock) { session.deliveryAllowed.set(false) }
        session.job?.cancel()
        synchronized(stateLock) { if (recording === session) recording = null }
    }

    override fun onStop(owner: LifecycleOwner) {
        val session = recording ?: return
        if (session.active.get()) {
            stop()
            session.onError("App 已離開前景，錄音已停止；已收到的逐字稿會保留。")
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        stop()
        permissionContinuation?.cancel()
        permissionContinuation = null
        activity.lifecycle.removeObserver(this)
    }
}

private class AndroidStorage(activity: ComponentActivity) : LocalStorage {
    private val folder = File(activity.filesDir, "livelingo").apply { mkdirs() }
    // Stable alias intentionally retained across 0.1.0 → 0.1.1 updates.
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

private class AndroidTextExporter(private val activity: ComponentActivity) : TextExporter {
    override fun export(fileName: String, mimeType: String, text: String) {
        val folder = File(activity.cacheDir, "exports").apply { mkdirs() }
        // Cache exports expire automatically; remove old copies when creating a new one.
        folder.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.delete() }
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "transcript.txt" }
        val target = File(folder, safeName)
        target.writeText(text, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.exports", target)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri("逐字稿", uri)
        }
        activity.startActivity(Intent.createChooser(intent, "匯出逐字稿"))
    }
}
