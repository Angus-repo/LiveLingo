package app.livelingo.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/** Kept independent of Android UI APIs so the actual native WebSocket engine is regression tested. */
internal fun createAndroidFoundryHttpClient(
    configureEngine: OkHttpClient.Builder.() -> Unit = {},
): HttpClient = HttpClient(OkHttp) {
    followRedirects = false
    // Ktor 3.3.1's OkHttp engine throws if WebSockets.maxFrameSize is assigned.
    // The shared receiver enforces MAX_FOUNDRY_FRAME_BYTES after receiving each frame.
    install(WebSockets)
    engine {
        config {
            configureEngine()
            followRedirects(false)
            followSslRedirects(false)
            pingInterval(20, TimeUnit.SECONDS)
            connectTimeout(15, TimeUnit.SECONDS)
            addInterceptor(FoundryHandshakeInterceptor())
        }
    }
}

private class AndroidFoundryHttpException(
    override val statusCode: Int,
    override val providerCode: String?,
    override val requestId: String?,
) : IOException("Foundry WebSocket HTTP $statusCode"), FoundryHttpFailure

/** OkHttp's Ktor adapter otherwise discards non-401 handshake responses. */
private class FoundryHandshakeInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (!request.header("Upgrade").equals("websocket", ignoreCase = true) || response.code == 101) {
            return response
        }
        val key = request.header("api-key") ?: request.header("Authorization")?.removePrefix("Bearer ")
        // Never retain response bodies, raw headers, request URLs or credentials in exceptions.
        val fields = runCatching { safeJsonErrorFields(response.peekBody(16_384).string(), key) }
            .getOrDefault(SafeFoundryErrorFields())
        val requestId = sequenceOf("apim-request-id", "x-ms-request-id", "x-request-id", "request-id")
            .mapNotNull { safeRequestId(response.header(it), key) }.firstOrNull() ?: fields.requestId
        val status = response.code
        response.close()
        throw AndroidFoundryHttpException(status, fields.providerCode, requestId)
    }
}
