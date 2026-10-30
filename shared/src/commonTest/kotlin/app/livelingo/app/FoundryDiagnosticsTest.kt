package app.livelingo.app

import io.ktor.client.plugins.websocket.WebSocketException
import kotlin.test.*

private class TestHttpFailure(
    override val statusCode: Int,
    override val providerCode: String? = null,
    override val requestId: String? = null,
) : Exception("secret-response-body private-key"), FoundryHttpFailure
private class UnknownHostException : Exception("private-key in private-url")
private class SSLHandshakeException : Exception("private-certificate-content")
private class SocketTimeoutException : Exception("private-key")
private class ProtocolException(message: String) : Exception(message)

class FoundryDiagnosticsTest {
    @Test fun classifiesHttpStatusesWithoutExposingResponseText() {
        for (status in listOf(302, 400, 401, 403, 404, 408, 409, 429, 500, 503, 504)) {
            val failure = classifyFoundryFailure(TestHttpFailure(status, "DeploymentNotFound", "12345678-1234-1234-1234-123456789abc"), FoundryStages.HANDSHAKE)
            assertEquals(status, failure.diagnostic?.httpStatus)
            assertEquals("HTTP_$status", failure.diagnostic?.code)
            assertEquals("DeploymentNotFound", failure.diagnostic?.providerCode)
            assertEquals("12345678-1234-1234-1234-123456789abc", failure.diagnostic?.requestId)
            assertTrue(failure.message.orEmpty().contains("HTTP $status"))
            assertFalse(failure.toString().contains("private-key"))
            assertFalse(failure.toString().contains("secret-response-body"))
        }
    }

    @Test fun extractsOnlyKnownCodesAndBoundedRequestIds() {
        val fields = safeJsonErrorFields("""{"error":{"code":"DeploymentNotFound","message":"private-key"},"request_id":"12345678-1234-1234-1234-123456789abc"}""", "private-key")
        assertEquals("DeploymentNotFound", fields.providerCode)
        assertEquals("12345678-1234-1234-1234-123456789abc", fields.requestId)
        assertEquals(SafeFoundryErrorFields(), safeJsonErrorFields("""{"error":{"code":"private-key","message":"private-key"},"request_id":"req_private-key"}""", "private-key"))
        assertEquals(SafeFoundryErrorFields(), safeJsonErrorFields("x".repeat(16_385)))
        assertEquals(SafeFoundryErrorFields(), safeJsonErrorFields("<html>private-key</html>"))
        for (value in listOf("Bearer private-key", "https://evil.test/?key=private-key", "id\r\nHeader: secret", "req_" + "x".repeat(65)))
            assertNull(safeRequestId(value))
        val key = "abcdef0123456789abcdef0123456789"
        assertNull(safeRequestId(key.uppercase(), key))
    }

    @Test fun classifiesCauseTypesWithoutPublishingMessages() {
        for ((cause, category) in listOf(UnknownHostException() to "DNS", SSLHandshakeException() to "TLS", SocketTimeoutException() to "TIMEOUT")) {
            val failure = classifyFoundryFailure(Exception("private-wrapper", cause), FoundryStages.STREAM)
            assertEquals(category, failure.diagnostic?.code)
            assertEquals(FoundryStages.STREAM, failure.diagnostic?.stage)
            assertNull(failure.diagnostic?.httpStatus)
            assertFalse(failure.message.orEmpty().contains("private"))
        }
    }

    @Test fun recognizesOnlyAdapterGeneratedHandshakeStatusPatterns() {
        assertEquals(403, classifyFoundryFailure(ProtocolException("Expected HTTP 101 response but was '403 Forbidden'"), FoundryStages.HANDSHAKE).diagnostic?.httpStatus)
        assertEquals(401, classifyFoundryFailure(WebSocketException("Handshake exception, expected status code 101 but was 401"), FoundryStages.HANDSHAKE).diagnostic?.httpStatus)
        assertNull(classifyFoundryFailure(Exception("HTTP 401 private-key"), FoundryStages.HANDSHAKE).diagnostic?.httpStatus)
        val failure = classifyFoundryFailure(WebSocketException("Max frame size switch is not supported in OkHttp engine."), FoundryStages.HANDSHAKE)
        assertEquals("CLIENT_CONFIGURATION", failure.diagnostic?.code)
    }

    @Test fun unknownNativeFailureRemainsHonestAndSafe() {
        val failure = classifyFoundryFailure(Exception("https://resource.openai.azure.com/?api-key=private-key private transcript"), FoundryStages.START)
        assertEquals("NETWORK_UNKNOWN", failure.diagnostic?.code)
        assertNull(failure.diagnostic?.httpStatus)
        assertFalse(failure.message.orEmpty().contains("private"))
        assertFalse(failure.message.orEmpty().contains("https"))
    }
}
