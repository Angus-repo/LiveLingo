package app.livelingo.app

import app.livelingo.core.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.Url
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.Channel
import kotlin.time.TimeSource
import kotlinx.serialization.json.*

class TranslationFailure(message: String, val diagnostic: FoundryDiagnostic? = null) : Exception(message)
data class TranslationNotice(val message: String, val diagnostic: FoundryDiagnostic, val sessionEnded: Boolean = false)

interface TranslationTransport {
    fun setUsageListener(listener: suspend (UsageSample) -> Unit) {}
    suspend fun run(
        connection: FoundryConnection,
        apiKey: String,
        settings: SessionSettings,
        audio: ReceiveChannel<ByteArray>,
        onReady: suspend () -> Unit,
        onTranscript: suspend (TranscriptDelta) -> Unit,
        onNotice: suspend (TranslationNotice) -> Unit = {},
    )
    /** Validates the real model lifecycle without using a microphone, knowledge profile, or transcript storage. */
    suspend fun probe(connection: FoundryConnection, apiKey: String): ConnectionProbeResult {
        val emptyAudio = Channel<ByteArray>(0)
        emptyAudio.close()
        try {
            var notice: TranslationNotice? = null
            run(connection, apiKey, SessionSettings(), emptyAudio, {}, {}, onNotice = { notice = it })
            notice?.let { throw TranslationFailure(it.message, it.diagnostic) }
            return ConnectionProbeResult("${connection.provider.label} 連線測試成功，GPT-Live 已啟動並正常結束。", FoundryDiagnostic(FoundryStages.COMPLETE, "OK", 101))
        } finally { emptyAudio.cancel() }
    }
    fun close()
}

/** Injectable native boundary: tests do not need credentials or relaxed Azure host validation. */
internal interface FoundrySocket {
    suspend fun send(payload: String)
    suspend fun receive(): String?
    suspend fun peerCloseCode(): Int? = null
    suspend fun close()
    fun cancel()
}

/** A stalled sender must fail its parent session, not silently cancel only the sender coroutine. */
internal suspend fun FoundrySocket.sendBounded(payload: String, stage: String) {
    try { withTimeout(15_000) { send(payload) } }
    catch (_: TimeoutCancellationException) {
        throw TranslationFailure("音訊傳送逾時，等待連線恢復。", FoundryDiagnostic(stage, "TIMEOUT"))
    }
}

internal fun interface FoundrySocketFactory {
    suspend fun open(url: String, apiKey: String): FoundrySocket
    fun close() {}
}

internal class KtorFoundrySocketFactory(private val originalClient: HttpClient) : FoundrySocketFactory {
    // Native platform engines also disable redirects.
    private val client = originalClient.config {
        followRedirects = false
        expectSuccess = false
        HttpResponseValidator {
            validateResponse { response ->
                val request = response.call.request
                if (request.url.protocol.name in setOf("ws", "wss") && response.status.value != 101) {
                    val secret = request.headers["api-key"] ?: request.headers["Authorization"]?.removePrefix("Bearer ")
                    val requestId = safeRequestId(response.headers["apim-request-id"] ?: response.headers["x-request-id"] ?: response.headers["request-id"], secret)
                    // Failed WebSocket bodies are engine-specific and may not be readable channels.
                    // Native Android interception captures safe bounded JSON fields before this layer.
                    throw httpTranslationFailure(response.status.value, FoundryStages.HANDSHAKE, requestId = requestId)
                }
            }
        }
    }
    override suspend fun open(url: String, apiKey: String): FoundrySocket {
        val session = client.webSocketSession {
            url(url)
            // Pin OpenAI credentials to its exact host. Azure resource keys use api-key.
            if (Url(url).host == "api.openai.com") header("Authorization", "Bearer $apiKey")
            else header("api-key", apiKey)
        }
        return object : FoundrySocket {
            private var receivedCloseCode: Int? = null
            override suspend fun send(payload: String) { session.send(Frame.Text(payload)) }
            override suspend fun receive(): String? {
                while (true) {
                    val received = session.incoming.receiveCatching()
                    received.exceptionOrNull()?.let { throw it }
                    val frame = received.getOrNull() ?: return null
                    if (frame.data.size > MAX_FOUNDRY_FRAME_BYTES) throw TranslationFailure("Foundry 回傳的事件超過大小限制。")
                    when (frame) {
                        is Frame.Text -> return frame.readText()
                        is Frame.Close -> {
                            receivedCloseCode = frame.readReason()?.code?.toInt()
                            return null
                        }
                        is Frame.Binary -> throw TranslationFailure("Foundry 回傳不支援的二進位事件。")
                        else -> Unit
                    }
                }
            }
            override suspend fun peerCloseCode(): Int? = receivedCloseCode ?: withTimeoutOrNull(150) {
                session.closeReason.await()?.code?.toInt()
            }
            override suspend fun close() { session.close(CloseReason(CloseReason.Codes.NORMAL, "Client finished")) }
            override fun cancel() { session.cancel() }
        }
    }
    override fun close() { client.close(); originalClient.close() }
}

class KtorFoundryTransport internal constructor(
    private val sockets: FoundrySocketFactory,
    private val startupTimeoutMillis: Long = 30_000,
    // Silence is valid. Native heartbeats detect broken sockets; window draining has its own deadline.
    private val inactivityTimeoutMillis: Long = 0,
    private val drainTimeoutMillis: Long = 15_000,
) : TranslationTransport {
    constructor(client: HttpClient) : this(KtorFoundrySocketFactory(client))
    private var usageListener: suspend (UsageSample) -> Unit = {}
    override fun setUsageListener(listener: suspend (UsageSample) -> Unit) { usageListener = listener }

    override suspend fun probe(connection: FoundryConnection, apiKey: String): ConnectionProbeResult {
        val emptyAudio = Channel<ByteArray>(0)
        emptyAudio.close()
        try {
            var notice: TranslationNotice? = null
            runSession(connection, apiKey, SessionSettings(), emptyAudio, {}, {}, { notice = it }, minimalProbe = true)
            notice?.let { throw TranslationFailure(it.message, it.diagnostic) }
            return ConnectionProbeResult(
                "${connection.provider.label} 最小連線測試成功，所選模型已啟動並正常結束；尚未驗證翻譯設定或收音。",
                FoundryDiagnostic(FoundryStages.COMPLETE, "OK", 101, minimalProbe = true),
            )
        } finally { emptyAudio.cancel() }
    }

    override suspend fun run(
        connection: FoundryConnection,
        apiKey: String,
        settings: SessionSettings,
        audio: ReceiveChannel<ByteArray>,
        onReady: suspend () -> Unit,
        onTranscript: suspend (TranscriptDelta) -> Unit,
        onNotice: suspend (TranslationNotice) -> Unit,
    ) = runSession(connection, apiKey, settings, audio, onReady, onTranscript, onNotice, minimalProbe = false)

    private suspend fun runSession(
        connection: FoundryConnection,
        apiKey: String,
        settings: SessionSettings,
        audio: ReceiveChannel<ByteArray>,
        onReady: suspend () -> Unit,
        onTranscript: suspend (TranscriptDelta) -> Unit,
        onNotice: suspend (TranslationNotice) -> Unit,
        minimalProbe: Boolean,
    ) {
        connection.validationError()?.let { throw TranslationFailure(it) }
        settings.validationError()?.let { throw TranslationFailure(it) }
        val key = apiKey.trim()
        if (key.isEmpty() || key.length > 4096 || key.any { it.isWhitespace() || it.code < 33 || it.code > 126 })
            throw TranslationFailure("請輸入有效的 Foundry API key，不可包含空白或控制字元。")
        // A previous meeting's listener may still be installed. A probe must never alter its costs.
        val reportUsage: suspend (UsageSample) -> Unit = if (minimalProbe) ({ _ -> }) else usageListener
        var usageClock: TimeSource.Monotonic.ValueTimeMark? = null
        var finalUsage = false
        var lastUsageSeconds = 0.0
        var lastCheckpoint = 0.0
        var sentAudioBytes = 0L
        var backendCount = 0
        val backendIds = mutableSetOf<String>()
        suspend fun checkpoint() {
            val seconds = usageClock?.elapsedNow()?.inWholeMilliseconds?.div(1000.0) ?: return
            if (!finalUsage) reportUsage(UsageSample("voice", UsageKind.VOICE,
                seconds = maxOf(seconds, lastUsageSeconds), estimated = true))
        }
        var socket: FoundrySocket? = null
        var sender: Job? = null
        var drainDeadline: Job? = null
        var stage = FoundryStages.HANDSHAKE
        var pendingFilter: FoundryDiagnostic? = null
        fun providerMessage(value: String): String =
            if (connection.provider == ModelProvider.OPENAI) value.replace("Foundry", "OpenAI").replace("部署", "模型") else value
        fun redacted(failure: TranslationFailure): TranslationFailure {
            val diagnostic = failure.diagnostic
            return TranslationFailure(providerMessage(failure.message ?: "服務工作階段失敗。"), diagnostic?.copy(
                requestId = safeRequestId(diagnostic.requestId, key), minimalProbe = minimalProbe))
        }
        fun disconnectedAfterFilter(): TranslationFailure = TranslationFailure(
            "Foundry 在內容篩選通知後斷線，但未回報工作階段結束原因。已收到的字幕已保留；可開始下一段收取新的音訊。若持續發生，請將診斷交給資源管理者確認。",
            pendingFilter!!.copy(stage = stage, code = "DISCONNECTED_AFTER_FILTER"),
        )
        suspend fun withCloseDetails(failure: TranslationFailure): TranslationFailure {
            // Read before client cleanup, otherwise our own normal close could look like the peer's.
            val code = runCatching { socket?.peerCloseCode() }.getOrNull()
            return TranslationFailure(failure.message.orEmpty(), failure.diagnostic?.copy(webSocketCloseCode = safeWebSocketCloseCode(code)))
        }
        fun decode(payload: String): FoundryEvent = try { parseFoundryEvent(payload) }
        catch (failure: FoundryProtocolException) {
            val fields = safeJsonErrorFields(payload, key)
            val message = if (failure.isProviderError) foundryProviderError(fields.providerCode ?: fields.providerType)
                else failure.message ?: "Foundry 回應格式不正確。"
            val guidance = if (stage != FoundryStages.START) "" else if (minimalProbe)
                " 最小連線測試未送出翻譯指令、背景知識或音訊；請複製診斷中的服務錯誤摘要。"
                else " 尚未開始收音；請先到設定執行「測試連線」，此錯誤不能判定麥克風是否正常。"
            throw TranslationFailure(message + guidance, FoundryDiagnostic(
                stage, if (failure.isProviderError) "PROVIDER_ERROR" else "PROTOCOL", httpStatus = 101,
                requestId = fields.requestId, providerCode = fields.providerCode,
                providerType = fields.providerType, providerParam = fields.providerParam,
                startupErrorSummary = if (stage == FoundryStages.START && failure.isProviderError)
                    startupErrorSummary(payload, key, buildList {
                        if (connection.provider != ModelProvider.OPENAI || connection.deployment.trim() != "gpt-live-1") add(connection.deployment.trim())
                        add(connection.endpoint)
                        if (!minimalProbe) {
                            add(translationInstructions(settings))
                            settings.knowledge?.let { add(it.id); add(it.name); add(it.content) }
                        }
                    }) else null,
            ))
        }
        try {
          coroutineScope {
            val active = withTimeout(startupTimeoutMillis) {
                sockets.open(connection.webSocketUrl(), key).also { socket = it }
            }
            stage = FoundryStages.START
            withTimeout(startupTimeoutMillis) {
                active.send(if (minimalProbe) foundryProbePayload(connection) else foundryStartPayload(connection, settings))
                while (true) {
                    val event = decode(active.receive() ?: throw TranslationFailure("Foundry 在準備完成前已中斷連線。", FoundryDiagnostic(stage, "CONNECTION_CLOSED")))
                    when (event) {
                        is FoundryEvent.Ready -> break
                        FoundryEvent.ContentFiltered -> throw TranslationFailure(foundryProviderError("content_filter") + "工作階段尚未啟動，未開始收音。",
                            FoundryDiagnostic(stage, "CONTENT_FILTER", providerCode = "content_filter"))
                        FoundryEvent.Ignored -> Unit
                        else -> throw TranslationFailure("Foundry 啟動順序不正確，尚未開始收音。")
                    }
                }
            }
            stage = FoundryStages.STREAM
            usageClock = TimeSource.Monotonic.markNow()
            reportUsage(UsageSample("voice", UsageKind.VOICE, estimated = true))
            onReady()
            val stopRequested = CompletableDeferred<Unit>()
            sender = launch {
                for (bytes in audio) {
                    active.sendBounded(foundryAudioPayload(bytes), stage)
                    sentAudioBytes += bytes.size
                    val elapsed = usageClock!!.elapsedNow().inWholeMilliseconds / 1000.0
                    if (elapsed - lastCheckpoint >= 5.0) { lastCheckpoint = elapsed; checkpoint() }
                }
                stage = FoundryStages.STOP
                stopRequested.complete(Unit)
                active.sendBounded(FOUNDRY_CLOSE_PAYLOAD, stage)
            }
            drainDeadline = launch {
                stopRequested.await()
                val ahead = (sentAudioBytes / 48 - (usageClock?.elapsedNow()?.inWholeMilliseconds ?: 0)).coerceIn(0, 120_000)
                delay(drainTimeoutMillis + if (ahead > 5_000) ahead else 0)
                throw TranslationFailure("結束等待逾時；已收到的字幕已保留，最後一句可能不完整。", FoundryDiagnostic(FoundryStages.STOP, "TIMEOUT"))
            }
            while (true) {
                // Usage/audio events count as activity; GPT-Live has no relay Ping/Pong event.
                val payload = try {
                    if (inactivityTimeoutMillis > 0) withTimeout(inactivityTimeoutMillis) { active.receive() }
                    else active.receive()
                }
                catch (_: TimeoutCancellationException) { throw TranslationFailure("Foundry 超過 90 秒沒有回應，已停止收音並保留收到的字幕。", FoundryDiagnostic(stage, "TIMEOUT")) }
                    ?: throw if (pendingFilter != null) disconnectedAfterFilter()
                    else TranslationFailure("Foundry 連線已中斷；已收到的字幕已保留，最後一句可能不完整。", FoundryDiagnostic(stage, "CONNECTION_CLOSED"))
                liveUsage(payload)?.let { usage ->
                    lastUsageSeconds = maxOf(lastUsageSeconds, usage.seconds)
                    finalUsage = usage.final
                    reportUsage(if (usage.final) usage else usage.copy(seconds = lastUsageSeconds))
                }
                // No backend delegation is configured by this app. Surface unexpected billed work as unpriced.
                runCatching { WireJson.parseToJsonElement(payload).jsonObject }.getOrNull()?.let { root ->
                    val nested = root["event"] as? JsonObject
                    if ((root["type"] as? JsonPrimitive)?.content == "response.event" &&
                        (nested?.get("type") as? JsonPrimitive)?.content in setOf("response.completed", "response.failed", "response.incomplete")) {
                        val response = nested?.get("response") as? JsonObject
                        val id = (response?.get("id") as? JsonPrimitive)?.content ?: "unknown-${backendCount++}"
                        if (backendIds.add(id)) reportUsage(UsageSample("backend-$id", UsageKind.BACKEND, known = false, final = true))
                    }
                }
                when (val event = decode(payload)) {
                    is FoundryEvent.Transcript -> {
                        pendingFilter = null
                        onTranscript(event.delta)
                    }
                    FoundryEvent.ContentFiltered -> {
                        val fields = safeJsonErrorFields(payload, key)
                        val diagnostic = FoundryDiagnostic(stage, "CONTENT_FILTER", requestId = fields.requestId, providerCode = "content_filter")
                        pendingFilter = diagnostic
                        // Keep this same session. Do not replay blocked audio, reconnect, or change filters.
                        onNotice(TranslationNotice(providerMessage("Foundry 已阻擋部分內容，該片段可能不完整。正在接收後續服務事件；工作階段若仍可用，字幕會繼續更新。"), diagnostic))
                    }
                    is FoundryEvent.Closed -> {
                        if (event.reason == "content") {
                            val diagnostic = (pendingFilter?.copy(stage = stage) ?: FoundryDiagnostic(stage, "CONTENT_FILTER", providerCode = "content_filter"))
                                .copy(sessionCloseReason = "content")
                            if (pendingFilter == null) onNotice(TranslationNotice(providerMessage(foundryCloseError(event.reason, stopRequested.isCompleted)!!), diagnostic, sessionEnded = true))
                            throw TranslationFailure(foundryCloseError(event.reason, stopRequested.isCompleted)!!, diagnostic)
                        }
                        foundryCloseError(event.reason, stopRequested.isCompleted)?.let { throw TranslationFailure(it, FoundryDiagnostic(stage, if (event.reason == "expired") "SESSION_EXPIRED" else "SESSION_CLOSED", sessionCloseReason = safeSessionCloseReason(event.reason))) }
                        break
                    }
                    is FoundryEvent.Ready -> throw TranslationFailure("Foundry 重複啟動工作階段，已停止收音。")
                    FoundryEvent.Ignored -> Unit
                }
            }
            sender?.cancelAndJoin()
            drainDeadline?.cancelAndJoin()
          }
        } catch (failure: FoundryProtocolException) {
            throw redacted(TranslationFailure(failure.message ?: "Foundry 回應格式不正確。", FoundryDiagnostic(stage, "PROTOCOL")))
        } catch (_: TimeoutCancellationException) {
            throw redacted(TranslationFailure("Foundry 連線或啟動逾時，請檢查網路、資源端點與部署。", FoundryDiagnostic(stage, "TIMEOUT")))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: TranslationFailure) {
            throw redacted(withCloseDetails(if (failure.diagnostic != null) failure else TranslationFailure(failure.message ?: "Foundry 工作階段失敗。", FoundryDiagnostic(stage, "SESSION_FAILURE"))))
        } catch (failure: Exception) {
            throw redacted(withCloseDetails(if (pendingFilter != null) disconnectedAfterFilter()
            else classifyFoundryFailure(failure, stage)))
        } finally {
            sender?.cancel()
            drainDeadline?.cancel()
            withContext(NonCancellable) {
                try { checkpoint() } finally {
                    withTimeoutOrNull(2_000) { runCatching { socket?.close() } }
                    runCatching { socket?.cancel() }
                }
            }
        }
    }

    override fun close() = sockets.close()
}
