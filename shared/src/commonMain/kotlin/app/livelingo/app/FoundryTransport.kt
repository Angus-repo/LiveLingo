package app.livelingo.app

import app.livelingo.core.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel

class TranslationFailure(message: String) : Exception(message)

interface TranslationTransport {
    suspend fun run(
        connection: FoundryConnection,
        apiKey: String,
        settings: SessionSettings,
        audio: ReceiveChannel<ByteArray>,
        onReady: suspend () -> Unit,
        onTranscript: suspend (TranscriptDelta) -> Unit,
    )
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
    suspend fun open(url: String, authorization: String): FoundrySocket
    fun close() {}
}

private class KtorFoundrySocketFactory(private val originalClient: HttpClient) : FoundrySocketFactory {
    // Native platform engines also disable redirects.
    private val client = originalClient.config { followRedirects = false }
    override suspend fun open(url: String, authorization: String): FoundrySocket {
        val session = client.webSocketSession {
            url(url)
            header("Authorization", authorization)
        }
        return object : FoundrySocket {
            override suspend fun send(payload: String) { session.send(Frame.Text(payload)) }
            override suspend fun receive(): String? {
                while (true) {
                    val frame = session.incoming.receiveCatching().getOrNull() ?: return null
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
        try {
          coroutineScope {
            val active = withTimeout(startupTimeoutMillis) { sockets.open(connection.webSocketUrl(), "Bearer $key") }
            socket = active
            active.send(foundryStartPayload(connection, settings))
            withTimeout(startupTimeoutMillis) {
                while (true) {
                    val event = parseFoundryEvent(active.receive() ?: throw TranslationFailure("Foundry 在準備完成前已中斷連線。"))
                    when (event) {
                        is FoundryEvent.Ready -> break
                        FoundryEvent.Ignored -> Unit
                        else -> throw TranslationFailure("Foundry 啟動順序不正確，尚未開始收音。")
                    }
                }
            }
            onReady()
            val stopRequested = CompletableDeferred<Unit>()
            sender = launch {
                for (bytes in audio) active.send(foundryAudioPayload(bytes))
                stopRequested.complete(Unit)
                active.send(FOUNDRY_CLOSE_PAYLOAD)
            }
            drainDeadline = launch {
                stopRequested.await()
                delay(drainTimeoutMillis)
                throw TranslationFailure("結束等待逾時；已收到的字幕已保留，最後一句可能不完整。")
            }
            while (true) {
                // Usage/audio events count as activity; GPT-Live has no relay Ping/Pong event.
                val payload = try { withTimeout(inactivityTimeoutMillis) { active.receive() } }
                catch (_: TimeoutCancellationException) { throw TranslationFailure("Foundry 超過 90 秒沒有回應，已停止收音並保留收到的字幕。") }
                    ?: throw TranslationFailure("Foundry 連線已中斷；已收到的字幕已保留，最後一句可能不完整。")
                when (val event = parseFoundryEvent(payload)) {
                    is FoundryEvent.Transcript -> onTranscript(event.delta)
                    is FoundryEvent.Closed -> {
                        foundryCloseError(event.reason, stopRequested.isCompleted)?.let { throw TranslationFailure(it) }
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
            throw TranslationFailure(failure.message ?: "Foundry 回應格式不正確。")
        } catch (_: TimeoutCancellationException) {
            throw TranslationFailure("Foundry 連線或啟動逾時，請檢查網路、資源端點與部署。")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: TranslationFailure) {
            throw failure
        } catch (_: Exception) {
            throw TranslationFailure("無法連接 Foundry，請檢查網路、API key、資源端點與 GPT-Live 部署權限。")
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
