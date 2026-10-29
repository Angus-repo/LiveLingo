@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.livelingo.app

import androidx.compose.ui.window.ComposeUIViewController
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSDate
import platform.Foundation.NSUUID
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIViewController
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Callback-based native bridge keeps Swift free of Kotlin suspend-function ABI details. */
interface IosNativeServices {
    fun startMicrophone(
        onPcm: (ByteArray) -> Unit,
        onFailure: (String) -> Unit,
        onStarted: (String?) -> Unit,
    )
    fun stopMicrophone()
    fun readValue(key: String, secret: Boolean): IosReadResult
    /** A null value deletes; a non-null return value describes a failure. */
    fun writeValue(key: String, value: String?, secret: Boolean): String?
    fun exportText(fileName: String, mimeType: String, text: String): String?
}

data class IosReadResult(val value: String?, val error: String?)

private class IosPlatformServices(private val native: IosNativeServices) : PlatformServices {
    override val storage: LocalStorage = object : LocalStorage {
        private fun readNative(key: String, secret: Boolean): String? {
            val result = native.readValue(key, secret)
            result.error?.let { error(it) }
            return result.value
        }
        private fun writeNative(key: String, value: String?, secret: Boolean) {
            native.writeValue(key, value, secret)?.let { error(it) }
        }
        override fun read(key: String) = readNative(key, false)
        override fun write(key: String, value: String) = writeNative(key, value, false)
        override fun delete(key: String) = writeNative(key, null, false)
        override fun readSecret(key: String) = readNative(key, true)
        override fun writeSecret(key: String, value: String) =
            writeNative(key, value.takeUnless(String::isEmpty), true)
    }

    override val microphone: Microphone = object : Microphone {
        override suspend fun start(onAudio: (ByteArray) -> Unit, onError: (String) -> Unit) {
            suspendCancellableCoroutine<Unit> { continuation ->
                continuation.invokeOnCancellation { native.stopMicrophone() }
                if (!continuation.isActive) return@suspendCancellableCoroutine
                native.startMicrophone(onAudio, onError) { failure ->
                    if (continuation.isActive) {
                        if (failure == null) continuation.resume(Unit)
                        else continuation.resumeWithException(IllegalStateException(failure))
                    }
                }
            }
        }
        override fun stop() = native.stopMicrophone()
    }

    override val exporter: TextExporter = object : TextExporter {
        override fun export(fileName: String, mimeType: String, text: String) {
            native.exportText(fileName, mimeType, text)?.let { error(it) }
        }
    }
    override fun currentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1_000).toLong()
    override fun uniqueId(): String = NSUUID().UUIDString
    override fun createHttpClient(): HttpClient = HttpClient(Darwin) {
        // Credentials must only be sent to the configured Foundry host.
        followRedirects = false
        install(WebSockets) {
            // KTOR-9639: late native PONG callbacks can abort older Darwin engines on cancellation.
            // Keep native pings disabled; do not depend on native PONG callbacks for liveness.
            maxFrameSize = 1_048_576
        }
    }
}

fun MainViewController(native: IosNativeServices): UIViewController {
    val services = IosPlatformServices(native)
    return ComposeUIViewController { LiveLingoApp(services) }
}
