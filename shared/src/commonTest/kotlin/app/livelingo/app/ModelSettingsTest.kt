package app.livelingo.app

import app.livelingo.core.*
import kotlinx.serialization.json.*
import kotlin.test.*

private class SettingsMemory : LocalStorage {
    val files = mutableMapOf<String, String>()
    val secrets = mutableMapOf<String, String>()
    override fun read(key: String) = files[key]
    override fun write(key: String, value: String) { files[key] = value }
    override fun delete(key: String) { files.remove(key) }
    override fun readSecret(key: String) = secrets[key]
    override fun writeSecret(key: String, value: String) { secrets[key] = value }
}

class ModelSettingsTest {
    @Test fun modelMenusKeepExistingCustomIdsAndAreScopedToProviderAndResource() {
        val original = AppPreferences(foundryEndpoint = "https://one.openai.azure.com", deployment = "company-live",
            foundryTextModel = "company-sol", foundryTextKind = TextModel.GPT61_SOL, foundrySourceTranscript = "company-whisper")
        val next = original.connection().copy(deployment = "gpt-realtime-translate", speechModel = SpeechModel.REALTIME_TRANSLATE,
            textDeployment = "gpt-6-luna", textModelKind = TextModel.GPT6_LUNA, sourceTranscriptionModel = "")
        val memory = SettingsMemory(); val repo = AppRepository(memory)
        repo.savePreferences(original.copy(deployment = next.deployment, foundrySpeechKind = next.speechModel,
            foundryTextModel = next.textDeployment, foundryTextKind = next.textModelKind, foundrySourceTranscript = "",
            savedModelChoices = original.rememberModelChoices(next)))
        val restored = repo.preferences()
        val sameResource = next.copy(endpoint = "https://one.openai.azure.com/openai/v1/")
        assertTrue(ModelSelection(SpeechModel.GPT_LIVE.name, "company-live") in modelSelections(restored, sameResource, ModelRole.SPEECH))
        assertTrue(ModelSelection(TextModel.GPT61_SOL.name, "company-sol") in modelSelections(restored, sameResource, ModelRole.TEXT))
        assertTrue(ModelSelection("", "company-whisper") in modelSelections(restored, sameResource, ModelRole.SOURCE_TRANSCRIPT))
        val otherResource = next.copy(endpoint = "https://two.openai.azure.com")
        assertTrue(modelSelections(restored, otherResource, ModelRole.SPEECH).none { it.modelId == "company-live" })
        val openai = restored.copy(provider = ModelProvider.OPENAI).connection()
        assertTrue(modelSelections(restored, openai, ModelRole.TEXT).none { it.modelId == "company-sol" })
        assertTrue(modelSelections(restored, openai, ModelRole.SPEECH).none { it.kind == SpeechModel.MAI_TRANSCRIBE.name })
    }

    @Test fun aSavedOpenAiSnapshotKeepsItsParametersAndKnownModelsHaveOneOption() {
        val prefs = AppPreferences(provider = ModelProvider.OPENAI, openaiTextKind = TextModel.GPT61_SOL,
            openaiTextModel = "gpt-6.1-sol-2026-10-01")
        val choices = modelSelections(prefs, prefs.connection(), ModelRole.TEXT)
        val snapshot = choices.single { it.modelId == prefs.openaiTextModel }
        val parameters = WireJson.parseToJsonElement(paragraphRequest(snapshot.modelId, emptyList(), TextModel.valueOf(snapshot.kind))).jsonObject
        assertEquals("low", parameters.eventString("reasoning_effort"))
        assertEquals(prefs.openaiTextModel, parameters.eventString("model"))
        assertEquals(1, choices.count { it.modelId == "gpt-6-luna" })
        assertNotNull(ModelSelection(SpeechModel.GPT_LIVE.name, "gpt-realtime-translate").validationError(ModelRole.SPEECH, ModelProvider.OPENAI))
        assertNotNull(ModelSelection(TextModel.GPT6_LUNA.name, "gpt-6.1-sol").validationError(ModelRole.TEXT, ModelProvider.OPENAI))
        assertNull(ModelSelection(SpeechModel.GPT_LIVE.name, "company-live").validationError(ModelRole.SPEECH, ModelProvider.FOUNDRY))
    }

    @Test fun bothProvidersDefaultToLuna() {
        val p = AppPreferences()
        assertEquals("gpt-6-luna", p.foundryTextModel); assertEquals("gpt-6-luna", p.openaiTextModel)
        assertEquals(TextModel.GPT6_LUNA, p.connection().textModelKind)
    }
    @Test fun oldDefaultsMigrateOnceButCustomDeploymentAndKeysRemainIntact() {
        val memory = SettingsMemory()
        memory.files["preferences"] = """{"foundryEndpoint":"https://demo.openai.azure.com","deployment":"my-live","foundryTextModel":"my-company-text","openaiTextModel":"gpt-4.1-mini"}"""
        val repository = AppRepository(memory)
        val endpoint = "wss://demo.openai.azure.com/openai/v1/live/sessions"
        repository.saveApiKey(endpoint, "existing-key")
        val p = repository.preferences()
        assertEquals("my-company-text", p.foundryTextModel)
        assertEquals(TextModel.CUSTOM, p.foundryTextKind)
        assertEquals("gpt-6-luna", p.openaiTextModel)
        repository.migrateLegacyConnection(p)
        repository.savePreferences(p.copy(openaiTextModel = "gpt-4.1-mini", openaiTextKind = TextModel.GPT41_MINI))
        assertEquals("gpt-4.1-mini", repository.preferences().openaiTextModel)
        assertEquals("existing-key", repository.apiKey(p.connection().copy(speechModel = SpeechModel.REALTIME_TRANSLATE).credentialEndpoint()))
    }
    @Test fun switchingProvidersKeepsIndependentTypesDeploymentsAndKeys() {
        val memory = SettingsMemory(); val repo = AppRepository(memory)
        val p = AppPreferences(foundryEndpoint = "https://demo.services.ai.azure.com", foundrySpeechKind = SpeechModel.MAI_TRANSCRIBE,
            deployment = "mai-custom", foundryTextKind = TextModel.GPT61_SOL, foundryTextModel = "sol-custom",
            openaiSpeechKind = SpeechModel.REALTIME_TRANSLATE, openaiModel = "gpt-realtime-translate")
        repo.savePreferences(p)
        repo.saveApiKey(p.connection().credentialEndpoint(), "azure-key")
        val openai = p.copy(provider = ModelProvider.OPENAI).connection()
        repo.saveApiKey(openai.credentialEndpoint(), "openai-key")
        assertEquals(SpeechModel.MAI_TRANSCRIBE, repo.preferences().connection().speechModel)
        assertEquals(TextModel.GPT6_LUNA, openai.textModelKind)
        assertEquals("azure-key", repo.apiKey(p.connection().credentialEndpoint()))
        assertEquals("openai-key", repo.apiKey(openai.credentialEndpoint()))
    }
    @Test fun paragraphRequestsHaveLunaNoneAndSolLowWithoutRewritingPayload() {
        for ((kind, effort) in listOf(TextModel.GPT6_LUNA to "none", TextModel.GPT54_MINI to "none", TextModel.GPT61_SOL to "low", TextModel.GPT41_MINI to null)) {
            val request = WireJson.parseToJsonElement(paragraphRequest("arbitrary-deployment", emptyList(), kind)).jsonObject
            assertEquals(effort, request["reasoning_effort"]?.jsonPrimitive?.content)
            assertEquals("arbitrary-deployment", request.eventString("model"))
            assertEquals("json_object", request["response_format"]?.jsonObject?.eventString("type"))
            assertFalse("temperature" in request)
        }
    }
    @Test fun textTranslationIsSeparateFromParagraphFormattingAndUsesBoundedContext() {
        val c = AppPreferences().connection().copy(endpoint = "https://demo.openai.azure.com", textDeployment = "luna-custom")
        val request = WireJson.parseToJsonElement(translationTextRequest(c, SessionSettings(), "What is 4.95?", "x".repeat(10_000))).jsonObject
        assertEquals("none", request.eventString("reasoning_effort"))
        assertEquals("luna-custom", request.eventString("model"))
        assertFalse("response_format" in request)
        val data = WireJson.parseToJsonElement(request["messages"]!!.jsonArray[1].jsonObject.eventString("content")!!).jsonObject
        assertEquals("What is 4.95?", data.eventString("CURRENT_TEXT"))
        assertEquals(2500, data.eventString("PREVIOUS_CONTEXT")!!.length)
    }
}
