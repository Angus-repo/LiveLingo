package app.livelingo.app

import android.content.Context
import android.content.Intent
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.ktor.client.HttpClient
import java.io.File
import java.lang.ref.WeakReference
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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
