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
)

private val endpointPattern = Regex("^https://([A-Za-z0-9.-]+)(?::443)?(/[^?#]*)?$", RegexOption.IGNORE_CASE)
private val hostnameLabel = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
private val endpointSuffixes = listOf(".openai.azure.com", ".services.ai.azure.com", ".cognitiveservices.azure.com")
private val supportedPaths = setOf("", "/", "/openai", "/openai/", "/openai/v1", "/openai/v1/", "/openai/v1/live", "/openai/v1/live/", "/openai/v1/live/sessions", "/openai/v1/live/sessions/")

fun FoundryConnection.validationError(): String? {
    if (provider == ModelProvider.OPENAI) {
        if (endpoint.trim().trimEnd('/') != "https://api.openai.com")
            return "OpenAI 連線僅接受 https://api.openai.com。"
        if (!Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}").matches(deployment.trim()))
            return "請填入有效的 OpenAI GPT-Live 模型名稱。"
        return null
    }
    val match = endpointPattern.matchEntire(endpoint.trim())
        ?: return "請填入 HTTPS Foundry 資源端點；不可包含帳密、查詢參數、片段或非 443 連接埠。"
    val host = match.groupValues[1].lowercase()
    if (host.length > 253 || !host.split('.').all { hostnameLabel.matches(it) } || endpointSuffixes.none { host.endsWith(it) })
        return "請使用 Azure 資源網域：openai.azure.com、services.ai.azure.com 或 cognitiveservices.azure.com。"
    if (match.groupValues[2] !in supportedPaths)
        return "端點請使用資源根網址，或 /openai/v1/live/sessions；不接受其他 API 路徑。"
    if (!Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}").matches(deployment.trim()))
        return "部署名稱須為 1–128 個英數字、點、底線或連字號。"
    return null
}

fun FoundryConnection.webSocketUrl(): String {
    require(validationError() == null) { validationError().orEmpty() }
    if (provider == ModelProvider.OPENAI) return "wss://api.openai.com/v1/live/sessions"
    val host = requireNotNull(endpointPattern.matchEntire(endpoint.trim())).groupValues[1].lowercase()
    return "wss://$host/openai/v1/live/sessions"
}

fun FoundryConnection.chatCompletionsUrl(): String =
    webSocketUrl().replace("wss://", "https://").removeSuffix("live/sessions") + "chat/completions"
