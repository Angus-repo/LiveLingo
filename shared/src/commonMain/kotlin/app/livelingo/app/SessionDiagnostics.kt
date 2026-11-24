package app.livelingo.app

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Called on the controller dispatcher at lifecycle boundaries, never for individual audio frames. */
@OptIn(ExperimentalTime::class)
internal class SessionDiagnostics(private val services: PlatformServices) {
    private val key = "diagnostic_events_v1"
    private val lines = ArrayDeque<String>()
    private var persistenceFailed = false

    init {
        try {
            services.storage.read(key)?.lineSequence()?.filter { it.isNotBlank() }
                ?.forEach { append(it.take(1024)) }
        } catch (_: Exception) { persistenceFailed = true }
    }

    private fun append(line: String) {
        if (lines.size == 160) lines.removeFirst()
        lines.addLast(line)
    }

    /** fields must consist only of application constants, enum values, numbers or safeFields(). */
    fun event(name: String, fields: String) {
        val time = Instant.fromEpochMilliseconds(services.currentTimeMillis())
        val network = runCatching { services.diagnosticNetwork() }.getOrDefault("unknown")
        append("$time $name ${fields.take(900)} network=$network")
        try {
            services.storage.write(key, lines.joinToString("\n"))
            persistenceFailed = false
        } catch (_: Exception) { persistenceFailed = true }
    }

    fun report(): String = buildString {
        appendLine("LiveLingo $APP_VERSION · 翻譯診斷紀錄 v1")
        appendLine("platform=${services.diagnosticPlatform} exported_utc=${Instant.fromEpochMilliseconds(services.currentTimeMillis())}")
        appendLine("事件依發生順序排列，時間為 UTC（台灣時間為 UTC+8）。")
        if (persistenceFailed) appendLine("本機診斷檔讀寫失敗，部分較早的事件可能缺漏。")
        if (lines.isEmpty()) appendLine("尚無事件。請使用此版本開始翻譯或補回待處理錄音後，再下載紀錄。")
        else lines.forEach { appendLine(it) }
    }

    fun safeFields(error: Throwable?, diagnostic: FoundryDiagnostic?, secret: String): String = buildList {
        var cause = error
        val seen = mutableListOf<Throwable>()
        while (cause != null && seen.size < 4 && seen.none { it === cause }) {
            val current = cause
            seen += current
            val kind = current::class.simpleName?.takeIf { it.matches(Regex("[A-Za-z0-9_]{1,80}")) } ?: "Unknown"
            val site = services.diagnosticLocation(current)
            add("cause${seen.size}=$kind${site?.let { "@$it" }.orEmpty()}")
            cause = current.cause
        }
        diagnostic?.let { d ->
            val stage = when (d.stage) {
                FoundryStages.HANDSHAKE -> "websocket_handshake"
                FoundryStages.START -> "session_start"
                FoundryStages.STREAM -> "stream"
                FoundryStages.STOP -> "drain"
                FoundryStages.COMPLETE -> "complete"
                "文字翻譯" -> "text_translation"
                "連線測試" -> "probe"
                else -> "other"
            }
            add("service_stage=$stage")
            val code = d.code.takeIf { it in categories || it.matches(Regex("HTTP_[1-5][0-9]{2}")) } ?: "OTHER"
            add("code=$code")
            d.httpStatus?.takeIf { it in 100..599 }?.let { add("http=$it") }
            safeWebSocketCloseCode(d.webSocketCloseCode)?.let { add("ws_close=$it") }
            safeSessionCloseReason(d.sessionCloseReason)?.let { add("close_reason=$it") }
            safeProviderCode(d.providerCode)?.let { add("provider_code=$it") }
            safeProviderType(d.providerType)?.let { add("provider_type=$it") }
            safeProviderParam(d.providerParam)?.let { add("provider_param=$it") }
            safeRequestId(d.requestId, secret)?.let { add("request_id=$it") }
        }
    }.joinToString(" ")

    private companion object {
        val categories = setOf("OK", "UNKNOWN", "PROTOCOL", "CONTENT_FILTER", "DISCONNECTED_AFTER_FILTER",
            "PROBE_TIMEOUT", "TIMEOUT", "PROVIDER_ERROR", "CONNECTION_CLOSED", "SESSION_EXPIRED",
            "SESSION_CLOSED", "SESSION_FAILURE", "TARGET_LANGUAGE_MISMATCH", "DNS", "TLS", "CONNECT",
            "NETWORK_UNKNOWN", "CLIENT_CONFIGURATION")
    }
}
