package app.livelingo.core

import kotlinx.serialization.json.*
import kotlin.test.*

class ModelRoutingTest {
    private fun connection(model: SpeechModel, provider: ModelProvider = ModelProvider.FOUNDRY) = FoundryConnection(
        if (provider == ModelProvider.OPENAI) "https://api.openai.com" else "https://demo.services.ai.azure.com", "custom-deployment", provider, model)

    @Test fun routesEachProtocolWithoutChangingCredentialBindingOrTextEndpoint() {
        for (provider in ModelProvider.entries) {
            val base = if (provider == ModelProvider.OPENAI) "wss://api.openai.com/v1/" else "wss://demo.services.ai.azure.com/openai/v1/"
            for (model in SpeechModel.entries.filter { it.supportedBy(provider) }) {
                val c = connection(model, provider)
                assertEquals(base + "live/sessions", c.credentialEndpoint())
                assertEquals(base.replace("wss://", "https://") + "chat/completions", c.chatCompletionsUrl())
                assertEquals(when (model) {
                    SpeechModel.GPT_LIVE -> base + "live/sessions"
                    SpeechModel.REALTIME_TRANSLATE -> base + "realtime/translations?model=custom-deployment"
                    SpeechModel.LIVE_TRANSCRIBE -> base + "realtime?intent=transcription"
                    SpeechModel.MAI_TRANSCRIBE -> "wss://demo.services.ai.azure.com/mai/v1/realtime?intent=transcription"
                }, c.webSocketUrl())
            }
        }
    }
    @Test fun maiIsOnlyAvailableWithFoundryResourceEndpoint() {
        assertNotNull(connection(SpeechModel.MAI_TRANSCRIBE, ModelProvider.OPENAI).validationError())
        assertNotNull(connection(SpeechModel.MAI_TRANSCRIBE).copy(endpoint = "https://demo.openai.azure.com").validationError())
        assertNull(connection(SpeechModel.MAI_TRANSCRIBE).validationError())
    }
    @Test fun translationUsesDedicatedSchemaWithOptionalSourceTranscription() {
        val settings = SessionSettings(knowledge = BackgroundKnowledge("k", "k", "PRIVATE REFERENCE"))
        val c = connection(SpeechModel.REALTIME_TRANSLATE).copy(sourceTranscriptionModel = "source-custom")
        val body = decodeRealtimeObject(realtimeStartPayload(c, settings))
        assertEquals("session.update", body.eventString("type"))
        val session = body.getValue("session").jsonObject
        assertEquals(setOf("audio"), session.keys)
        assertFalse(body.toString().contains("PRIVATE"))
        assertEquals("zh", session.getValue("audio").jsonObject.getValue("output").jsonObject.eventString("language"))
        assertTrue("source-custom" in body.toString())
        assertFalse("transcription" in realtimeStartPayload(c.copy(sourceTranscriptionModel = ""), settings))
    }
    @Test fun transcriptionSchemasMatchProvidersAndDoNotSendLiveFields() {
        for (provider in ModelProvider.entries) {
            val body = realtimeStartPayload(connection(SpeechModel.LIVE_TRANSCRIBE, provider), SessionSettings(sourceLanguage = Language.INDIAN_ENGLISH))
            assertTrue("24000" in body)
            assertTrue("\"turn_detection\":null" in body)
            assertTrue(if (provider == ModelProvider.OPENAI) "\"languages\":[\"en\"]" in body else "\"language\":\"en\"" in body)
            assertFalse("instructions" in body)
            assertFalse("delegation" in body)
        }
        val mai = realtimeStartPayload(connection(SpeechModel.MAI_TRANSCRIBE), SessionSettings())
        assertFalse("delay" in mai); assertFalse("prompt" in mai)
    }
    @Test fun audioUsesProtocolSpecificEventAndStillValidatesPcm() {
        for (model in SpeechModel.entries.filter { it != SpeechModel.GPT_LIVE }) {
            val event = decodeRealtimeObject(realtimeAudioPayload(model, byteArrayOf(1, 0)))
            assertEquals(if (model.transcribes) "input_audio_buffer.append" else "session.input_audio_buffer.append", event.eventString("type"))
            assertEquals("AQA=", event.eventString("audio"))
            assertFails { realtimeAudioPayload(model, byteArrayOf(1)) }
            assertFails { realtimeAudioPayload(model, ByteArray(48_002)) }
        }
    }
    @Test fun translationElapsedTimesAndFoundryTextEventsProduceValidRanges() {
        val clock = TranslationDeltaClock()
        val first = clock.delta(decodeRealtimeObject("""{"type":"session.output_transcript.delta","delta":"台灣","elapsed_ms":200}"""), 0)!!
        val second = clock.delta(decodeRealtimeObject("""{"type":"response.text.delta","text":"資訊"}"""), 500)!!
        assertEquals(0L, first.startMs); assertEquals(200L, first.endMs)
        assertEquals(200L, second.startMs); assertEquals(500L, second.endMs)
        assertFails { clock.delta(decodeRealtimeObject("""{"type":"session.output_transcript.delta","delta":"x","elapsed_ms":-1}"""), 0) }
    }
    @Test fun explicitTextKindControlsParametersEvenWithCustomDeploymentName() {
        for (kind in TextModel.entries) {
            val body = buildJsonObject { textModelParameters(kind, 1024) }
            assertEquals(kind.effort, body["reasoning_effort"]?.jsonPrimitive?.content)
            assertFalse("temperature" in body)
            assertEquals(if (kind == TextModel.GPT61_SOL) 8192 else 1024, body["max_completion_tokens"]?.jsonPrimitive?.int)
        }
    }
}
