package app.livelingo.app

import app.livelingo.core.*
import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.serialization.json.*

class ModelTranslationTransport(clientFactory: () -> HttpClient) : TranslationTransport {
    private val live = KtorFoundryTransport(clientFactory())
    private val realtime = RealtimeTransport(KtorFoundrySocketFactory(clientFactory()), HttpTextTranslator(clientFactory()))
    override fun setUsageListener(listener: suspend (UsageSample) -> Unit) {
        live.setUsageListener(listener); realtime.setUsageListener(listener)
    }
    private fun transport(connection: FoundryConnection): TranslationTransport = if (connection.speechModel == SpeechModel.GPT_LIVE) live else realtime
    override suspend fun run(connection: FoundryConnection, apiKey: String, settings: SessionSettings, audio: ReceiveChannel<ByteArray>,
        onReady: suspend () -> Unit, onTranscript: suspend (TranscriptDelta) -> Unit, onNotice: suspend (TranslationNotice) -> Unit) =
        transport(connection).run(connection, apiKey, settings, audio, onReady, onTranscript, onNotice)
    override suspend fun probe(connection: FoundryConnection, apiKey: String) = transport(connection).probe(connection, apiKey)
    override fun close() { live.close(); realtime.close() }
}

/** Commit order, not completion arrival order, defines the source timeline. */
internal class CommittedTranscriptBuffer {
    private data class Turn(val start: Long, val end: Long, var id: String? = null, var text: String? = null, var acknowledged: Boolean = false)
    private val turns = ArrayDeque<Turn>()
    private val completedIds = ArrayDeque<String>()
    private val partials = linkedMapOf<String, String>()
    private var committedEnd = 0L
    val empty: Boolean get() = turns.isEmpty()
    fun commit(end: Long) {
        require(end > committedEnd)
        if (turns.size >= 20) throw TranslationFailure("轉錄回應落後過多，已停止收音並保留收到的字幕。")
        turns.addLast(Turn(committedEnd, end)); committedEnd = end
    }
    fun acknowledge(id: String?) {
        val turn = turns.firstOrNull { !it.acknowledged } ?: return
        turn.id = id; turn.acknowledged = true
    }
    fun partial(event: JsonObject) {
        val text = event.eventString("delta") ?: return
        val id = event.eventString("item_id") ?: "current"
        if (id in completedIds) return
        if (partials.size >= 20 && id !in partials) throw TranslationFailure("轉錄片段數量超過限制。")
        val accumulated = partials[id].orEmpty() + text
        if (accumulated.length > 32_000) throw TranslationFailure("轉錄片段長度超過限制。")
        partials[id] = accumulated
    }
    fun complete(event: JsonObject): List<TranscriptDelta> {
        val id = event.eventString("item_id")
        if (id != null && id in completedIds) return emptyList()
        val turn = if (id == null) turns.firstOrNull { it.text == null }
            else turns.firstOrNull { it.id == id } ?: turns.firstOrNull { it.id == null && it.text == null }
        if (turn == null) throw TranslationFailure("收到無法對應音訊的轉錄完成事件。")
        val text = event.eventString("transcript") ?: throw TranslationFailure("轉錄完成事件缺少全文。")
        if (text.length > 32_000) throw TranslationFailure("轉錄全文超過大小限制。")
        if (turn.text != null) return emptyList()
        turn.text = text
        if (id != null) { turn.id = id; completedIds.addLast(id); if (completedIds.size > 128) completedIds.removeFirst(); partials.remove(id) }
        else {
            // MAI can put item_id on deltas but omit it on both committed and completed.
            // Its completed text is the concatenation of those finalized deltas.
            val partialKey = turn.id ?: partials.entries.firstOrNull { it.value == text }?.key ?: "current"
            partials.remove(partialKey)
        }
        val result = mutableListOf<TranscriptDelta>()
        while (turns.firstOrNull()?.text != null) {
            val first = turns.removeFirst()
            if (first.text!!.isNotBlank()) result += TranscriptDelta(TranscriptChannel.INPUT, first.text!!, first.start, first.end)
        }
        return result
    }
    fun unfinished(audioMs: Long): List<TranscriptDelta> {
        val result = mutableListOf<TranscriptDelta>()
        for (turn in turns) {
            val text = turn.text ?: turn.id?.let { partials.remove(it) } ?: partials.remove("current")
            if (!text.isNullOrBlank()) result += TranscriptDelta(TranscriptChannel.INPUT, text, turn.start, turn.end)
        }
        for (text in partials.values) if (text.isNotBlank()) result += TranscriptDelta(TranscriptChannel.INPUT, text, committedEnd, maxOf(committedEnd + 1, audioMs))
        partials.clear(); turns.clear()
        return result
    }
}

internal fun separatedTranscript(previous: String, next: String): String {
    val a = previous.lastOrNull(); val b = next.firstOrNull()
    return if (a != null && b != null && a.code < 128 && b.code < 128 && a.isLetterOrDigit() && b.isLetterOrDigit()) " $next" else next
}

internal class RealtimeTransport(
    private val sockets: FoundrySocketFactory,
    private val translator: TextTranslator,
    private val startupTimeoutMillis: Long = 30_000,
    private val drainTimeoutMillis: Long = 30_000,
) : TranslationTransport {
    private var usageListener: suspend (UsageSample) -> Unit = {}
    override fun setUsageListener(listener: suspend (UsageSample) -> Unit) {
        usageListener = listener; translator.setUsageListener(listener)
    }
    override suspend fun probe(connection: FoundryConnection, apiKey: String): ConnectionProbeResult {
        val audio = Channel<ByteArray>(0).also { it.close() }
        run(connection, apiKey, SessionSettings(), audio, {}, {}, {})
        return ConnectionProbeResult("${connection.provider.label} ${connection.speechModel.label} 語音連線測試成功；未收音，未測試文字翻譯。", FoundryDiagnostic(FoundryStages.COMPLETE, "OK", 101))
    }

    override suspend fun run(connection: FoundryConnection, apiKey: String, settings: SessionSettings, audio: ReceiveChannel<ByteArray>,
        onReady: suspend () -> Unit, onTranscript: suspend (TranscriptDelta) -> Unit, onNotice: suspend (TranslationNotice) -> Unit) {
        connection.validationError()?.let { throw TranslationFailure(it) }
        settings.validationError()?.let { throw TranslationFailure(it) }
        val key = apiKey.trim()
        if (key.isEmpty() || key.length > 4096 || key.any { it.isWhitespace() || it.code !in 33..126 }) throw TranslationFailure("API key 格式不正確。")
        val transcribes = connection.speechModel.transcribes
        var socket: FoundrySocket? = null
        var stage = FoundryStages.HANDSHAKE
        val reportUsage = usageListener
        var ready = false
        var checkpointBytes = 0L
        var audioBytes = 0L
        suspend fun checkpoint() {
            if (!ready) return
            val seconds = audioBytes / 48_000.0
            reportUsage(UsageSample("voice", UsageKind.VOICE, seconds = seconds, estimated = true))
            if (connection.speechModel == SpeechModel.REALTIME_TRANSLATE && connection.sourceTranscriptionModel.isNotBlank())
                reportUsage(UsageSample("source", UsageKind.SOURCE, seconds = seconds, estimated = true))
        }
        var stopping = false
        var pendingFilter = false
        var previousInput = ""
        val pending = CommittedTranscriptBuffer()
        suspend fun emitInput(delta: TranscriptDelta) {
            val adjusted = delta.copy(text = separatedTranscript(previousInput, delta.text))
            onTranscript(adjusted); previousInput = adjusted.text.takeLast(1)
        }
        fun checked(payload: String): JsonObject {
            val event = decodeRealtimeObject(payload)
            if (event.eventString("type") in setOf("error", "conversation.item.input_audio_transcription.failed")) {
                val fields = safeJsonErrorFields(payload, key)
                val code = fields.providerCode
                if (isContentFilterCode(code) && !transcribes && stage != FoundryStages.START) return event
                throw TranslationFailure(if (isContentFilterCode(code)) "服務因內容安全篩選中止處理，已保留收到的字幕。" else "語音服務拒絕請求，請檢查模型類型、部署、權限與配額。",
                    FoundryDiagnostic(stage, if (isContentFilterCode(code)) "CONTENT_FILTER" else "PROVIDER_ERROR", providerCode = code, requestId = fields.requestId))
            }
            return event
        }
        try {
            coroutineScope {
                val active = withTimeout(startupTimeoutMillis) { sockets.open(connection.webSocketUrl(), key).also { socket = it } }
                stage = FoundryStages.START
                withTimeout(startupTimeoutMillis) {
                    active.send(realtimeStartPayload(connection, settings))
                    while (true) {
                        val event = checked(active.receive() ?: throw TranslationFailure("服務在準備完成前中斷。"))
                        when (event.eventString("type")) {
                            "session.updated", "transcription_session.updated" -> {
                                val id = (event["session"] as? JsonObject)?.eventString("id")
                                if (id.isNullOrBlank()) throw TranslationFailure("服務未回傳有效的工作階段。")
                                break
                            }
                            "session.created", "transcription_session.created" -> Unit
                            "session.closed" -> throw TranslationFailure("服務在準備完成前結束工作階段。")
                        }
                    }
                }
                stage = FoundryStages.STREAM
                ready = true
                checkpoint()
                onReady()
                val done = CompletableDeferred<Unit>()
                val translations = Channel<TranscriptDelta>(12)
                val worker = if (transcribes) launch {
                    var context = ""
                    var previousOutput = ""
                    for (source in translations) {
                        if (connection.textDeployment.isBlank()) throw TranslationFailure("轉錄方案需要設定文字翻譯模型。")
                        val output = translator.translate(connection, key, settings, source.text, context)
                        val separated = separatedTranscript(previousOutput, output)
                        onTranscript(source.copy(channel = TranscriptChannel.OUTPUT, text = separated))
                        previousOutput = separated.takeLast(1)
                        context = (context + "\nOriginal: " + source.text + "\nTranslation: " + output).takeLast(2500)
                    }
                } else null
                val clock = TranslationDeltaClock()
                val receiver = launch {
                    while (true) {
                        val raw = active.receive() ?: throw TranslationFailure(
                            if (pendingFilter) "服務在內容篩選通知後斷線，已保留字幕。" else "語音連線已中斷，已保留字幕；最後片段可能不完整。",
                            FoundryDiagnostic(stage, if (pendingFilter) "DISCONNECTED_AFTER_FILTER" else "CONNECTION_CLOSED"))
                        val event = checked(raw)
                        when (event.eventString("type")) {
                            "error" -> {
                                pendingFilter = true
                                onNotice(TranslationNotice("服務已阻擋部分內容，正在等待後續字幕。", FoundryDiagnostic(stage, "CONTENT_FILTER", providerCode = "content_filter")))
                            }
                            "session.closed" -> {
                                val reason = event.eventString("reason")
                                foundryCloseError(reason, stopping)?.let { throw TranslationFailure(it,
                                    FoundryDiagnostic(stage, if (reason == "content") "CONTENT_FILTER" else "SESSION_CLOSED", sessionCloseReason = safeSessionCloseReason(reason))) }
                                if (transcribes && !pending.empty) throw TranslationFailure("轉錄未完成即結束，已保留收到的原文。")
                                done.complete(Unit); return@launch
                            }
                            "input_audio_buffer.committed" -> pending.acknowledge(event.eventString("item_id"))
                            "conversation.item.input_audio_transcription.delta" -> if (transcribes) pending.partial(event)
                            "conversation.item.input_audio_transcription.completed" -> if (transcribes) {
                                for (source in pending.complete(event)) {
                                    emitInput(source)
                                    if (!translations.trySend(source).isSuccess) throw TranslationFailure("文字翻譯速度落後，已停止收音並保留原文；請選擇較快的文字模型。")
                                }
                                if (stopping && pending.empty) { done.complete(Unit); return@launch }
                            }
                            else -> if (!transcribes) clock.delta(event, audioBytes / 48)?.let { pendingFilter = false; onTranscript(it) }
                        }
                    }
                }
                val sender = launch {
                    var uncommittedBytes = 0L
                    suspend fun commit() {
                        if (uncommittedBytes == 0L) return
                        // Avoid committing less than 100 ms at shutdown.
                        if (uncommittedBytes < 4800) {
                            val padding = ByteArray((4800 - uncommittedBytes).toInt())
                            active.send(realtimeAudioPayload(connection.speechModel, padding)); audioBytes += padding.size
                        }
                        pending.commit(audioBytes / 48)
                        active.send(TRANSCRIPTION_COMMIT_PAYLOAD)
                        uncommittedBytes = 0
                    }
                    for (bytes in audio) {
                        active.send(realtimeAudioPayload(connection.speechModel, bytes))
                        audioBytes += bytes.size; uncommittedBytes += bytes.size
                        if (audioBytes - checkpointBytes >= 240_000) { checkpointBytes = audioBytes; checkpoint() }
                        // Bounded three-second commits, including silence. No server VAD is assumed.
                        if (transcribes && uncommittedBytes >= 144_000) commit()
                    }
                    stage = FoundryStages.STOP
                    if (transcribes) {
                        commit(); stopping = true
                        if (pending.empty) done.complete(Unit)
                    } else { stopping = true; active.send(FOUNDRY_CLOSE_PAYLOAD) }
                }
                sender.join()
                checkpoint()
                withTimeout(drainTimeoutMillis) { done.await() }
                receiver.cancelAndJoin()
                translations.close()
                withTimeout(60_000) { worker?.join() }
            }
        } catch (cancelled: CancellationException) {
            if (cancelled is TimeoutCancellationException) {
                if (transcribes) for (partial in pending.unfinished(audioBytes / 48)) emitInput(partial)
                throw TranslationFailure("服務等待逾時，已保留收到的字幕。", FoundryDiagnostic(stage, "TIMEOUT"))
            }
            throw cancelled
        } catch (failure: Exception) {
            // Source deltas not yet finalized are still valuable after a disconnect. Do not translate/replay them.
            if (transcribes) for (partial in pending.unfinished(audioBytes / 48)) emitInput(partial)
            val safe = failure as? TranslationFailure ?: if (failure is FoundryProtocolException)
                TranslationFailure(failure.message.orEmpty(), FoundryDiagnostic(stage, "PROTOCOL")) else classifyFoundryFailure(failure, stage)
            val code = withTimeoutOrNull(150) { runCatching { socket?.peerCloseCode() }.getOrNull() }
            throw TranslationFailure(safeDiagnosticField(safe.message.orEmpty(), key),
                (safe.diagnostic ?: FoundryDiagnostic(stage, "SESSION_FAILURE")).copy(webSocketCloseCode = safeWebSocketCloseCode(code)))
        } finally {
            withContext(NonCancellable) {
                try { checkpoint() } finally { withTimeoutOrNull(2000) { runCatching { socket?.close() } }; socket?.cancel() }
            }
        }
    }
    override fun close() { sockets.close(); translator.close() }
}
