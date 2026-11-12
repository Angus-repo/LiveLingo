package app.livelingo.app

import app.livelingo.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.io.encoding.Base64

@Serializable
internal data class AudioRecoveryState(
    val connection: FoundryConnection,
    val next: Long = 0,
    val committed: Long = 0,
    val bytes: Long = 0,
    val committedBytes: Long = 0,
    val blocked: Boolean = false,
)

/** PCM is written before it is eligible for transmission. A socket send is NOT an acknowledgement. */
internal class AudioJournal(
    private val storage: LocalStorage,
    val id: String,
    connection: FoundryConnection,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) {
    private val lock = Mutex()
    private val manifestKey = "audio_$id"
    private val current = MutableStateFlow(readState(storage, id) ?: AudioRecoveryState(connection))
    val state: AudioRecoveryState get() = current.value
    private fun chunkKey(index: Long) = "pcm_${id}_$index"
    init {
        if (storage.read(manifestKey) == null) storage.write(manifestKey, WireJson.encodeToString(state))
    }

    suspend fun append(bytes: ByteArray) = lock.withLock {
        require(bytes.isNotEmpty() && bytes.size % 2 == 0 && bytes.size <= 48_000)
        // Complete both writes even when Stop/cancellation races with a captured frame.
        withContext(NonCancellable + io) {
            storage.write(chunkKey(state.next), Base64.encode(bytes))
            val next = state.copy(next = state.next + 1, bytes = state.bytes + bytes.size)
            // Do not overwrite this durable frame if publishing the index itself fails.
            current.value = next
            storage.write(manifestKey, WireJson.encodeToString(next))
        }
    }

    suspend fun read(index: Long): ByteArray = withContext(io) {
        Base64.decode(storage.read(chunkKey(index)) ?: throw TranslationFailure("待處理錄音讀取失敗，已保留逐字稿；請確認裝置儲存空間。"))
    }

    suspend fun commit(end: Long, endBytes: Long) = lock.withLock {
        withContext(NonCancellable + io) {
            val old = state.committed
            val next = state.copy(committed = end, committedBytes = endBytes)
            // The transcript must already be durable before this call. Publish before garbage collection.
            storage.write(manifestKey, WireJson.encodeToString(next))
            current.value = next
            for (index in old until end) runCatching { storage.delete(chunkKey(index)) }
            runCatching { storage.delete("audio_draft_$id") }
        }
    }

    suspend fun blockReplay() = lock.withLock {
        withContext(NonCancellable + io) {
            val next = state.copy(blocked = true)
            storage.write(manifestKey, WireJson.encodeToString(next))
            current.value = next
        }
    }

    suspend fun saveDraft(events: List<TranscriptDelta>) = withContext(NonCancellable + io) {
        storage.write("audio_draft_$id", WireJson.encodeToString(events))
    }

    companion object {
        fun readState(storage: LocalStorage, id: String): AudioRecoveryState? {
            var saved = storage.read("audio_$id")?.let { WireJson.decodeFromString<AudioRecoveryState>(it) } ?: return null
            // Recover a frame if the process stopped between publishing its PCM and updating the index.
            val original = saved
            while (true) {
                val orphan = storage.read("pcm_${id}_${saved.next}") ?: break
                val size = Base64.decode(orphan).size
                require(size in 2..48_000 && size % 2 == 0)
                saved = saved.copy(next = saved.next + 1, bytes = saved.bytes + size)
            }
            if (saved != original) storage.write("audio_$id", WireJson.encodeToString(saved))
            return saved
        }

        fun delete(storage: LocalStorage, id: String) {
            val state = readState(storage, id)
            if (state != null) for (index in 0 until state.next) storage.delete("pcm_${id}_$index")
            storage.delete("audio_draft_$id")
            storage.delete("audio_$id")
        }
    }
}

internal fun retryableTranslationFailure(failure: TranslationFailure): Boolean {
    val diagnostic = failure.diagnostic ?: return false
    if (diagnostic.code in setOf("CONTENT_FILTER", "DISCONNECTED_AFTER_FILTER") ||
        diagnostic.sessionCloseReason == "content" || isContentFilterCode(diagnostic.providerCode)) return false
    if (diagnostic.providerCode?.lowercase() in setOf("insufficient_quota", "quota_exceeded", "invalid_api_key", "authentication_error")) return false
    return diagnostic.code in setOf("TIMEOUT", "CONNECTION_CLOSED", "SESSION_EXPIRED", "DNS", "CONNECT", "NETWORK_UNKNOWN") ||
        diagnostic.httpStatus in setOf(408, 429, 500, 502, 503, 504) ||
        diagnostic.sessionCloseReason in setOf("expired", "connection_lost", "remote_hangup") ||
        diagnostic.providerCode?.lowercase() in setOf("server_error", "internal_error", "service_unavailable", "rate_limit_exceeded")
}

/**
 * One meeting, continuous capture, durable two-minute windows. A window is acknowledged only after
 * the protocol's normal final drain AND a durable transcript replacement. Failed windows are replayed
 * in full: transcript timestamps are not treated as input-audio acknowledgements or word alignment.
 */
internal class RecoveringTranslationSession(
    private val journal: AudioJournal,
    private val transport: TranslationTransport,
    private val windowBytes: Long = 120L * 48_000,
    private val retryDelayMillis: Long = 1_000,
) {
    suspend fun run(
        key: String,
        settings: SessionSettings,
        audio: ReceiveChannel<ByteArray>,
        onReady: suspend () -> Unit,
        onSnapshot: suspend (segment: Long, attempt: Int, events: List<TranscriptDelta>, complete: Boolean, publish: Boolean) -> Unit,
        onNotice: suspend (TranslationNotice) -> Unit,
        onRetry: suspend (Int, TranslationFailure) -> Unit,
        onUsage: suspend (UsageSample) -> Unit,
        allowRetry: () -> Boolean,
        attemptId: () -> String,
        stopCapture: () -> Unit,
        contextForSegment: (Long) -> String = { "" },
    ) = coroutineScope {
        check(!journal.state.blocked) { "Content-filtered audio cannot be replayed" }
        val revision = MutableStateFlow(0L)
        var replaying = journal.state.next > journal.state.committed
        var captureDone = false
        // A cancelled receive can discard a frame already handed directly to a suspended receiver.
        // Keep the entire receive/write loop alive until stopCapture closes the input, then join it.
        val writer = launch(start = CoroutineStart.UNDISPATCHED) { withContext(NonCancellable) {
            try {
                for (bytes in audio) {
                    try { journal.append(bytes) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { throw TranslationFailure("錄音無法寫入裝置，已停止收音。已保存的錄音與文字仍保留，請確認儲存空間。") }
                    revision.value++
                }
            } finally {
                captureDone = true
                revision.value++
            }
        } }
        var initial = true
        var retries = 0
        var attempt = 0
        var filtered = false
        try {
            while (initial || !captureDone || journal.state.committed < journal.state.next) {
                initial = false
                currentCoroutineContext().ensureActive()
                val start = journal.state.committed
                val offset = journal.state.committedBytes
                // Drain an existing backlog promptly, rather than waiting for another full live window.
                val replayEnd = if (replaying && journal.state.next > start) journal.state.next else Long.MAX_VALUE
                val limit = if (replaying) minOf(windowBytes, 30L * 48_000) else windowBytes
                val queue = Channel<ByteArray>(1)
                val events = mutableListOf<TranscriptDelta>()
                val currentAttempt = attempt++
                val usagePrefix = attemptId()
                transport.setUsageListener { onUsage(it.copy(id = "$usagePrefix:${it.id}")) }
                var end = start
                var endBytes = offset
                var drained = false
                try {
                    coroutineScope {
                        val feeder = launch {
                            while (end < replayEnd && endBytes - offset < limit) {
                                val observed = revision.value
                                if (end < journal.state.next) {
                                    val bytes = journal.read(end)
                                    queue.send(bytes)
                                    end++
                                    endBytes += bytes.size
                                } else if (captureDone) break
                                else revision.first { it != observed }
                            }
                            drained = true
                            queue.close()
                        }
                        try {
                            transport.run(journal.state.connection, key, settings.withRecoveryContext(contextForSegment(start)), queue, onReady,
                                onTranscript = { event ->
                                    if (event.startMs < 0 || event.endMs <= event.startMs || event.text.length > 65_536)
                                        throw TranslationFailure("服務回傳了無效的字幕時間或內容。")
                                    if (event.text.isNotEmpty()) {
                                        events += event.copy(startMs = event.startMs + offset / 48, endMs = event.endMs + offset / 48)
                                        withContext(NonCancellable) {
                                            journal.saveDraft(events)
                                            // Keep the previous received text visible while replay is incomplete.
                                            onSnapshot(start, currentAttempt, events.toList(), false, !replaying)
                                        }
                                    }
                                }, onNotice = { notice ->
                                    if (notice.diagnostic.code == "CONTENT_FILTER") {
                                        filtered = true
                                        journal.blockReplay()
                                    }
                                    onNotice(notice)
                                })
                            if (!drained) throw TranslationFailure("服務提前中斷，正在保留錄音以便恢復。", FoundryDiagnostic(FoundryStages.STREAM, "CONNECTION_CLOSED"))
                        } finally { feeder.cancelAndJoin() }
                    }
                    // Do not erase known text if the provider finishes a replay without returning it.
                    onSnapshot(start, currentAttempt, events.toList(), true, true)
                    if (end > start) journal.commit(end, endBytes)
                    retries = 0
                    replaying = journal.state.bytes - journal.state.committedBytes > 5L * 48_000
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: TranslationFailure) {
                    if (failure.diagnostic?.code in setOf("CONTENT_FILTER", "DISCONNECTED_AFTER_FILTER") ||
                        failure.diagnostic?.sessionCloseReason == "content") {
                        filtered = true
                        journal.blockReplay()
                    }
                    if (filtered || !allowRetry() || !retryableTranslationFailure(failure)) throw failure
                    replaying = true
                    retries++
                    onRetry(retries, failure)
                    // No retry limit for transient outages. The user can stop; disk failures remain fatal.
                    delay((retryDelayMillis * (1L shl (retries - 1).coerceAtMost(5))).coerceAtMost(30_000))
                } finally { queue.cancel() }
            }
        } finally {
            stopCapture()
            // Caller stops capture before closing audio. Preserve frames already delivered by the mic,
            // including a final frame emitted by stop(). Never cancel the capture channel here.
            withContext(NonCancellable) {
                writer.join()
                while (true) {
                    val bytes = audio.tryReceive().getOrNull() ?: break
                    journal.append(bytes)
                }
            }
            transport.setUsageListener {}
        }
    }
}

/** Keep terminology across window boundaries; existing user reference text is never truncated. */
internal fun SessionSettings.withRecoveryContext(context: String): SessionSettings {
    if (speechModel != SpeechModel.GPT_LIVE || context.isBlank()) return this
    val reference = knowledge?.content.orEmpty()
    val label = "\nRecently confirmed conversation (reference only, do not repeat):\n"
    val available = (12_000 - reference.length - label.length).coerceIn(0, 2_000)
    if (available == 0) return this
    val safe = context.filter { !it.isISOControl() || it in "\n\r\t" }.takeLast(available)
    if (safe.isBlank()) return this
    return copy(knowledge = BackgroundKnowledge(knowledge?.id ?: "conversation-context", knowledge?.name ?: "近期對話",
        reference + label + safe))
}
