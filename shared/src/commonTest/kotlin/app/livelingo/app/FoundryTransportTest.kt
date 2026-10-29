package app.livelingo.app

import app.livelingo.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

private const val STARTED = "{\"type\":\"session.started\",\"session\":{\"id\":\"test-session\"}}"
private const val CLOSED = "{\"type\":\"session.closed\",\"reason\":\"close_requested\"}"

private class FakeFoundrySocket : FoundrySocket {
    val incoming = Channel<String>(Channel.UNLIMITED)
    val sent = Channel<String>(Channel.UNLIMITED)
    val sentTypes = mutableListOf<String>()
    var closed = false
    var cancelled = false
    var sendFailure: Throwable? = null
    override suspend fun send(payload: String) {
        sendFailure?.let { throw it }
        sentTypes += WireJson.parseToJsonElement(payload).jsonObject.getValue("type").jsonPrimitive.content
        sent.send(payload)
    }
    override suspend fun receive(): String? = incoming.receiveCatching().getOrNull()
    override suspend fun close() { closed = true }
    override fun cancel() { cancelled = true }
}

private class Harness(scope: CoroutineScope, val socket: FakeFoundrySocket = FakeFoundrySocket()) {
    val audio = Channel<ByteArray>(10)
    val result = CompletableDeferred<Result<Unit>>()
    val transcripts = mutableListOf<TranscriptDelta>()
    var readyCount = 0
    var url: String? = null
    var authorization: String? = null
    val transport = KtorFoundryTransport(FoundrySocketFactory { target, auth -> url = target; authorization = auth; socket })
    val job = scope.launch {
        result.complete(runCatching {
            transport.run(FoundryConnection("https://Sample.openai.azure.com/openai/v1/", "custom-live"), "private-test-key", SessionSettings(), audio,
                onReady = { readyCount++ }, onTranscript = { transcripts += it })
        })
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FoundryTransportTest {
    @Test fun waitsForStartedThenSendsPcmAndDrainsFinalCaptionsAfterClose() = runTest {
        val h = Harness(this)
        val start = WireJson.parseToJsonElement(h.socket.sent.receive()).jsonObject
        assertEquals("session.start", start.getValue("type").jsonPrimitive.content)
        assertEquals("custom-live", start.getValue("session").jsonObject.getValue("model").jsonPrimitive.content)
        assertEquals("wss://sample.openai.azure.com/openai/v1/live/sessions", h.url)
        assertEquals("Bearer private-test-key", h.authorization)
        h.audio.send(byteArrayOf(0, 0, 1, 0))
        runCurrent()
        assertEquals(0, h.readyCount)
        assertTrue(h.socket.sent.tryReceive().isFailure)
        h.socket.incoming.send(STARTED)
        runCurrent()
        assertEquals(1, h.readyCount)
        assertEquals("session.input_audio.append", WireJson.parseToJsonElement(h.socket.sent.receive()).jsonObject.getValue("type").jsonPrimitive.content)
        h.audio.close()
        assertEquals(FOUNDRY_CLOSE_PAYLOAD, h.socket.sent.receive())
        h.socket.incoming.send("""{"type":"session.input_transcript.delta","delta":"hello","start_ms":100,"end_ms":500}""")
        h.socket.incoming.send("""{"type":"session.output_transcript.delta","delta":"您好","start_ms":900,"end_ms":1400}""")
        h.socket.incoming.send(CLOSED)
        assertTrue(h.result.await().isSuccess)
        assertEquals(listOf(TranscriptChannel.INPUT, TranscriptChannel.OUTPUT), h.transcripts.map { it.channel })
        assertEquals(900L, h.transcripts.last().startMs)
        assertTrue(h.socket.closed && h.socket.cancelled)
        assertEquals(listOf("session.start", "session.input_audio.append", "session.close"), h.socket.sentTypes)
    }

    @Test fun providerErrorIsRedactedAndSocketAlwaysReleased() = runTest {
        val h = Harness(this)
        h.socket.sent.receive()
        h.socket.incoming.send("""{"type":"error","error":{"code":"invalid_api_key","message":"Bearer private-test-key SECRET"}}""")
        val error = assertIs<TranslationFailure>(h.result.await().exceptionOrNull())
        assertTrue(error.message.orEmpty().contains("認證"))
        assertFalse(error.message.orEmpty().contains("private-test-key"))
        assertEquals(0, h.readyCount)
        assertTrue(h.socket.closed && h.socket.cancelled)
    }

    @Test fun abruptDisconnectAndUnrequestedExpiryAreNotSuccessfulStops() = runTest {
        for (expired in listOf(false, true)) {
            val h = Harness(this)
            h.socket.sent.receive()
            h.socket.incoming.send(STARTED)
            runCurrent()
            if (expired) h.socket.incoming.send("""{"type":"session.closed","reason":"expired"}""") else h.socket.incoming.close()
            assertIs<TranslationFailure>(h.result.await().exceptionOrNull())
            assertTrue(h.socket.closed && h.socket.cancelled)
        }
    }

    @Test fun startupTimeoutDoesNotStartMicrophone() = runTest {
        val h = Harness(this)
        h.socket.sent.receive()
        advanceTimeBy(30_001)
        runCurrent()
        assertIs<TranslationFailure>(h.result.await().exceptionOrNull())
        assertEquals(0, h.readyCount)
        assertTrue(h.socket.cancelled)
    }

    @Test fun providerUsageRefreshesInactivityDeadlineWithoutSendingFakePing() = runTest {
        val h = Harness(this)
        h.socket.sent.receive()
        h.socket.incoming.send(STARTED)
        runCurrent()
        advanceTimeBy(80_000)
        h.socket.incoming.send("""{"type":"session.usage.updated","usage":{"seconds":80}}""")
        runCurrent()
        advanceTimeBy(89_000)
        assertFalse(h.result.isCompleted)
        advanceTimeBy(1_001)
        runCurrent()
        val error = assertIs<TranslationFailure>(h.result.await().exceptionOrNull())
        assertTrue(error.message.orEmpty().contains("90 秒"))
        assertEquals(listOf("session.start"), h.socket.sentTypes)
        assertTrue(h.socket.cancelled)
    }

    @Test fun stopTimeoutPreservesFailureAndClosesSocket() = runTest {
        val h = Harness(this)
        h.socket.sent.receive()
        h.socket.incoming.send(STARTED)
        runCurrent()
        h.audio.close()
        assertEquals(FOUNDRY_CLOSE_PAYLOAD, h.socket.sent.receive())
        advanceTimeBy(15_001)
        runCurrent()
        assertIs<TranslationFailure>(h.result.await().exceptionOrNull())
        assertTrue(h.socket.closed && h.socket.cancelled)
    }

    @Test fun invalidAudioAndNativeSendFailuresNeverLeakRawExceptions() = runTest {
        for (nativeFailure in listOf(false, true)) {
            val h = Harness(this)
            h.socket.sent.receive()
            h.socket.incoming.send(STARTED)
            runCurrent()
            if (nativeFailure) h.socket.sendFailure = IllegalStateException("SECRET private-test-key request")
            h.audio.send(if (nativeFailure) byteArrayOf(0, 0) else byteArrayOf(0))
            val failure = assertIs<TranslationFailure>(h.result.await().exceptionOrNull())
            assertFalse(failure.message.orEmpty().contains("SECRET"))
            assertFalse(failure.message.orEmpty().contains("private-test-key"))
            assertTrue(h.socket.closed && h.socket.cancelled)
        }
    }

    @Test fun cancellationReleasesNativeSocket() = runTest {
        val h = Harness(this)
        h.socket.sent.receive()
        h.socket.incoming.send(STARTED)
        runCurrent()
        h.job.cancelAndJoin()
        assertIs<CancellationException>(h.result.await().exceptionOrNull())
        assertTrue(h.socket.closed && h.socket.cancelled)
    }

    @Test fun invalidEndpointAndHeaderKeyNeverReachNativeSocket() = runTest {
        var opened = false
        val transport = KtorFoundryTransport(FoundrySocketFactory { _, _ -> opened = true; FakeFoundrySocket() })
        val audio = Channel<ByteArray>()
        for ((connection, key) in listOf(FoundryConnection("https://evil.test") to "key", FoundryConnection("https://sample.openai.azure.com") to "key\r\nHeader: x")) {
            val result = runCatching { transport.run(connection, key, SessionSettings(), audio, {}, {}) }
            assertIs<TranslationFailure>(result.exceptionOrNull())
        }
        assertFalse(opened)
    }
}
