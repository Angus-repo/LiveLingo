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
internal data class DeferredAudio(val start: Long, val startBytes: Long, val end: Long, val endBytes: Long)

@Serializable
internal data class AudioRecoveryState(
    val connection: FoundryConnection,
    val next: Long = 0,
    val committed: Long = 0,
    val bytes: Long = 0,
    val committedBytes: Long = 0,
    val blocked: Boolean = false,
    // committed is the forward cursor. Ranges behind it retain their PCM until separately acknowledged.
    val deferred: List<DeferredAudio> = emptyList(),
) {
    val pendingChunks: Long get() = next - committed + deferred.sumOf { it.end - it.start }
    val confirmedBytes: Long get() = committedBytes - deferred.sumOf { it.endBytes - it.startBytes }
}

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

    // The transcript and its text queue may already be durable after a crash between
    // saving the record and acknowledging PCM. Never replay that completed window.
    suspend fun acknowledgeSaved(checkpoint: AudioWindowCheckpoint?) {
        val saved = checkpoint ?: return
        val range = state.deferred.firstOrNull { it.start == saved.start }
        val offset = range?.startBytes ?: state.committedBytes
        if ((range != null || saved.start == state.committed) &&
            saved.end in (saved.start + 1)..(range?.end ?: state.next) &&
            saved.endBytes in (offset + 1)..(range?.endBytes ?: state.bytes)) commit(saved)
    }

    suspend fun defer(range: DeferredAudio) = lock.withLock {
        check(range.start == state.committed && range.startBytes == state.committedBytes && range.end in (range.start + 1)..state.next)
        withContext(NonCancellable + io) {
            val next = state.copy(committed = range.end, committedBytes = range.endBytes, deferred = state.deferred + range)
            storage.write(manifestKey, WireJson.encodeToString(next))
            current.value = next
        }
    }

    suspend fun commit(saved: AudioWindowCheckpoint) = lock.withLock {
        withContext(NonCancellable + io) {
            val next = if (saved.start == state.committed) {
                state.copy(committed = saved.end, committedBytes = saved.endBytes)
            } else {
                val range = state.deferred.first { it.start == saved.start }
                val remaining = state.deferred.mapNotNull {
                    if (it != range) it else if (saved.end == range.end) null
                    else range.copy(start = saved.end, startBytes = saved.endBytes)
                }
                state.copy(deferred = remaining)
            }
            // The transcript is durable before the range is removed from the manifest.
            storage.write(manifestKey, WireJson.encodeToString(next))
            current.value = next
            for (index in saved.start until saved.end) runCatching { storage.delete(chunkKey(index)) }
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
        (diagnostic.httpStatus in setOf(408, 425, 429) || diagnostic.httpStatus?.let { it in 500..599 } == true) ||
        diagnostic.sessionCloseReason in setOf("expired", "connection_lost", "remote_hangup") ||
        diagnostic.providerType in setOf("server_error", "rate_limit_error") ||
        diagnostic.providerCode?.lowercase() in setOf("server_error", "internal_error", "service_unavailable", "rate_limit_exceeded", "toomanyrequests", "session_expired", "session_timeout")
}

private fun ambiguousSessionRejection(failure: TranslationFailure): Boolean {
    val diagnostic = failure.diagnostic ?: return false
    val genericCodes = setOf("invalid_request_error", "invalid_request", "badrequest")
    if (diagnostic.code != "PROVIDER_ERROR" || diagnostic.providerParam != null) return false
    return diagnostic.providerCode?.lowercase() in genericCodes ||
        (diagnostic.providerCode == null && diagnostic.providerType == "invalid_request_error")
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
        onSnapshot: suspend (segment: Long, attempt: Int, events: List<TranscriptDelta>, checkpoint: AudioWindowCheckpoint?, publish: Boolean) -> Unit,
        onNotice: suspend (TranslationNotice) -> Unit,
        onRetry: suspend (Int, TranslationFailure) -> Unit,
        onUsage: suspend (UsageSample) -> Unit,
        allowRetry: () -> Boolean,
        attemptId: () -> String,
        stopCapture: () -> Unit,
        onAttempt: (Int, Long) -> Unit = { _, _ -> },
        contextForSegment: (Long) -> String = { "" },
        recoverDeferred: Boolean = false,
        onDeferred: (DeferredAudio) -> Unit = {},
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
                    catch (failure: Exception) { throw TranslationFailure("錄音無法寫入裝置，已停止收音。已保存的錄音與文字仍保留，請確認儲存空間。", cause = failure) }
                    revision.value++
                }
            } finally {
                captureDone = true
                revision.value++
            }
        } }
        var initial = true
        var retries = 0
        var meetingWasReady = journal.state.committed > 0
        // Only an explicit recovery run revisits previously deferred ranges.
        val previousCursor = if (recoverDeferred) journal.state.committed else 0L
        var deferredFloor = 0L
        var audioRetryEnd: Long? = null
        var retriedRejection = false
        var omitRecentContext = false
        var attempt = 0
        var filtered = false
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val state = journal.state
                val deferred = state.deferred.firstOrNull { it.start >= deferredFloor && it.start < previousCursor }
                if (!initial && captureDone && state.next == state.committed && deferred == null) break
                initial = false
                val start = deferred?.start ?: state.committed
                val offset = deferred?.startBytes ?: state.committedBytes
                // Drain an existing backlog promptly, rather than waiting for another full live window.
                val replayEnd = audioRetryEnd ?: deferred?.end ?: if (replaying && state.next > start) state.next else Long.MAX_VALUE
                val limit = if (replaying || deferred != null) minOf(windowBytes, 30L * 48_000) else windowBytes
                val queue = Channel<ByteArray>(1)
                var events: List<TranscriptDelta> = emptyList()
                val publishLock = Mutex()
                val currentAttempt = attempt++
                onAttempt(currentAttempt, start)
                val usagePrefix = attemptId()
                transport.setUsageListener { onUsage(it.copy(id = "$usagePrefix:${it.id}")) }
                var end = start
                var endBytes = offset
                var drained = false
                val sessionSettings = if (omitRecentContext) settings else settings.withRecoveryContext(contextForSegment(start))
                try {
                    coroutineScope {
                        val paceReplay = audioRetryEnd != null || deferred != null
                        val readyForReplay = CompletableDeferred<Unit>()
                        val feeder = launch {
                            if (paceReplay) readyForReplay.await()
                            var sentMillis = 0L
                            while (end < replayEnd && endBytes - offset < limit) {
                                val observed = revision.value
                                if (end < journal.state.next) {
                                    val bytes = journal.read(end)
                                    if (paceReplay) {
                                        // A fresh socket should not receive the rejected PCM in a single burst.
                                        val audioMillis = (endBytes - offset) / 48
                                        delay(audioMillis - sentMillis)
                                        sentMillis = audioMillis
                                    }
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
                            transport.run(journal.state.connection, key, sessionSettings, queue, onReady = {
                                onReady()
                                meetingWasReady = true
                                readyForReplay.complete(Unit)
                            },
                                onTranscript = { event ->
                                    if (event.startMs < 0 || event.endMs <= event.startMs || event.text.length > 65_536)
                                        throw TranslationFailure("服務回傳了無效的字幕時間或內容。")
                                    if (event.text.isNotEmpty()) {
                                        // Source and translation workers may both emit while disk IO is suspended.
                                        // Finish publishing each received delta before the next draft can replace it.
                                        withContext(NonCancellable) {
                                            publishLock.withLock {
                                                events = events + event.copy(startMs = event.startMs + offset / 48, endMs = event.endMs + offset / 48)
                                                journal.saveDraft(events)
                                                onSnapshot(start, currentAttempt, events, null, !replaying && deferred == null)
                                            }
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
                    val checkpoint = AudioWindowCheckpoint(start, end, endBytes)
                    onSnapshot(start, currentAttempt, events, checkpoint, true)
                    if (end > start) journal.commit(checkpoint)
                    audioRetryEnd = null
                    retries = 0
                    retriedRejection = false
                    replaying = journal.state.bytes - journal.state.committedBytes > 5L * 48_000
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: TranslationFailure) {
                    if (failure.diagnostic?.code in setOf("CONTENT_FILTER", "DISCONNECTED_AFTER_FILTER") ||
                        failure.diagnostic?.sessionCloseReason == "content" || isContentFilterCode(failure.diagnostic?.providerCode)) {
                        filtered = true
                        journal.blockReplay()
                    }
                    if (filtered || !allowRetry()) throw failure
                    val diagnostic = failure.diagnostic
                    val audioRejected = meetingWasReady && end > start && diagnostic != null &&
                        diagnostic.stage in setOf(FoundryStages.STREAM, FoundryStages.STOP) &&
                        diagnostic.code == "PROVIDER_ERROR" && diagnostic.providerType == "invalid_request_error" &&
                        diagnostic.providerParam == "audio" && diagnostic.providerCode in setOf(null, "invalid_request_error", "invalid_value", "invalid_parameter")
                    if (audioRejected) {
                        if (audioRetryEnd != null) {
                            val range = deferred ?: DeferredAudio(start, offset, end, endBytes).also { journal.defer(it) }
                            deferredFloor = range.end
                            onDeferred(range)
                            audioRetryEnd = null
                            retriedRejection = false
                            retries = 0
                            replaying = journal.state.bytes - journal.state.committedBytes > 5L * 48_000
                            continue
                        }
                        // Freeze the first attempted range. Audio captured during the retry belongs to the next window.
                        audioRetryEnd = end
                    } else if (!retryableTranslationFailure(failure)) {
                        // A working meeting can lose its session mid-stream or during rollover. Never
                        // continually resend the same rejected audio or retry an initial setup error.
                        val addedContextRejected = sessionSettings != settings && diagnostic?.stage == FoundryStages.START &&
                            (diagnostic.providerCode == "context_length_exceeded" ||
                                (diagnostic.providerParam == "session.instructions" &&
                                    diagnostic.providerType == "invalid_request_error"))
                        if (!meetingWasReady || retriedRejection || !(ambiguousSessionRejection(failure) || addedContextRejected)) throw failure
                        retriedRejection = true
                        if (sessionSettings != settings && diagnostic?.stage == FoundryStages.START) omitRecentContext = true
                    }
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
