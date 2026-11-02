@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package app.livelingo.app

import app.livelingo.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

private class RealtimeSocket : FoundrySocket {
    val incoming = Channel<String>(Channel.UNLIMITED)
    val sent = mutableListOf<JsonObject>()
    var closed = false
    var cancelled = false
    var respondToClose = true
    var onCommit: suspend () -> Unit = {}
    override suspend fun send(payload: String) {
        val event = decodeRealtimeObject(payload); sent += event
        when (event.eventString("type")) {
            "session.update" -> {
                incoming.send("""{"type":"session.created","session":{"id":"s"}}""")
                incoming.send("""{"type":"session.updated","session":{"id":"s"}}""")
            }
            "session.close" -> if (respondToClose) incoming.send("""{"type":"session.closed","reason":"close_requested"}""")
            "input_audio_buffer.commit" -> onCommit()
        }
    }
    override suspend fun receive() = incoming.receiveCatching().getOrNull()
    override suspend fun close() { closed = true }
    override fun cancel() { cancelled = true }
}

class RealtimeTransportTest {
    private fun c(model: SpeechModel) = FoundryConnection("https://demo.services.ai.azure.com", "voice-custom", ModelProvider.FOUNDRY,
        model, TextModel.GPT6_LUNA, "luna-custom")

    @Test fun durationIncludesOnlySentAudioAndOptionalSourceTranscription() = runTest {
        val socket = RealtimeSocket(); val audio = Channel<ByteArray>(10)
        val usage = mutableListOf<UsageSample>()
        val transport = RealtimeTransport(FoundrySocketFactory { _, _ -> socket }, TextTranslator { _, _, _, _, _ -> error("Not used") })
        transport.setUsageListener { usage += it }
        audio.send(ByteArray(48_000)); audio.send(ByteArray(24_000)); audio.close()
        transport.run(c(SpeechModel.REALTIME_TRANSLATE).copy(sourceTranscriptionModel = "whisper-deployment"), "test-key", SessionSettings(), audio, {}, {}, {})
        assertEquals(1.5, usage.last { it.kind == UsageKind.VOICE }.seconds)
        assertEquals(1.5, usage.last { it.kind == UsageKind.SOURCE }.seconds)
        assertTrue(usage.all { it.estimated })
    }

    @Test fun translationUsesUpdateAppendAndGracefulCloseAndRetainsBothCaptionTracks() = runTest {
        val socket = RealtimeSocket(); val audio = Channel<ByteArray>(10)
        val transcripts = mutableListOf<TranscriptDelta>(); val ready = CompletableDeferred<Unit>()
        var url = ""
        val transport = RealtimeTransport(FoundrySocketFactory { u, _ -> url = u; socket }, TextTranslator { _, _, _, _, _ -> error("Not used") })
        val result = async { transport.run(c(SpeechModel.REALTIME_TRANSLATE), "test-key", SessionSettings(), audio, { ready.complete(Unit) }, { transcripts += it }, {}) }
        ready.await(); audio.send(ByteArray(9600)); runCurrent()
        socket.incoming.send("""{"type":"session.input_transcript.delta","delta":"Hello","elapsed_ms":200}""")
        socket.incoming.send("""{"type":"response.text.delta","text":"你好"}"""); runCurrent()
        audio.close(); result.await()
        assertTrue(url.endsWith("realtime/translations?model=voice-custom"))
        assertEquals(listOf("session.update", "session.input_audio_buffer.append", "session.close"), socket.sent.map { it.eventString("type") })
        assertEquals(listOf(TranscriptChannel.INPUT, TranscriptChannel.OUTPUT), transcripts.map { it.channel })
        assertTrue(socket.closed && socket.cancelled)
    }
    @Test fun bothTranscriptionModelsTranslateFinalsOnceAndDrainLastAudioOnStop() = runTest {
        for (model in listOf(SpeechModel.LIVE_TRANSCRIBE, SpeechModel.MAI_TRANSCRIBE)) {
            val socket = RealtimeSocket(); val audio = Channel<ByteArray>(10)
            val ready = CompletableDeferred<Unit>(); val transcripts = mutableListOf<TranscriptDelta>(); val translated = mutableListOf<String>()
            var commits = 0
            socket.onCommit = {
                commits++
                socket.incoming.send("""{"type":"input_audio_buffer.committed","item_id":"$commits"}""")
                socket.incoming.send("""{"type":"conversation.item.input_audio_transcription.delta","item_id":"$commits","delta":"draft"}""")
                val completed = """{"type":"conversation.item.input_audio_transcription.completed","item_id":"$commits","transcript":"Final $commits."}"""
                socket.incoming.send(completed); socket.incoming.send(completed)
            }
            val transport = RealtimeTransport(FoundrySocketFactory { _, _ -> socket }, TextTranslator { connection, _, _, text, _ ->
                assertEquals("luna-custom", connection.textDeployment); translated += text; "譯文${translated.size}。"
            })
            val result = async { transport.run(c(model), "test-key", SessionSettings(), audio, { ready.complete(Unit) }, { transcripts += it }, {}) }
            ready.await()
            repeat(3) { audio.send(ByteArray(48000)) }; runCurrent()
            audio.send(ByteArray(9600)); audio.close(); result.await()
            assertEquals(2, commits)
            assertEquals(listOf("Final 1.", "Final 2."), translated)
            assertEquals(2, transcripts.count { it.channel == TranscriptChannel.INPUT })
            assertEquals(2, transcripts.count { it.channel == TranscriptChannel.OUTPUT })
            assertFalse(transcripts.any { "draft" in it.text })
            assertFalse(socket.sent.any { it.eventString("type") in setOf("session.start", "session.close") })
            assertEquals(3200L, transcripts.filter { it.channel == TranscriptChannel.INPUT }.last().endMs)
            assertTrue(socket.closed && socket.cancelled)
        }
    }
    @Test fun completionsOutOfOrderAreReorderedAndAuthoritativeFinalsReplaceDrafts() {
        val buffer = CommittedTranscriptBuffer()
        buffer.commit(3000); buffer.acknowledge("one")
        buffer.commit(6000); buffer.acknowledge("two")
        buffer.partial(decodeRealtimeObject("""{"type":"x","item_id":"one","delta":"wrong"}"""))
        assertTrue(buffer.complete(decodeRealtimeObject("""{"type":"x","item_id":"two","transcript":"second"}""")).isEmpty())
        val ordered = buffer.complete(decodeRealtimeObject("""{"type":"x","item_id":"one","transcript":"first"}"""))
        assertEquals(listOf("first", "second"), ordered.map { it.text })
        assertEquals(listOf(0L, 3000L), ordered.map { it.startMs })
        assertTrue(buffer.empty)
    }
    @Test fun maiCompletedEventWithoutItemIdStillMatchesCommitOrder() {
        val buffer = CommittedTranscriptBuffer(); buffer.commit(1000); buffer.acknowledge(null)
        val result = buffer.complete(decodeRealtimeObject("""{"type":"x","transcript":"full final"}"""))
        assertEquals("full final", result.single().text); assertTrue(buffer.empty)
    }
    @Test fun maiDeltasWithIdsAreReleasedWhenFinalEventsOmitIds() {
        val buffer = CommittedTranscriptBuffer()
        repeat(40) { index ->
            // Deltas may begin before the client commits the current interval.
            buffer.partial(decodeRealtimeObject("""{"type":"x","item_id":"item-$index","delta":"Final $index"}"""))
            buffer.commit((index + 1L) * 3000); buffer.acknowledge(null)
            val result = buffer.complete(decodeRealtimeObject("""{"type":"x","transcript":"Final $index"}"""))
            assertEquals("Final $index", result.single().text)
        }
        assertTrue(buffer.empty)
        assertTrue(buffer.unfinished(120_000).isEmpty())
    }
    @Test fun textTranslationFailurePreservesOriginalAndStopsWithoutRetry() = runTest {
        val socket = RealtimeSocket(); val audio = Channel<ByteArray>(10); val ready = CompletableDeferred<Unit>()
        val transcripts = mutableListOf<TranscriptDelta>(); var calls = 0
        socket.onCommit = { socket.incoming.send("""{"type":"conversation.item.input_audio_transcription.completed","transcript":"Keep 123.45"}""") }
        val transport = RealtimeTransport(FoundrySocketFactory { _, _ -> socket }, TextTranslator { _, _, _, _, _ -> calls++; throw TranslationFailure("文字翻譯遭阻擋") })
        val result = async { runCatching { transport.run(c(SpeechModel.MAI_TRANSCRIBE), "test-key", SessionSettings(), audio, { ready.complete(Unit) }, { transcripts += it }, {}) } }
        ready.await(); audio.send(ByteArray(9600)); audio.close()
        assertIs<TranslationFailure>(result.await().exceptionOrNull())
        assertEquals("Keep 123.45", transcripts.single().text); assertEquals(1, calls)
        assertTrue(socket.closed && socket.cancelled)
    }
    @Test fun disconnectedTranscriptionRetainsUnfinishedSourceWithoutTranslatingIt() = runTest {
        val socket = RealtimeSocket(); val audio = Channel<ByteArray>(10); val ready = CompletableDeferred<Unit>()
        val transcripts = mutableListOf<TranscriptDelta>()
        val transport = RealtimeTransport(FoundrySocketFactory { _, _ -> socket }, TextTranslator { _, _, _, _, _ -> error("Must not translate") })
        val result = async { runCatching { transport.run(c(SpeechModel.LIVE_TRANSCRIBE), "test-key", SessionSettings(), audio, { ready.complete(Unit) }, { transcripts += it }, {}) } }
        ready.await(); audio.send(ByteArray(9600)); runCurrent()
        socket.incoming.send("""{"type":"conversation.item.input_audio_transcription.delta","item_id":"partial","delta":"unfinished original"}""")
        socket.incoming.close()
        assertTrue(result.await().isFailure)
        assertEquals("unfinished original", transcripts.single().text)
    }
    @Test fun emptyProbeDoesNotCommitOrCallTranslator() = runTest {
        val socket = RealtimeSocket()
        val transport = RealtimeTransport(FoundrySocketFactory { _, _ -> socket }, TextTranslator { _, _, _, _, _ -> error("Must not translate") })
        assertEquals("OK", transport.probe(c(SpeechModel.MAI_TRANSCRIBE), "test-key").diagnostic.code)
        assertEquals(listOf("session.update"), socket.sent.map { it.eventString("type") })
        assertTrue(socket.closed)
    }
    @Test fun missingTranscriptionFinalPreservesPartialOnDrainTimeout() = runTest {
        val socket = RealtimeSocket(); val audio = Channel<ByteArray>(10)
        val transcripts = mutableListOf<TranscriptDelta>(); val ready = CompletableDeferred<Unit>()
        socket.onCommit = {
            socket.incoming.send("""{"type":"input_audio_buffer.committed","item_id":"one"}""")
            socket.incoming.send("""{"type":"conversation.item.input_audio_transcription.delta","item_id":"one","delta":"Keep 42"}""")
        }
        val transport = RealtimeTransport(FoundrySocketFactory { _, _ -> socket }, TextTranslator { _, _, _, _, _ -> error("Do not translate unfinished text") }, drainTimeoutMillis = 50)
        val result = async { runCatching { transport.run(c(SpeechModel.LIVE_TRANSCRIBE), "test-key", SessionSettings(), audio, { ready.complete(Unit) }, { transcripts += it }, {}) } }
        ready.await(); audio.send(ByteArray(9600)); audio.close()
        val failure = assertIs<TranslationFailure>(result.await().exceptionOrNull())
        assertEquals("TIMEOUT", failure.diagnostic?.code)
        assertEquals("Keep 42", transcripts.single().text)
        assertTrue(socket.closed && socket.cancelled)
    }
    @Test fun startupErrorsNeverStartMicrophoneAndNeverExposeServiceBody() = runTest {
        val socket = RealtimeSocket(); socket.incoming.send("""{"type":"error","error":{"code":"invalid_api_key","message":"test-key PRIVATE"}}""")
        var ready = false
        val transport = RealtimeTransport(FoundrySocketFactory { _, _ -> socket }, TextTranslator { _, _, _, _, _ -> error("Unused") })
        val failure = assertFailsWith<TranslationFailure> { transport.run(c(SpeechModel.REALTIME_TRANSLATE), "test-key", SessionSettings(), Channel(), { ready = true }, {}, {}) }
        assertFalse(ready); assertFalse("PRIVATE" in failure.message.orEmpty()); assertFalse("test-key" in failure.message.orEmpty())
    }
    @Test fun stoppingWaitsForFinalEventButTimesOutSafely() = runTest {
        val socket = RealtimeSocket(); socket.respondToClose = false
        val audio = Channel<ByteArray>().also { it.close() }
        val transport = RealtimeTransport(FoundrySocketFactory { _, _ -> socket }, TextTranslator { _, _, _, _, _ -> error("Unused") }, drainTimeoutMillis = 50)
        val failure = assertFailsWith<TranslationFailure> { transport.run(c(SpeechModel.REALTIME_TRANSLATE), "test-key", SessionSettings(), audio, {}, {}, {}) }
        assertEquals("TIMEOUT", failure.diagnostic?.code)
        assertTrue(socket.cancelled)
    }
}
