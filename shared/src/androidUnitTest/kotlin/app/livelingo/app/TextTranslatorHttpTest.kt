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
    @Test fun streamingPublishesTheFirstTokenBeforeTheServerCanFinishAndCountsUsageOnce() = runBlocking {
        val gate = java.util.concurrent.CountDownLatch(1)
        val observed = java.util.concurrent.atomic.AtomicBoolean()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        lateinit var request: JsonObject
        server.createContext("/") { exchange ->
            request = WireJson.parseToJsonElement(exchange.requestBody.bufferedReader().use { it.readText() }).jsonObject
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.bufferedWriter().use { writer ->
                fun frame(json: String) { writer.write("data: $json\n\n"); writer.flush() }
                frame("""{"choices":[{"delta":{"content":"請"}}]}""")
                observed.set(gate.await(8, TimeUnit.SECONDS))
                frame("""{"choices":[{"delta":{"content":"繼續。"},"finish_reason":"stop"}]}""")
                frame("""{"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":3}}""")
                frame("[DONE]")
            }
        }
        server.start()
        val translator = HttpTextTranslator(createAndroidFoundryHttpClient {
            addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url("http://127.0.0.1:${server.address.port}/").build())
            }
        })
        val samples = mutableListOf<UsageSample>()
        translator.setUsageListener { samples += it }
        try {
            val connection = FoundryConnection("https://api.openai.com", "voice", ModelProvider.OPENAI,
                SpeechModel.GPT_LIVE, TextModel.GPT6_LUNA, "my-luna")
            val drafts = mutableListOf<String>()
            val result = translator.stream(connection, "test-key", SessionSettings(), "Please continue.", "") {
                drafts += it
                if (it == "請") gate.countDown()
            }
            assertTrue(observed.get(), "The first token should render while the remaining response is withheld")
            assertEquals(listOf("請", "請繼續。"), drafts)
            assertEquals("請繼續。", result)
            assertEquals(true, request["stream"]?.jsonPrimitive?.boolean)
            assertEquals(true, request["stream_options"]?.jsonObject?.get("include_usage")?.jsonPrimitive?.boolean)
            assertEquals(3L, samples.single { it.final }.outputTokens)
        } finally { gate.countDown(); translator.close(); server.stop(0) }
    }

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
            val usages = mutableListOf<UsageSample>()
            translator.setUsageListener { usages += it }
            fun enqueue(finish: String, content: String) {
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(buildJsonObject {
                    putJsonObject("usage") { put("prompt_tokens", 100); put("completion_tokens", 20) }
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
                assertEquals(7, usages.size) // four pending requests, three received usage reports
                assertEquals(3, usages.count { it.final && it.inputTokens == 100L && it.outputTokens == 20L })
                assertFalse(usages.last().known) // redirected request has no usage, never invent zero
                assertEquals(4, usages.map { it.id }.distinct().size)
                assertEquals(setOf(host), requestedHosts.toSet())
                enqueue("stop", "我們繼續。 Can we call it?")
                enqueue("stop", "我們繼續。可以呼叫它嗎？")
                val source = "Let us continue. Can we call it?"
                assertEquals("我們繼續。可以呼叫它嗎？", translator.translate(connection, "test-secret", SessionSettings(), source, ""))
                enqueue("stop", "Can we call it?")
                enqueue("stop", "Can we call it?")
                val rejected = assertFailsWith<TranslationFailure> {
                    translator.translate(connection, "test-secret", SessionSettings(), source, "")
                }
                assertEquals("TARGET_LANGUAGE_MISMATCH", rejected.diagnostic?.code)
                assertEquals(8, usages.map { it.id }.distinct().size)
                enqueue("stop", " ")
                enqueue("stop", "請繼續。")
                assertEquals("請繼續。", translator.translate(connection, "test-secret", SessionSettings(), "Please continue.", ""))
                enqueue("stop", "")
                enqueue("stop", " ")
                assertTrue(assertFailsWith<TranslationFailure> {
                    translator.translate(connection, "test-secret", SessionSettings(), "Please continue.", "")
                }.targetLanguageMismatch)
            } finally { translator.close(); server.shutdown() }
        }
    }
}
