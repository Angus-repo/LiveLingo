package app.livelingo.app

import app.livelingo.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.util.concurrent.TimeUnit
import kotlin.test.*

class TextTranslatorHttpTest {
    @Test fun textTranslationUsesLunaWithProviderCredentialsAndRejectsIncompleteOrRedirectedResults() = runBlocking {
        for (provider in ModelProvider.entries) {
            val server = MockWebServer()
            val cert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
            val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
            val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
            server.useHttps(serverTls.sslSocketFactory(), false); server.start()
            val requestedHosts = mutableListOf<String>()
            val translator = HttpTextTranslator(createAndroidFoundryHttpClient {
                sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                addInterceptor { chain ->
                    requestedHosts += chain.request().url.host
                    chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
                }
            })
            val host = if (provider == ModelProvider.OPENAI) "api.openai.com" else "demo.services.ai.azure.com"
            val connection = FoundryConnection("https://$host", "voice", provider,
                if (provider == ModelProvider.OPENAI) SpeechModel.LIVE_TRANSCRIBE else SpeechModel.MAI_TRANSCRIBE,
                TextModel.GPT6_LUNA, "my-luna")
            fun enqueue(finish: String, content: String) {
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(buildJsonObject {
                    putJsonArray("choices") { add(buildJsonObject {
                        put("finish_reason", finish); putJsonObject("message") { put("content", content) }
                    }) }
                }.toString()))
            }
            try {
                enqueue("stop", "保留 123.45")
                assertEquals("保留 123.45", translator.translate(connection, "test-secret", SessionSettings(), "Keep 123.45", "prior text"))
                val request = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                assertEquals(if (provider == ModelProvider.OPENAI) "/v1/chat/completions" else "/openai/v1/chat/completions", request.path)
                assertEquals(if (provider == ModelProvider.OPENAI) "Bearer test-secret" else null, request.getHeader("Authorization"))
                assertEquals(if (provider == ModelProvider.FOUNDRY) "test-secret" else null, request.getHeader("api-key"))
                val payload = WireJson.parseToJsonElement(request.body.readUtf8()).jsonObject
                assertEquals("my-luna", payload.eventString("model"))
                assertEquals("none", payload.eventString("reasoning_effort"))
                assertFalse("temperature" in payload)
                val input = WireJson.parseToJsonElement(payload["messages"]!!.jsonArray[1].jsonObject.eventString("content")!!).jsonObject
                assertEquals("Keep 123.45", input.eventString("CURRENT_TEXT"))
                assertEquals("prior text", input.eventString("PREVIOUS_CONTEXT"))
                enqueue("content_filter", "")
                assertEquals("CONTENT_FILTER", assertFailsWith<TranslationFailure> { translator.translate(connection, "test-secret", SessionSettings(), "source", "") }.diagnostic?.code)
                enqueue("length", "Incomplete")
                assertFailsWith<TranslationFailure> { translator.translate(connection, "test-secret", SessionSettings(), "source", "") }
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://other.example/capture").setBody("test-secret PRIVATE"))
                val redirect = assertFailsWith<TranslationFailure> { translator.translate(connection, "test-secret", SessionSettings(), "source", "") }
                assertEquals("HTTP_302", redirect.diagnostic?.code)
                assertFalse("PRIVATE" in redirect.message.orEmpty()); assertFalse("test-secret" in redirect.message.orEmpty())
                assertEquals(4, server.requestCount)
                assertEquals(setOf(host), requestedHosts.toSet())
            } finally { translator.close(); server.shutdown() }
        }
    }
}
