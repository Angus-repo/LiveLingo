package app.livelingo.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

val WireJson: Json = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = false
}

@Serializable
data class TranscriptDelta(val channel: TranscriptChannel, val text: String, val startMs: Long, val endMs: Long)

sealed interface FoundryEvent {
    data class Ready(val sessionId: String) : FoundryEvent
    data class Transcript(val delta: TranscriptDelta) : FoundryEvent
    data class Closed(val reason: String?) : FoundryEvent
    data object Ignored : FoundryEvent
}

/** Messages are curated locally; provider message bodies are never exposed. */
class FoundryProtocolException(message: String) : Exception(message)
const val MAX_FOUNDRY_FRAME_BYTES = 1_048_576
const val FOUNDRY_CLOSE_PAYLOAD = "{\"type\":\"session.close\"}"

fun foundryStartPayload(connection: FoundryConnection, settings: SessionSettings): String {
    require(connection.validationError() == null) { connection.validationError().orEmpty() }
    return buildJsonObject {
        put("type", "session.start")
        putJsonObject("session") {
            put("model", connection.deployment.trim())
            put("instructions", translationInstructions(settings))
            putJsonObject("audio") { putJsonObject("output") { put("voice", "marin") } }
            putJsonObject("delegation") { put("type", "client") }
        }
    }.toString()
}

@OptIn(ExperimentalEncodingApi::class)
fun foundryAudioPayload(pcm16: ByteArray): String {
    if (pcm16.isEmpty() || pcm16.size > 48_000 || pcm16.size % 2 != 0)
        throw FoundryProtocolException("音訊須為 24 kHz 單聲道 PCM16，且每段最多一秒。")
    if (pcm16.size >= 12 && pcm16.copyOfRange(0, 4).decodeToString() == "RIFF" && pcm16.copyOfRange(8, 12).decodeToString() == "WAVE")
        throw FoundryProtocolException("音訊不可包含 WAV 檔頭。")
    return buildJsonObject { put("type", "session.input_audio.append"); put("audio", Base64.encode(pcm16)) }.toString()
}

fun foundryProviderError(code: String?): String = when (code) {
    "rate_limit_exceeded", "quota_exceeded", "insufficient_quota" -> "Foundry 使用量或速率達上限，請稍後重試並檢查配額。"
    "invalid_api_key", "unauthorized", "authentication_error" -> "Foundry 認證失敗，請檢查 API key 與資源端點。"
    "model_not_found", "deployment_not_found", "DeploymentNotFound" -> "找不到 GPT-Live 部署，請檢查部署名稱、資源端點與區域。"
    else -> "Foundry 無法完成翻譯，請檢查部署、權限或服務狀態。"
}

fun foundryCloseError(reason: String?, requestedByClient: Boolean): String? = when {
    reason == "expired" -> "Foundry 工作階段已到期，已保留收到的逐字稿；請重新開始翻譯。"
    reason == "content" -> "Foundry 因內容處理限制結束翻譯，最後字幕可能不完整。"
    reason == "connection_lost" || reason == "remote_hangup" -> "Foundry 連線已中斷，最後字幕可能不完整。"
    requestedByClient && (reason == null || reason == "close_requested") -> null
    else -> "Foundry 提前結束翻譯，已保留收到的逐字稿。"
}

fun parseFoundryEvent(text: String): FoundryEvent {
    if (text.length > MAX_FOUNDRY_FRAME_BYTES) throw FoundryProtocolException("Foundry 回傳的事件超過大小限制。")
    val event = try { WireJson.parseToJsonElement(text).jsonObject } catch (_: Exception) {
        throw FoundryProtocolException("Foundry 回傳無法辨識的事件格式。")
    }
    fun str(key: String): String? = (event[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    return when (val type = str("type") ?: throw FoundryProtocolException("Foundry 事件缺少類型。")) {
        "session.started" -> {
            val id = ((event["session"] as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            if (id.isNullOrBlank() || id.length > 512) throw FoundryProtocolException("Foundry 未回傳有效的工作階段識別碼。")
            FoundryEvent.Ready(id)
        }
        "session.input_transcript.delta", "session.output_transcript.delta" -> {
            val delta = str("delta") ?: throw FoundryProtocolException("Foundry 字幕片段缺少文字。")
            val start = (event["start_ms"] as? JsonPrimitive)?.longOrNull
            val end = (event["end_ms"] as? JsonPrimitive)?.longOrNull
            if (start == null || end == null || start < 0 || end <= start || delta.length > 32_000)
                throw FoundryProtocolException("Foundry 字幕時間戳或片段長度不正確。")
            FoundryEvent.Transcript(TranscriptDelta(
                if (type == "session.input_transcript.delta") TranscriptChannel.INPUT else TranscriptChannel.OUTPUT,
                delta, start, end,
            ))
        }
        "session.closed" -> FoundryEvent.Closed(str("reason"))
        "error" -> {
            val code = ((event["error"] as? JsonObject)?.get("code") as? JsonPrimitive)?.contentOrNull
            throw FoundryProtocolException(foundryProviderError(code))
        }
        // Output audio is generated/billed but this app only displays subtitles.
        else -> FoundryEvent.Ignored
    }
}
