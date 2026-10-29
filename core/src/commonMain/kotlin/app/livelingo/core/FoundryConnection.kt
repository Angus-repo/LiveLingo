package app.livelingo.core

import kotlinx.serialization.Serializable

/** Contains no credentials. The API key lives separately in platform secure storage. */
@Serializable
data class FoundryConnection(val endpoint: String, val deployment: String = "gpt-live-1")

private val endpointPattern = Regex("^https://([A-Za-z0-9.-]+)(?::443)?(/[^?#]*)?$", RegexOption.IGNORE_CASE)
private val hostnameLabel = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
private val endpointSuffixes = listOf(".openai.azure.com", ".services.ai.azure.com", ".cognitiveservices.azure.com")
private val supportedPaths = setOf("", "/", "/openai", "/openai/", "/openai/v1", "/openai/v1/", "/openai/v1/live", "/openai/v1/live/", "/openai/v1/live/sessions", "/openai/v1/live/sessions/")

fun FoundryConnection.validationError(): String? {
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
    val host = requireNotNull(endpointPattern.matchEntire(endpoint.trim())).groupValues[1].lowercase()
    return "wss://$host/openai/v1/live/sessions"
}
