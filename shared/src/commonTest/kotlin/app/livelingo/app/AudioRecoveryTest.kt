@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.livelingo.app

import app.livelingo.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class AudioRecoveryTest {
    private val connection = FoundryConnection("https://example.openai.azure.com")
    private val settings = SessionSettings()

    @Test fun timeoutReplaysSentAndUnsentAudioAndRetainsCaptureDuringBackoff() = runTest {
        val storage = RecoveryStorage()
        val journal = AudioJournal(storage, "meeting", connection, StandardTestDispatcher(testScheduler))
        val capture = Channel<ByteArray>(Channel.UNLIMITED)
        val received = mutableListOf<List<Byte>>()
        val snapshots = mutableListOf<Pair<Boolean, String>>()
        var attempts = 0
        var stopped = false
        val transport = ScriptTransport { audio, ready, transcript, _ ->
            val attempt = attempts++
            ready()
            if (attempt == 0) {
                val chunk = audio.receive()
                assertTrue(storage.values.keys.any { it.startsWith("pcm_") }, "persist before send")
                received += chunk.toList()
                transcript(TranscriptDelta(TranscriptChannel.INPUT, "partial", 0, 100))
                throw TranslationFailure("timeout", FoundryDiagnostic(FoundryStages.STREAM, "TIMEOUT"))
            }
            val bytes = mutableListOf<Byte>()
            for (chunk in audio) bytes += chunk.toList()
            received += bytes
            transcript(TranscriptDelta(TranscriptChannel.INPUT, "complete", 0, 300))
        }
        capture.send(byteArrayOf(1, 2))
        RecoveringTranslationSession(journal, transport, retryDelayMillis = 10).run("key", settings, capture,
            {}, { _, _, events, _, publish -> snapshots += publish to events.joinToString("") { it.text } }, {},
            { count, _ ->
                assertEquals(1, count)
                capture.send(byteArrayOf(3, 4)) // Recording continues while no socket exists.
                capture.send(byteArrayOf(5, 6))
                capture.close()
            }, {}, { true }, { "attempt-$attempts" }, { stopped = true; capture.close() })
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5, 6), received.last())
        assertTrue(snapshots.contains(false to "complete")) // Incomplete retry does not erase old captions.
        assertEquals(true to "complete", snapshots.last())
        assertEquals(journal.state.next, journal.state.committed)
        assertFalse(storage.values.keys.any { it.startsWith("pcm_") })
        assertTrue(stopped)
    }

    @Test fun successfulWindowsAdvanceOffsetsAndDoNotReplayEarlierAudio() = runTest {
        val storage = RecoveryStorage()
        val journal = AudioJournal(storage, "windows", connection, StandardTestDispatcher(testScheduler))
        val capture = Channel<ByteArray>(Channel.UNLIMITED)
        repeat(3) { capture.send(ByteArray(4800) { it.toByte() }) }
        capture.close()
        val segments = mutableListOf<Long>()
        val starts = mutableListOf<Long>()
        val sent = mutableListOf<Int>()
        val usage = mutableListOf<UsageSample>()
        val transport = ScriptTransport { audio, ready, transcript, _ ->
            ready()
            var size = 0
            for (bytes in audio) size += bytes.size
            sent += size
            transcript(TranscriptDelta(TranscriptChannel.INPUT, "word", 0, 100))
        }
        var seq = 0
        RecoveringTranslationSession(journal, transport, windowBytes = 4800).run("key", settings, capture,
            {}, { segment, _, events, complete, _ -> if (complete) { segments += segment; starts += events.single().startMs } }, {},
            { _, _ -> fail("Healthy rollover should not back off") }, { usage += it }, { true }, { "${seq++}" }, {})
        assertEquals(listOf(4800, 4800, 4800), sent)
        assertEquals(listOf(0L, 1L, 2L), segments)
        assertEquals(listOf(0L, 100L, 200L), starts)
        assertEquals(3, usage.map { it.id }.distinct().size)
    }

    @Test fun cancellingDuringBackoffKeepsFinalMicrophoneFrameForRestart() = runTest {
        val storage = RecoveryStorage()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val journal = AudioJournal(storage, "restart", connection, dispatcher)
        val capture = Channel<ByteArray>(Channel.UNLIMITED)
        val retrying = CompletableDeferred<Unit>()
        val transport = ScriptTransport { audio, ready, _, _ ->
            ready(); audio.receive()
            throw TranslationFailure("timeout", FoundryDiagnostic(FoundryStages.STREAM, "TIMEOUT"))
        }
        capture.send(byteArrayOf(1, 2))
        val job = launch {
            RecoveringTranslationSession(journal, transport).run("key", settings, capture, {}, { _, _, _, _, _ -> }, {},
                { _, _ -> retrying.complete(Unit) }, {}, { true }, { "a" }, {
                    capture.trySend(byteArrayOf(3, 4)) // The native stop callback may deliver one final frame.
                    capture.close()
                })
        }
        retrying.await()
        job.cancelAndJoin()
        val restored = AudioJournal(storage, "restart", connection, dispatcher)
        assertEquals(0L, restored.state.committed)
        assertEquals(2L, restored.state.next)
        assertContentEquals(byteArrayOf(1, 2), restored.read(0))
        assertContentEquals(byteArrayOf(3, 4), restored.read(1))
        assertFalse(storage.values.values.any { "key" == it })
    }

    @Test fun failedTranscriptCommitNeverDeletesCapturedAudio() = runTest {
        val storage = RecoveryStorage()
        val journal = AudioJournal(storage, "disk", connection, StandardTestDispatcher(testScheduler))
        val capture = Channel<ByteArray>(Channel.UNLIMITED).also { it.trySend(byteArrayOf(1, 2)); it.close() }
        val transport = ScriptTransport { audio, ready, transcript, _ ->
            ready(); for (bytes in audio) Unit
            transcript(TranscriptDelta(TranscriptChannel.INPUT, "saved draft", 0, 100))
        }
        assertFailsWith<TranslationFailure> {
            RecoveringTranslationSession(journal, transport).run("key", settings, capture, {},
                { _, _, _, complete, _ -> if (complete) throw TranslationFailure("disk full") }, {}, { _, _ -> fail("No network retry") }, {}, { true }, { "a" }, {})
        }
        assertEquals(0L, journal.state.committed)
        assertContentEquals(byteArrayOf(1, 2), journal.read(0))
        assertTrue(storage.values["audio_draft_disk"].orEmpty().contains("saved draft"))
    }

    @Test fun contentFilteringNeverReplaysBlockedAudioEvenAfterRestart() = runTest {
        val storage = RecoveryStorage()
        val journal = AudioJournal(storage, "filter", connection, StandardTestDispatcher(testScheduler))
        val capture = Channel<ByteArray>(Channel.UNLIMITED).also { it.trySend(byteArrayOf(1, 2)); it.close() }
        var starts = 0
        val transport = ScriptTransport { audio, ready, _, notice ->
            starts++; ready(); audio.receive()
            notice(TranslationNotice("filter", FoundryDiagnostic(FoundryStages.STREAM, "CONTENT_FILTER")))
            throw TranslationFailure("timeout", FoundryDiagnostic(FoundryStages.STREAM, "TIMEOUT"))
        }
        assertFailsWith<TranslationFailure> {
            RecoveringTranslationSession(journal, transport).run("key", settings, capture, {}, { _, _, _, _, _ -> }, {},
                { _, _ -> fail("Filtered audio must not be retried") }, {}, { true }, { "a" }, {})
        }
        assertEquals(1, starts)
        assertTrue(AudioJournal.readState(storage, "filter")!!.blocked)
        assertEquals(0L, journal.state.committed)
    }

    @Test fun recoversPcmWrittenJustBeforeAnInterruptedIndexUpdate() = runTest {
        val storage = RecoveryStorage()
        AudioJournal(storage, "orphan", connection, StandardTestDispatcher(testScheduler))
        storage.write("pcm_orphan_0", kotlin.io.encoding.Base64.encode(byteArrayOf(9, 10)))
        val restored = AudioJournal(storage, "orphan", connection, StandardTestDispatcher(testScheduler))
        assertEquals(1L, restored.state.next)
        assertContentEquals(byteArrayOf(9, 10), restored.read(0))
        assertEquals(2L, AudioJournal.readState(storage, "orphan")!!.bytes)
    }

    @Test fun resumedLongBacklogUsesBoundedWindows() = runTest {
        val storage = RecoveryStorage()
        val journal = AudioJournal(storage, "backlog", connection, StandardTestDispatcher(testScheduler))
        repeat(65) { journal.append(ByteArray(48_000)) }
        val capture = Channel<ByteArray>(0).also { it.close() }
        val durations = mutableListOf<Int>()
        val transport = ScriptTransport { audio, ready, _, _ ->
            ready()
            var bytes = 0
            for (chunk in audio) bytes += chunk.size
            durations += bytes / 48_000
        }
        RecoveringTranslationSession(journal, transport).run("key", settings, capture, {}, { _, _, _, _, _ -> }, {},
            { _, _ -> fail("No retry") }, {}, { true }, { "a" }, {})
        assertEquals(listOf(30, 30, 5), durations)
    }

    @Test fun confirmedContextKeepsOriginalKnowledgeAndOnlyAppliesToSupportedModel() {
        val original = BackgroundKnowledge("bank", "Banking", "x".repeat(11_900))
        val settings = SessionSettings(knowledge = original)
        val updated = settings.withRecoveryContext("previous conversation".repeat(500))
        assertTrue(updated.knowledge!!.content.startsWith(original.content))
        assertTrue(updated.knowledge!!.content.length <= 12_000)
        assertNull(updated.validationError())
        assertEquals(settings.copy(speechModel = SpeechModel.REALTIME_TRANSLATE),
            settings.copy(speechModel = SpeechModel.REALTIME_TRANSLATE).withRecoveryContext("private reference"))
    }

    @Test fun receivedReplayDraftIsReadableAfterRestartEvenBeforeTranscriptReplacement() {
        val storage = RecoveryStorage()
        val repository = AppRepository(storage)
        repository.saveTranscript(TranscriptRecord("draft", "Meeting", 1, settings,
            fragments = listOf(TranscriptFragment("old", TranscriptChannel.INPUT, 0, 100, "old text"))))
        storage.write("audio_draft_draft", WireJson.encodeToString(listOf(TranscriptDelta(TranscriptChannel.INPUT, "new text", 0, 200))))
        val record = repository.history().single()
        assertEquals("old text", record.fragments.single().text)
        assertEquals("new text", record.recoveredText.single().text)
        repository.saveTranscript(record)
        assertEquals(1, repository.history().single().recoveredText.size)
    }

    @Test fun retryPolicySeparatesTemporaryFailuresFromCredentialsQuotaAndProtocolErrors() {
        for (code in listOf("TIMEOUT", "DNS", "CONNECT", "CONNECTION_CLOSED", "SESSION_EXPIRED"))
            assertTrue(retryableTranslationFailure(TranslationFailure("test", FoundryDiagnostic(FoundryStages.STREAM, code))))
        for (code in listOf("TLS", "PROTOCOL", "HTTP_401", "HTTP_403", "CONTENT_FILTER", "DISCONNECTED_AFTER_FILTER", "PROVIDER_ERROR"))
            assertFalse(retryableTranslationFailure(TranslationFailure("test", FoundryDiagnostic(FoundryStages.STREAM, code))))
        assertTrue(retryableTranslationFailure(TranslationFailure("test", FoundryDiagnostic(FoundryStages.STREAM, "HTTP_503", 503))))
        assertFalse(retryableTranslationFailure(TranslationFailure("test", FoundryDiagnostic(FoundryStages.STREAM, "HTTP_429", 429, providerCode = "insufficient_quota"))))
        assertFalse(retryableTranslationFailure(TranslationFailure("disk failure")))
    }
}

private class RecoveryStorage : LocalStorage {
    val values = mutableMapOf<String, String>()
    override fun read(key: String) = values[key]
    override fun write(key: String, value: String) { values[key] = value }
    override fun delete(key: String) { values.remove(key) }
    override fun readSecret(key: String): String? = null
    override fun writeSecret(key: String, value: String) {}
}

internal class ScriptTransport(private val script: suspend (ReceiveChannel<ByteArray>, suspend () -> Unit,
    suspend (TranscriptDelta) -> Unit, suspend (TranslationNotice) -> Unit) -> Unit) : TranslationTransport {
    private var usage: suspend (UsageSample) -> Unit = {}
    override fun setUsageListener(listener: suspend (UsageSample) -> Unit) { usage = listener }
    override suspend fun run(connection: FoundryConnection, apiKey: String, settings: SessionSettings, audio: ReceiveChannel<ByteArray>,
        onReady: suspend () -> Unit, onTranscript: suspend (TranscriptDelta) -> Unit, onNotice: suspend (TranslationNotice) -> Unit) {
        usage(UsageSample("voice", UsageKind.VOICE, seconds = 1.0))
        script(audio, onReady, onTranscript, onNotice)
    }
    override fun close() {}
}
