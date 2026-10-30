package app.livelingo.app

import app.livelingo.core.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.Channel

class TranslationFailure(message: String, val diagnostic: FoundryDiagnostic? = null) : Exception(message)

interface TranslationTransport {
    suspend fun run(
        connection: FoundryConnection,
        apiKey: String,
        settings: SessionSettings,
        audio: ReceiveChannel<ByteArray>,
        onReady: suspend () -> Unit,
        onTranscript: suspend (TranscriptDelta) -> Unit,
    )
    /** Validates the real model lifecycle without using a microphone, knowledge profile, or transcript storage. */
    suspend fun probe(connection: FoundryConnection, apiKey: String): ConnectionProbeResult {
        val emptyAudio = Channel<ByteArray>(0)
        emptyAudio.close()
        try {
            run(connection, apiKey, SessionSettings(), emptyAudio, {}, {})
            return ConnectionProbeResult("Foundry 連線測試成功，GPT-Live 已啟動並正常結束。", FoundryDiagnostic(FoundryStages.COMPLETE, "OK", 101))
        } finally { emptyAudio.cancel() }
    }
    fun close()
}

/** Injectable native boundary: tests do not need credentials or relaxed Azure host validation. */
internal interface FoundrySocket {
    suspend fun send(payload: String)
    suspend fun receive(): String?
    suspend fun close()
    fun cancel()
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
                    val secret = request.headers["api-key"]
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
            // GPT-Live uses Azure resource-key authentication. Bearer is reserved for Entra tokens.
            header("api-key", apiKey)
        }
        return object : FoundrySocket {
            override suspend fun send(payload: String) { session.send(Frame.Text(payload)) }
            override suspend fun receive(): String? {
                while (true) {
                    val received = session.incoming.receiveCatching()
                    received.exceptionOrNull()?.let { throw it }
                    val frame = received.getOrNull() ?: return null
                    if (frame.data.size > MAX_FOUNDRY_FRAME_BYTES) throw TranslationFailure("Foundry 回傳的事件超過大小限制。")
                    when (frame) {
                        is Frame.Text -> return frame.readText()
                        is Frame.Close -> return null
                        is Frame.Binary -> throw TranslationFailure("Foundry 回傳不支援的二進位事件。")
                        else -> Unit
                    }
                }
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
    private val inactivityTimeoutMillis: Long = 90_000,
    private val drainTimeoutMillis: Long = 15_000,
) : TranslationTransport {
    constructor(client: HttpClient) : this(KtorFoundrySocketFactory(client))

    override suspend fun run(
        connection: FoundryConnection,
        apiKey: String,
        settings: SessionSettings,
        audio: ReceiveChannel<ByteArray>,
        onReady: suspend () -> Unit,
        onTranscript: suspend (TranscriptDelta) -> Unit,
    ) {
        connection.validationError()?.let { throw TranslationFailure(it) }
        settings.validationError()?.let { throw TranslationFailure(it) }
        val key = apiKey.trim()
        if (key.isEmpty() || key.length > 4096 || key.any { it.isWhitespace() || it.code < 33 || it.code > 126 })
            throw TranslationFailure("請輸入有效的 Foundry API key，不可包含空白或控制字元。")
        var socket: FoundrySocket? = null
        var sender: Job? = null
        var drainDeadline: Job? = null
        var stage = FoundryStages.HANDSHAKE
        fun redacted(failure: TranslationFailure): TranslationFailure {
            val diagnostic = failure.diagnostic ?: return failure
            return TranslationFailure(failure.message ?: "Foundry 工作階段失敗。", diagnostic.copy(requestId = safeRequestId(diagnostic.requestId, key)))
        }
        fun decode(payload: String): FoundryEvent = try { parseFoundryEvent(payload) }
        catch (failure: FoundryProtocolException) {
            val fields = safeJsonErrorFields(payload, key)
            throw TranslationFailure(failure.message ?: "Foundry 回應格式不正確。", FoundryDiagnostic(
                stage, if (fields.providerCode != null) "PROVIDER_ERROR" else "PROTOCOL", requestId = fields.requestId, providerCode = fields.providerCode,
            ))
        }
        try {
          coroutineScope {
            val active = withTimeout(startupTimeoutMillis) {
                sockets.open(connection.webSocketUrl(), key).also { socket = it }
            }
            stage = FoundryStages.START
            withTimeout(startupTimeoutMillis) {
                active.send(foundryStartPayload(connection, settings))
                while (true) {
                    val event = decode(active.receive() ?: throw TranslationFailure("Foundry 在準備完成前已中斷連線。", FoundryDiagnostic(stage, "CONNECTION_CLOSED")))
                    when (event) {
                        is FoundryEvent.Ready -> break
                        FoundryEvent.Ignored -> Unit
                        else -> throw TranslationFailure("Foundry 啟動順序不正確，尚未開始收音。")
                    }
                }
            }
            stage = FoundryStages.STREAM
            onReady()
            val stopRequested = CompletableDeferred<Unit>()
            sender = launch {
                for (bytes in audio) active.send(foundryAudioPayload(bytes))
                stage = FoundryStages.STOP
                stopRequested.complete(Unit)
                active.send(FOUNDRY_CLOSE_PAYLOAD)
            }
            drainDeadline = launch {
                stopRequested.await()
                delay(drainTimeoutMillis)
                throw TranslationFailure("結束等待逾時；已收到的字幕已保留，最後一句可能不完整。", FoundryDiagnostic(FoundryStages.STOP, "TIMEOUT"))
            }
            while (true) {
                // Usage/audio events count as activity; GPT-Live has no relay Ping/Pong event.
                val payload = try { withTimeout(inactivityTimeoutMillis) { active.receive() } }
                catch (_: TimeoutCancellationException) { throw TranslationFailure("Foundry 超過 90 秒沒有回應，已停止收音並保留收到的字幕。", FoundryDiagnostic(stage, "TIMEOUT")) }
                    ?: throw TranslationFailure("Foundry 連線已中斷；已收到的字幕已保留，最後一句可能不完整。", FoundryDiagnostic(stage, "CONNECTION_CLOSED"))
                when (val event = decode(payload)) {
                    is FoundryEvent.Transcript -> onTranscript(event.delta)
                    is FoundryEvent.Closed -> {
                        foundryCloseError(event.reason, stopRequested.isCompleted)?.let { throw TranslationFailure(it, FoundryDiagnostic(stage, if (event.reason == "expired") "SESSION_EXPIRED" else "SESSION_CLOSED")) }
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
            throw TranslationFailure(failure.message ?: "Foundry 回應格式不正確。", FoundryDiagnostic(stage, "PROTOCOL"))
        } catch (_: TimeoutCancellationException) {
            throw TranslationFailure("Foundry 連線或啟動逾時，請檢查網路、資源端點與部署。", FoundryDiagnostic(stage, "TIMEOUT"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: TranslationFailure) {
            throw redacted(if (failure.diagnostic != null) failure else TranslationFailure(failure.message ?: "Foundry 工作階段失敗。", FoundryDiagnostic(stage, "SESSION_FAILURE")))
        } catch (failure: Exception) {
            throw redacted(classifyFoundryFailure(failure, stage))
        } finally {
            sender?.cancel()
            drainDeadline?.cancel()
            withContext(NonCancellable) {
                withTimeoutOrNull(2_000) { runCatching { socket?.close() } }
                runCatching { socket?.cancel() }
            }
        }
    }

    override fun close() = sockets.close()
}
