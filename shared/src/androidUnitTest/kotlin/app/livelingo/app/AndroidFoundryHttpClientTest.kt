package app.livelingo.app

import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.io.Closeable
import java.util.concurrent.TimeUnit
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real TLS and OkHttp handshakes catch engine failures that an injected fake socket cannot. */
class AndroidFoundryHttpClientTest {
    @Test fun nativeFactoryRetainsPeerCloseCodeWithoutExposingCloseText() = runBlocking {
        Fixture().use { fixture ->
            fixture.server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    webSocket.send("""{"type":"error","error":{"code":"content_filter"}}""")
                    webSocket.close(1008, "private-test-key PRIVATE_TRANSCRIPT")
                }
            }))
            val factory = KtorFoundrySocketFactory(fixture.client)
            try {
                withTimeout(10_000) {
                    val socket = factory.open(fixture.url, "private-test-key")
                    try {
                        socket.send("start")
                        assertTrue(socket.receive().orEmpty().contains("content_filter"))
                        assertEquals(null, socket.receive())
                        assertEquals(1008, socket.peerCloseCode())
                    } finally { socket.cancel() }
                }
            } finally { factory.close() }
        }
    }

    private class Fixture : Closeable {
        private val certificate = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("api.openai.com").build()
        private val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        private val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer().apply { useHttps(serverCertificates.sslSocketFactory(), false); start() }
        val client = createAndroidFoundryHttpClient {
            sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            dns { host -> if (host == "api.openai.com") listOf(InetAddress.getByName("127.0.0.1")) else okhttp3.Dns.SYSTEM.lookup(host) }
        }
        val url: String get() = server.url("/openai/v1/live/sessions").toString().replaceFirst("https://", "wss://")
        override fun close() { client.close(); server.shutdown() }
    }

    @Test fun productionFactoryUsesOpenAiBearerWithoutAzureHeaderOrQuery() = runBlocking {
        Fixture().use { fixture ->
            fixture.server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) { webSocket.send("ack:$text") }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            }))
            val factory = KtorFoundrySocketFactory(fixture.client)
            try {
                withTimeout(10_000) {
                    val url = fixture.server.url("/v1/live/sessions").toString().replace("https://localhost", "wss://api.openai.com")
                    val socket = factory.open(url, "openai-test-key")
                    try {
                        socket.send("hello")
                        assertEquals("ack:hello", socket.receive())
                        val request = assertNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
                        assertEquals("Bearer openai-test-key", request.getHeader("Authorization"))
                        assertEquals(null, request.getHeader("api-key"))
                        assertEquals("/v1/live/sessions", request.path)
                        socket.close()
                    } finally { socket.cancel() }
                }
            } finally { factory.close() }
        }
    }

    @Test fun successfulTlsUpgradeExchangesFramesAndPreservesApiKeyHeader() = runBlocking {
        Fixture().use { fixture ->
            fixture.server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) { webSocket.send("ack:$text") }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            }))
            withTimeout(10_000) {
                val session = fixture.client.webSocketSession {
                    url(fixture.url)
                    header("api-key", "test-device-secret")
                }
                try {
                    session.send(Frame.Text("session.start"))
                    assertEquals("ack:session.start", (session.incoming.receive() as Frame.Text).readText())
                    val request = assertNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
                    assertEquals("test-device-secret", request.getHeader("api-key"))
                    assertEquals(null, request.getHeader("Authorization"))
                    assertEquals("/openai/v1/live/sessions", request.path)
                    session.close()
                } finally { session.cancel() }
            }
        }
    }

    @Test fun productionFactoryUsesAzureApiKeyHeaderWithoutBearerOrQuery() = runBlocking {
        Fixture().use { fixture ->
            val key = "test-device-secret"
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.getHeader("Authorization") != null -> MockResponse().setResponseCode(302)
                        .setHeader("Location", fixture.server.url("/openai/v1/live/sessions?api-key=$key"))
                    request.getHeader("api-key") != key -> MockResponse().setResponseCode(401)
                    request.path != "/openai/v1/live/sessions" -> MockResponse().setResponseCode(400)
                    else -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) { webSocket.send("ack:$text") }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                    })
                }
            }
            val factory = KtorFoundrySocketFactory(fixture.client)
            try {
                withTimeout(10_000) {
                    val socket = factory.open(fixture.url, key)
                    try {
                        socket.send("session.start")
                        assertEquals("ack:session.start", socket.receive())
                        val request = assertNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
                        assertEquals(key, request.getHeader("api-key"))
                        assertEquals(null, request.getHeader("Authorization"))
                        assertEquals("/openai/v1/live/sessions", request.path)
                        assertEquals(null, request.requestUrl?.query)
                        assertEquals(1, fixture.server.requestCount)
                        socket.close()
                    } finally { socket.cancel() }
                }
            } finally { factory.close() }
        }
    }

    private fun assertHttpFailure(status: Int) = runBlocking {
        Fixture().use { fixture ->
            val secret = "native-test-api-key"
            fixture.server.enqueue(MockResponse().setResponseCode(status)
                .setHeader("apim-request-id", "c07246be-7636-4d57-a836-75a74f18d836")
                .setBody("""{"error":{"code":"DeploymentNotFound","message":"secret=$secret"}}"""))
            val failure = try {
                withTimeout(10_000) { fixture.client.webSocketSession { url(fixture.url); header("api-key", secret) } }
                error("The failed HTTP upgrade was accepted")
            } catch (failure: Throwable) { failure }
            val translated = classifyFoundryFailure(failure, FoundryStages.HANDSHAKE)
            assertEquals(status, translated.diagnostic?.httpStatus)
            assertEquals("DeploymentNotFound", translated.diagnostic?.providerCode)
            assertEquals("c07246be-7636-4d57-a836-75a74f18d836", translated.diagnostic?.requestId)
            assertFalse(translated.toString().contains(secret))
            assertFalse(translated.toString().contains("localhost"))
        }
    }

    @Test fun unauthorizedHandshakeHasSafeMetadata() = assertHttpFailure(401)
    @Test fun forbiddenHandshakeHasSafeMetadata() = assertHttpFailure(403)
    @Test fun missingDeploymentHandshakeHasSafeMetadata() = assertHttpFailure(404)
    @Test fun throttledHandshakeHasSafeMetadata() = assertHttpFailure(429)
    @Test fun serverFailureHandshakeHasSafeMetadata() = assertHttpFailure(500)

    @Test fun redirectedHandshakeDoesNotForwardCredential() = runBlocking {
        Fixture().use { fixture ->
            MockWebServer().use { destination ->
                destination.start()
                fixture.server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", destination.url("/capture")))
                val failure = try {
                    withTimeout(10_000) {
                        fixture.client.webSocketSession { url(fixture.url); header("api-key", "private-key") }
                    }
                    error("Redirect should fail")
                } catch (failure: Throwable) { failure }
                assertEquals(302, classifyFoundryFailure(failure, FoundryStages.HANDSHAKE).diagnostic?.httpStatus)
                assertEquals(0, destination.requestCount)
            }
        }
    }

    @Test fun oversizedOrHostileErrorBodyDoesNotLeakSecrets() = runBlocking {
        Fixture().use { fixture ->
            val secret = "req_0123456789abcdef"
            fixture.server.enqueue(MockResponse().setResponseCode(400)
                .setHeader("request-id", secret)
                .setBody("""{"error":{"code":"$secret","message":"${"x".repeat(20_000)}"},"request_id":"$secret"}"""))
            val failure = try {
                withTimeout(10_000) { fixture.client.webSocketSession { url(fixture.url); header("api-key", secret) } }
                error("Bad HTTP upgrade should fail")
            } catch (failure: Throwable) { failure }
            val result = classifyFoundryFailure(failure, FoundryStages.HANDSHAKE)
            assertEquals(400, result.diagnostic?.httpStatus)
            assertEquals(null, result.diagnostic?.providerCode)
            assertEquals(null, result.diagnostic?.requestId)
            assertFalse(result.toString().contains(secret))
            assertTrue(result.toString().length < 1_000)
        }
    }
}
