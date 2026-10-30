package app.livelingo.app

import app.livelingo.core.WireJson
import kotlinx.serialization.json.*

data class FoundryDiagnostic(
    val stage: String,
    val code: String,
    val httpStatus: Int? = null,
    val requestId: String? = null,
    val providerCode: String? = null,
)

data class ConnectionProbeResult(val message: String, val diagnostic: FoundryDiagnostic)

/** Native HTTP adapters expose only these fields, never an HTTP body, headers or credential. */
interface FoundryHttpFailure {
    val statusCode: Int
    val providerCode: String?
    val requestId: String?
}

data class SafeFoundryErrorFields(val providerCode: String? = null, val requestId: String? = null)

private val knownProviderCodes = setOf(
    "invalid_api_key", "authentication_error", "unauthorized", "Unauthorized",
    "permission_denied", "access_denied", "Forbidden", "DeploymentNotFound", "ResourceNotFound",
    "model_not_found", "deployment_not_found", "rate_limit_exceeded", "quota_exceeded", "insufficient_quota",
    "TooManyRequests", "invalid_request_error", "invalid_request", "BadRequest", "unsupported_model",
    "model_not_supported", "OperationNotSupported", "content_filter", "server_error", "internal_error",
    "service_unavailable", "invalid_audio", "context_length_exceeded",
)

fun safeProviderCode(raw: String?): String? = raw?.let { value -> knownProviderCodes.firstOrNull { it.equals(value, ignoreCase = true) } }

fun safeRequestId(raw: String?, secret: String? = null): String? {
    val value = raw?.trim() ?: return null
    if (!secret.isNullOrEmpty() && value.contains(secret, ignoreCase = true)) return null
    val valid = Regex("[0-9a-fA-F]{32}|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|req_[A-Za-z0-9_-]{8,64}")
    return value.takeIf(valid::matches)
}

/** The caller bounds native peekBody to 16 KiB; this also rejects oversized or non-JSON input. */
fun safeJsonErrorFields(body: String, secret: String? = null): SafeFoundryErrorFields {
    if (body.length > 16_384) return SafeFoundryErrorFields()
    val root = runCatching { WireJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: return SafeFoundryErrorFields()
    val error = (root["error"] as? JsonObject) ?: root
    fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    return SafeFoundryErrorFields(
        providerCode = safeProviderCode(error.string("code")),
        requestId = safeRequestId(root.string("request_id") ?: error.string("request_id"), secret),
    )
}

internal object FoundryStages {
    const val HANDSHAKE = "WebSocket 握手"
    const val START = "啟動 GPT-Live"
    const val STREAM = "串流翻譯"
    const val STOP = "結束工作階段"
    const val COMPLETE = "完成"
}

internal fun httpTranslationFailure(status: Int, stage: String, providerCode: String? = null, requestId: String? = null): TranslationFailure {
    val message = when (status) {
        400 -> "Foundry 拒絕 WebSocket 請求（HTTP 400）。請確認資源端點與 GPT-Live 支援狀態。"
        401 -> "Foundry 認證失敗（HTTP 401）。請確認 API key 屬於這個資源且尚未失效。"
        403 -> "Foundry 拒絕存取（HTTP 403）。請確認資源網路限制、模型使用權限與區域支援。"
        404 -> "找不到 Foundry 介面（HTTP 404）。請確認資源端點、GPT-Live 支援狀態與部署名稱。"
        408, 504 -> "Foundry 請求逾時（HTTP $status），請稍後重試。"
        409 -> "Foundry 部署目前無法使用（HTTP 409），請確認部署狀態後重試。"
        429 -> "Foundry 使用量或速率達上限（HTTP 429），請檢查配額或稍後重試。"
        in 300..399 -> "Foundry 回傳重新導向（HTTP $status）；為保護 API key，App 未跟隨重新導向，請檢查端點。"
        in 500..599 -> "Foundry 服務暫時無法使用（HTTP $status），請稍後重試。"
        else -> "Foundry 未接受 WebSocket 連線（HTTP $status），請檢查資源端點與服務狀態。"
    }
    return TranslationFailure(message, FoundryDiagnostic(stage, "HTTP_$status", status, safeRequestId(requestId), safeProviderCode(providerCode)))
}

internal fun classifyFoundryFailure(error: Throwable, stage: String): TranslationFailure {
    val chain = mutableListOf<Throwable>()
    var current: Throwable? = error
    while (current != null && chain.size < 12 && chain.none { it === current }) {
        chain += current
        current = current.cause
    }
    chain.filterIsInstance<FoundryHttpFailure>().firstOrNull()?.let {
        return httpTranslationFailure(it.statusCode, stage, it.providerCode, it.requestId)
    }
    // Only extract a numeric status from the two documented adapter-generated handshake patterns.
    chain.firstNotNullOfOrNull { cause ->
        val name = cause::class.simpleName.orEmpty()
        if (name !in setOf("WebSocketException", "ProtocolException")) null else {
            val text = cause.message.orEmpty()
            (Regex("^Handshake exception, expected status code 101 but was ([1-5][0-9]{2})(?:\\s.*)?$").find(text)
                ?: Regex("^Expected HTTP 101 response but was '([1-5][0-9]{2})[^']*'$" ).find(text))
                ?.groupValues?.get(1)?.toIntOrNull()
        }
    }?.let { return httpTranslationFailure(it, stage) }
    val names = chain.map { it::class.simpleName.orEmpty().lowercase() }
    val (code, message) = when {
        names.any { "unknownhost" in it || "unresolvedaddress" in it } -> "DNS" to "無法解析 Foundry 網址（DNS）。請檢查網路、DNS 與資源端點拼字。"
        names.any { "ssl" in it || "certificate" in it || "certpath" in it || "tls" in it } -> "TLS" to "Foundry TLS 憑證驗證失敗。請檢查裝置日期、網路代理與憑證；App 不會略過安全驗證。"
        names.any { "timeout" in it } -> "TIMEOUT" to "Foundry 連線等待逾時，請檢查網路與服務狀態。"
        names.any { "connectexception" in it || "noroutetohost" in it } -> "CONNECT" to "無法建立 Foundry 網路連線，請檢查網路、防火牆與資源端點。"
        chain.any { it::class.simpleName == "WebSocketException" && it.message == "Max frame size switch is not supported in OkHttp engine." } ->
            "CLIENT_CONFIGURATION" to "App 的 WebSocket 元件設定不相容，請更新 App 後重試。"
        else -> "NETWORK_UNKNOWN" to "Foundry 連線失敗，尚未取得 HTTP 狀態。請檢查網路與資源端點，並提供診斷報告。"
    }
    return TranslationFailure(message, FoundryDiagnostic(stage, code))
}
