package app.livelingo.core

import kotlinx.serialization.Serializable

/** Contains no credentials. The API key lives separately in platform secure storage. */
@Serializable
enum class ModelProvider(val label: String) { FOUNDRY("Microsoft Foundry"), OPENAI("OpenAI") }

@Serializable
data class FoundryConnection(
    val endpoint: String,
    val deployment: String = "gpt-live-1",
    val provider: ModelProvider = ModelProvider.FOUNDRY,
    val speechModel: SpeechModel = SpeechModel.GPT_LIVE,
    val textModelKind: TextModel = TextModel.CUSTOM,
    val textDeployment: String = "",
    val sourceTranscriptionModel: String = "",
)

private val endpointPattern = Regex("^https://([A-Za-z0-9.-]+)(?::443)?(/[^?#]*)?$", RegexOption.IGNORE_CASE)
private val hostnameLabel = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
private val endpointSuffixes = listOf(".openai.azure.com", ".services.ai.azure.com", ".cognitiveservices.azure.com")
private val supportedPaths = setOf("", "/", "/openai", "/openai/", "/openai/v1", "/openai/v1/", "/openai/v1/live", "/openai/v1/live/", "/openai/v1/live/sessions", "/openai/v1/live/sessions/")

fun FoundryConnection.validationError(): String? {
    if (!speechModel.supportedBy(provider)) return "MAI 轉錄僅支援 Microsoft Foundry。"
    if (sourceTranscriptionModel.isNotBlank() && !Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}").matches(sourceTranscriptionModel))
        return "請填入有效的原文字幕模型或部署名稱。"
    if (provider == ModelProvider.OPENAI) {
        if (endpoint.trim().trimEnd('/') != "https://api.openai.com")
            return "OpenAI 連線僅接受 https://api.openai.com。"
        if (!Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}").matches(deployment.trim()))
            return "請填入有效的 OpenAI 語音模型名稱。"
        return null
    }
    val match = endpointPattern.matchEntire(endpoint.trim())
        ?: return "請填入 HTTPS Foundry 資源端點；不可包含帳密、查詢參數、片段或非 443 連接埠。"
    val host = match.groupValues[1].lowercase()
    if (host.length > 253 || !host.split('.').all { hostnameLabel.matches(it) } || endpointSuffixes.none { host.endsWith(it) })
        return "請使用 Azure 資源網域：openai.azure.com、services.ai.azure.com 或 cognitiveservices.azure.com。"
    if (speechModel == SpeechModel.MAI_TRANSCRIBE && !host.endsWith(".services.ai.azure.com"))
        return "MAI 轉錄需使用此 Foundry 資源的 services.ai.azure.com HTTPS 根網址。"
    if (match.groupValues[2] !in supportedPaths)
        return "端點請使用資源根網址，或 /openai/v1/live/sessions；不接受其他 API 路徑。"
    if (!Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}").matches(deployment.trim()))
        return "部署名稱須為 1–128 個英數字、點、底線或連字號。"
    return null
}

fun FoundryConnection.webSocketUrl(): String {
    require(validationError() == null) { validationError().orEmpty() }
    val base = apiBaseUrl().replace("https://", "wss://")
    return when (speechModel) {
        SpeechModel.GPT_LIVE -> base + "live/sessions"
        SpeechModel.REALTIME_TRANSLATE -> base + "realtime/translations?model=${deployment.trim()}"
        SpeechModel.LIVE_TRANSCRIBE -> base + "realtime?intent=transcription"
        SpeechModel.MAI_TRANSCRIBE -> "wss://${resourceHost()}/mai/v1/realtime?intent=transcription"
    }
}

private fun FoundryConnection.resourceHost(): String = if (provider == ModelProvider.OPENAI) "api.openai.com"
else requireNotNull(endpointPattern.matchEntire(endpoint.trim())).groupValues[1].lowercase()

fun FoundryConnection.apiBaseUrl(): String {
    require(validationError() == null) { validationError().orEmpty() }
    return "https://${resourceHost()}/" + if (provider == ModelProvider.OPENAI) "v1/" else "openai/v1/"
}

/** Stable, backwards-compatible credential binding; changing models never changes the key's destination. */
fun FoundryConnection.credentialEndpoint(): String = apiBaseUrl().replace("https://", "wss://") + "live/sessions"
fun FoundryConnection.chatCompletionsUrl(): String = apiBaseUrl() + "chat/completions"
