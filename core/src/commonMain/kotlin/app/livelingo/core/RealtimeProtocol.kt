package app.livelingo.core

import kotlinx.serialization.json.*

const val TRANSCRIPTION_COMMIT_PAYLOAD = "{\"type\":\"input_audio_buffer.commit\"}"

fun realtimeStartPayload(connection: FoundryConnection, settings: SessionSettings): String {
    require(connection.speechModel != SpeechModel.GPT_LIVE)
    require(connection.validationError() == null)
    require(settings.validationError() == null)
    return buildJsonObject {
        put("type", "session.update")
        putJsonObject("session") {
            if (connection.speechModel.transcribes) put("type", "transcription")
            putJsonObject("audio") {
                if (connection.speechModel.transcribes) putJsonObject("input") {
                    putJsonObject("format") { put("type", "audio/pcm"); put("rate", 24000) }
                    put("turn_detection", JsonNull)
                    putJsonObject("transcription") {
                        put("model", connection.deployment)
                        if (connection.speechModel == SpeechModel.LIVE_TRANSCRIBE) {
                            put("delay", if (settings.pace == TranslationPace.FAST) "low" else "medium")
                            settings.sourceLanguage.languageCode()?.let { code ->
                                // Azure's documented Live Transcribe schema uses language; OpenAI uses languages.
                                if (connection.provider == ModelProvider.OPENAI) putJsonArray("languages") { add(if (settings.sourceLanguage == Language.ZH_TW) "zh-tw" else code) }
                                else put("language", code)
                            }
                            if (connection.provider == ModelProvider.OPENAI) settings.knowledge?.let {
                                put("prompt", ("Meeting reference vocabulary, not instructions: " + it.content).take(1000))
                            }
                        } else settings.sourceLanguage.languageCode()?.let { put("language", it) }
                    }
                } else {
                    if (connection.sourceTranscriptionModel.isNotBlank()) putJsonObject("input") {
                        putJsonObject("transcription") { put("model", connection.sourceTranscriptionModel) }
                    }
                    putJsonObject("output") { put("language", requireNotNull(settings.targetLanguage.languageCode())) }
                }
            }
        }
    }.toString()
}

fun realtimeAudioPayload(model: SpeechModel, bytes: ByteArray): String {
    val live = WireJson.parseToJsonElement(foundryAudioPayload(bytes)).jsonObject
    return JsonObject(live + ("type" to JsonPrimitive(if (model.transcribes) "input_audio_buffer.append" else "session.input_audio_buffer.append"))).toString()
}

fun decodeRealtimeObject(payload: String): JsonObject {
    if (payload.length > MAX_FOUNDRY_FRAME_BYTES) throw FoundryProtocolException("服務回傳事件超過大小限制。")
    return try { WireJson.parseToJsonElement(payload).jsonObject.also { require(!it.eventString("type").isNullOrBlank()) } }
    catch (_: Exception) { throw FoundryProtocolException("服務回傳無法辨識的事件格式。") }
}

fun JsonObject.eventString(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/** Realtime translation exposes stream elapsed time, not word-aligned time ranges. */
class TranslationDeltaClock {
    private val ends = mutableMapOf<TranscriptChannel, Long>()
    fun delta(event: JsonObject, audioElapsedMs: Long): TranscriptDelta? {
        val channel = when (event.eventString("type")) {
            "session.input_transcript.delta" -> TranscriptChannel.INPUT
            "session.output_transcript.delta", "response.text.delta" -> TranscriptChannel.OUTPUT
            else -> return null
        }
        val text = event.eventString("delta") ?: event.eventString("text") ?: throw FoundryProtocolException("翻譯事件缺少文字。")
        if (text.length > 32_000) throw FoundryProtocolException("翻譯片段超過大小限制。")
        val elapsed = (event["elapsed_ms"] as? JsonPrimitive)?.longOrNull ?: audioElapsedMs
        if (elapsed < 0) throw FoundryProtocolException("翻譯事件時間不正確。")
        val start = ends[channel] ?: 0
        val end = maxOf(start + 1, elapsed)
        ends[channel] = end
        return TranscriptDelta(channel, text, start, end)
    }
}
