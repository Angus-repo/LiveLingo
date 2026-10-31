package app.livelingo.app

import app.livelingo.core.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import kotlin.test.*

class ParagraphHttpTest {
    private val record = TranscriptRecord("r", "x", 0, SessionSettings(), listOf(
        TranscriptFragment("in", TranscriptChannel.INPUT, 0, 1000, "Taiwan is 4.95 trillion. India is 4.92 trillion. Then analyze the reasons."),
        TranscriptFragment("f", TranscriptChannel.OUTPUT, 0, 1000, "台湾4.95兆。\n\n印度4.92兆。\n\n接着分析原因。")
    ))

    private fun success(ids: String = "[2]"): String = buildJsonObject {
        putJsonArray("choices") { add(buildJsonObject {
            put("finish_reason", "stop")
            putJsonObject("message") { put("content", "{\"input\":[2],\"output\":$ids,\"alignment\":[{\"input\":2,\"output\":2}]}") }
        }) }
    }.toString()

    @Test fun nativeHttpUsesSelectedProviderOnlyAndReturnsBoundaries() = runBlocking {
        for (provider in ModelProvider.entries) {
            val server = MockWebServer()
            val cert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
            val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
            val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
            server.useHttps(serverTls.sslSocketFactory(), false); server.start()
            var originalHost = ""
            val client = createAndroidFoundryHttpClient {
                sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                addInterceptor { chain ->
                    originalHost = chain.request().url.host
                    chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
                }
            }
            val formatter = HttpParagraphFormatter(client)
            val connection = if (provider == ModelProvider.OPENAI) FoundryConnection("https://api.openai.com", provider = provider)
                else FoundryConnection("https://demo.openai.azure.com")
            try {
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(success()))
                val layouts = formatter.format(connection.copy(textModelKind = TextModel.GPT6_LUNA), "isolated-test-secret", "my-text-model", record)
                assertEquals(2, layouts.size)
                val layout = layouts.single { it.channel == TranscriptChannel.OUTPUT }
                assertEquals(1, layout.breakOffsets.size)
                assertTrue(layout.source.startsWith("台灣4.95兆"))
                assertTrue(layout.replaceExistingBreaks)
                assertNotNull(layout.originalReference)
                assertEquals("台灣4.95兆。 印度4.92兆。\n\n接著分析原因。", liveCaptionText(record.copy(paragraphLayouts = layouts), TranscriptChannel.OUTPUT))
                val request = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                val body = request.body.readUtf8()
                assertFalse("isolated-test-secret" in body)
                val payload = WireJson.parseToJsonElement(body).jsonObject
                assertEquals("my-text-model", payload["model"]?.jsonPrimitive?.content)
                assertEquals("none", payload["reasoning_effort"]?.jsonPrimitive?.content)
                assertFalse("temperature" in payload)
                if (provider == ModelProvider.OPENAI) {
                    assertEquals("api.openai.com", originalHost)
                    assertEquals("/v1/chat/completions", request.path)
                    assertEquals("Bearer isolated-test-secret", request.getHeader("Authorization"))
                    assertNull(request.getHeader("api-key"))
                } else {
                    assertEquals("demo.openai.azure.com", originalHost)
                    assertEquals("/openai/v1/chat/completions", request.path)
                    assertEquals("isolated-test-secret", request.getHeader("api-key"))
                    assertNull(request.getHeader("Authorization"))
                }
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://other.example/test"))
                assertFails { formatter.format(connection.copy(textModelKind = TextModel.GPT6_LUNA), "isolated-test-secret", "my-text-model", record) }
                assertEquals(2, server.requestCount) // No redirect to the other destination.
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(success("[999]")))
                assertFails { formatter.format(connection.copy(textModelKind = TextModel.GPT6_LUNA), "isolated-test-secret", "my-text-model", record) }
            } finally { formatter.close(); server.shutdown() }
        }
    }
}
