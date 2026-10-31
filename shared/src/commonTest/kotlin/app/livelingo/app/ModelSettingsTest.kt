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
