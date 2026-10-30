package app.livelingo.core

import kotlinx.serialization.json.*
import kotlin.test.*

class FoundryProtocolTest {
    @Test fun contentModerationIsASeparateEventWithSafeMessages() {
        for (code in listOf("content_filter", "CONTENT_FILTER", "ResponsibleAIPolicyViolation")) {
            assertEquals(FoundryEvent.ContentFiltered, parseFoundryEvent("""{"type":"error","error":{"code":"$code","message":"private transcript"}}"""))
            assertTrue(foundryProviderError(code).contains("內容安全篩選"))
        }
        assertEquals(FoundryEvent.ContentFiltered, parseFoundryEvent("""{"type":"error","error":{"code":"BadRequest","innererror":{"code":"ResponsibleAIPolicyViolation"}}}"""))
        assertEquals(FoundryEvent.Closed("content"), parseFoundryEvent("""{"type":"session.closed","reason":"content"}"""))
        assertNotNull(foundryCloseError("content", requestedByClient = true))
        assertFalse(foundryProviderError("content_filter").contains("請檢查部署"))
    }

    private val connection = FoundryConnection("https://sample.openai.azure.com")

    @Test fun normalizesKnownEndpointPathsExactlyOnce() {
        for (path in listOf("", "/", "/openai", "/openai/v1/", "/openai/v1/live", "/openai/v1/live/sessions/")) {
            val value = FoundryConnection(" HTTPS://Sample.openai.azure.com:443$path ")
            assertNull(value.validationError())
            assertEquals("wss://sample.openai.azure.com/openai/v1/live/sessions", value.webSocketUrl())
        }
        for (suffix in listOf("openai.azure.com", "services.ai.azure.com", "cognitiveservices.azure.com"))
            assertNull(FoundryConnection("https://sample.$suffix").validationError())
    }

    @Test fun rejectsCredentialExfiltrationAndNonCanonicalEndpoints() {
        for (url in listOf(
            "http://sample.openai.azure.com", "wss://sample.openai.azure.com", "https://sample.openai.azure.com.evil.test",
            "https://evil.test", "https://sample.openai.azure.com@evil.test", "https://user:pass@sample.openai.azure.com",
            "https://sample.openai.azure.com:8443", "https://sample.openai.azure.com/?api-key=secret",
            "https://sample.openai.azure.com/#secret", "https://sample.openai.azure.com/openai/deployments/x",
            "https://sample.openai.azure.com/openai/v1/live/sessions/openai/v1/live/sessions",
            "https://sample.openai.azure.com/../openai/v1/live/sessions", "https://sample.openai.azure.com/%2fopenai",
            "https://.openai.azure.com", "https://sample..openai.azure.com", "https://sample.openai.azure.com./",
            "https://sample.openai.azure.com\\@evil.test", "https://-sample.openai.azure.com",
        )) {
            assertNotNull(FoundryConnection(url).validationError(), url)
            assertFailsWith<IllegalArgumentException>(url) { FoundryConnection(url).webSocketUrl() }
        }
    }

    @Test fun validatesDeploymentAndNeverPlacesItInTheUrl() {
        assertEquals(connection.webSocketUrl(), connection.copy(deployment = "custom-live_1").webSocketUrl())
        for (name in listOf("", "../secret", "a b", "x\nAuthorization", "x".repeat(129)))
            assertNotNull(connection.copy(deployment = name).validationError())
    }

    @Test fun startUsesOnlyOfficialLiveFieldsAndSelectedKnowledge() {
        val settings = SessionSettings(sourceLanguage = Language.INDIAN_ENGLISH, knowledge = BackgroundKnowledge("bank", "銀行", "OTP 保留縮寫"))
        val payload = WireJson.parseToJsonElement(foundryStartPayload(connection.copy(deployment = "live-prod"), settings)).jsonObject
        assertEquals("session.start", payload.getValue("type").jsonPrimitive.content)
        val session = payload.getValue("session").jsonObject
        assertEquals(setOf("model", "instructions", "audio", "delegation"), session.keys)
        assertEquals("live-prod", session.getValue("model").jsonPrimitive.content)
        assertTrue(session.getValue("instructions").jsonPrimitive.content.contains("OTP"))
        assertFalse(payload.toString().contains("modalities"))
        assertFalse(payload.toString().contains("api-key"))
    }

    @Test fun audioUsesRawPcmAndRejectsWrongLengthsOrContainers() {
        val payload = WireJson.parseToJsonElement(foundryAudioPayload(byteArrayOf(0, 0, 1, 0))).jsonObject
        assertEquals("session.input_audio.append", payload.getValue("type").jsonPrimitive.content)
        assertEquals("AAABAA==", payload.getValue("audio").jsonPrimitive.content)
        for (bytes in listOf(byteArrayOf(), byteArrayOf(1), ByteArray(48_002), "RIFF0000WAVE0000".encodeToByteArray()))
            assertFailsWith<FoundryProtocolException> { foundryAudioPayload(bytes) }
        assertEquals("{\"type\":\"session.close\"}", FOUNDRY_CLOSE_PAYLOAD)
    }

    @Test fun preservesIndependentTranscriptTimesAndRejectsMalformedRanges() {
        val input = assertIs<FoundryEvent.Transcript>(parseFoundryEvent("""{"type":"session.input_transcript.delta","delta":"信用卡","start_ms":600,"end_ms":900}""")).delta
        val output = assertIs<FoundryEvent.Transcript>(parseFoundryEvent("""{"type":"session.output_transcript.delta","delta":"credit card","start_ms":1500,"end_ms":2200}""")).delta
        assertEquals(TranscriptChannel.INPUT, input.channel)
        assertEquals(TranscriptChannel.OUTPUT, output.channel)
        assertEquals(600L, input.startMs)
        assertEquals(1500L, output.startMs)
        for ((start, end) in listOf(-1 to 5, 5 to 5, 10 to 1))
            assertFailsWith<FoundryProtocolException> { parseFoundryEvent("""{"type":"session.input_transcript.delta","delta":"x","start_ms":$start,"end_ms":$end}""") }
        assertFailsWith<FoundryProtocolException> { parseFoundryEvent("""{"type":"session.started","session":{}}""") }
    }

    @Test fun providerErrorsNeverEchoTheirMessageOrUnknownCode() {
        val failure = assertFailsWith<FoundryProtocolException> {
            parseFoundryEvent("""{"type":"error","error":{"code":"SECRET_KEY","message":"Bearer private-key"}}""")
        }
        assertFalse(failure.message.orEmpty().contains("SECRET"))
        assertFalse(failure.message.orEmpty().contains("private-key"))
        assertTrue(foundryProviderError("invalid_api_key").contains("認證"))
    }

    @Test fun providerCloseReasonsRemainErrorsUnlessClientRequestedNormalStop() {
        assertNull(foundryCloseError("close_requested", true))
        assertNull(foundryCloseError(null, true))
        for (reason in listOf("expired", "content", "remote_hangup", "connection_lost", "unknown"))
            assertNotNull(foundryCloseError(reason, true))
        assertNotNull(foundryCloseError("close_requested", false))
        assertEquals(FoundryEvent.Closed("expired"), parseFoundryEvent("""{"type":"session.closed","reason":"expired"}"""))
    }

    @Test fun usageAudioAndFutureEventsAreConsumedWithoutFabricatedCaptions() {
        for (type in listOf("session.usage.updated", "session.output_audio.delta", "future.event"))
            assertEquals(FoundryEvent.Ignored, parseFoundryEvent("""{"type":"$type"}"""))
        assertEquals(FoundryEvent.Ready("s"), parseFoundryEvent("""{"type":"session.started","session":{"id":"s"},"future":true}"""))
        assertFailsWith<FoundryProtocolException> { parseFoundryEvent("[]") }
        assertFailsWith<FoundryProtocolException> { parseFoundryEvent("{}") }
        assertFailsWith<FoundryProtocolException> { parseFoundryEvent("x".repeat(MAX_FOUNDRY_FRAME_BYTES + 1)) }
    }
}
