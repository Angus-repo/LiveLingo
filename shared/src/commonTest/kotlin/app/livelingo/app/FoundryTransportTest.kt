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
    var closeCode: Int? = null
    override suspend fun peerCloseCode() = closeCode
    override suspend fun send(payload: String) {
        sendFailure?.let { throw it }
        sentTypes += WireJson.parseToJsonElement(payload).jsonObject.getValue("type").jsonPrimitive.content
        sent.send(payload)
    }
    override suspend fun receive(): String? {
        val result = incoming.receiveCatching()
        result.exceptionOrNull()?.let { throw it }
        return result.getOrNull()
    }
    override suspend fun close() { closed = true }
    override fun cancel() { cancelled = true }
}

private class Harness(scope: CoroutineScope, val socket: FakeFoundrySocket = FakeFoundrySocket()) {
    val audio = Channel<ByteArray>(10)
    val result = CompletableDeferred<Result<Unit>>()
    val transcripts = mutableListOf<TranscriptDelta>()
    val notices = mutableListOf<TranslationNotice>()
    val usages = mutableListOf<UsageSample>()
    var readyCount = 0
    var url: String? = null
    var apiKey: String? = null
    val transport = KtorFoundryTransport(FoundrySocketFactory { target, key -> url = target; apiKey = key; socket })
    val job = scope.launch {
        transport.setUsageListener { usages += it }
        result.complete(runCatching {
            transport.run(FoundryConnection("https://Sample.openai.azure.com/openai/v1/", "custom-live"), "private-test-key", SessionSettings(), audio,
                onReady = { readyCount++ }, onTranscript = { transcripts += it }, onNotice = { notices += it })
        })
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FoundryTransportTest {
    @Test fun finalUsageOnContentClosureIsCapturedBeforeErrorAndCumulativeEventsReplace() = runTest {
        val h = Harness(this)
        h.socket.sent.receive(); h.socket.incoming.send(STARTED); runCurrent()
        h.socket.incoming.send("""{"type":"session.usage.updated","usage":{"seconds":60}}""")
        h.socket.incoming.send("""{"type":"session.usage.updated","usage":{"seconds":60}}""")
        h.socket.incoming.send("""{"type":"session.closed","reason":"content","usage":{"seconds":61.5}}""")
        assertIs<TranslationFailure>(h.result.await().exceptionOrNull())
        val last = h.usages.last()
        assertTrue(last.final)
        assertFalse(last.estimated)
        assertEquals(61.5, last.seconds)
        val result = h.usages.fold(MeetingUsage("m", "m", 0, ModelProvider.OPENAI, "gpt-live-1", "", CostRates(voicePerMinute = 0.05))) { m, u -> m.update(u) }
        assertEquals(0.05125, result.total().usd, 1e-12)
        assertTrue(h.socket.closed && h.socket.cancelled)
    }

    @Test fun transportCloseWithoutFinalUsageKeepsReportedDurationAsLowerBound() = runTest {
        val h = Harness(this)
        h.socket.sent.receive(); h.socket.incoming.send(STARTED); runCurrent()
        h.socket.incoming.send("""{"type":"session.usage.updated","usage":{"seconds":60}}""")
        h.socket.incoming.close()
        assertIs<TranslationFailure>(h.result.await().exceptionOrNull())
        assertTrue(h.usages.last().estimated)
        assertTrue(h.usages.last().seconds >= 60.0)
    }

    @Test fun contentFilterDuringStreamingDoesNotDisconnectOrReplayAndLaterCaptionsContinue() = runTest {
        val h = Harness(this)
        h.socket.sent.receive()
        h.socket.incoming.send(STARTED); runCurrent()
        h.socket.incoming.send("""{"type":"error","error":{"code":"content_filter","message":"private-test-key private transcript"}}""")
        runCurrent()
        assertFalse(h.result.isCompleted)
        assertFalse(h.socket.closed)
        assertEquals("CONTENT_FILTER", h.notices.single().diagnostic.code)
        assertFalse(h.notices.single().message.contains("private"))
        h.audio.send(byteArrayOf(1, 0)); runCurrent()
        h.socket.incoming.send("""{"type":"session.output_transcript.delta","delta":"下一句正常翻譯","start_ms":2000,"end_ms":2500}""")
        runCurrent()
        assertEquals("下一句正常翻譯", h.transcripts.single().text)
        h.audio.close(); runCurrent()
        h.socket.incoming.send(CLOSED)
        assertTrue(h.result.await().isSuccess)
        assertEquals(listOf("session.start", "session.input_audio.append", "session.close"), h.socket.sentTypes)
        assertTrue(h.socket.closed && h.socket.cancelled)
    }

    @Test fun contentFilterBeforeReadyIsFatalAndNeverStartsAudio() = runTest {
        val h = Harness(this)
        h.socket.sent.receive()
        h.socket.incoming.send("""{"type":"error","error":{"code":"content_filter"}}""")
        val failure = assertIs<TranslationFailure>(h.result.await().exceptionOrNull())
        assertEquals("CONTENT_FILTER", failure.diagnostic?.code)
        assertEquals(FoundryStages.START, failure.diagnostic?.stage)
        assertEquals(0, h.readyCount)
        assertTrue(h.notices.isEmpty())
        assertTrue(h.socket.cancelled)
    }

    @Test fun contentClosureIsFatalWithOrWithoutEarlierWarning() = runTest {
        for (warningFirst in listOf(true, false)) {
            val h = Harness(this)
            h.socket.sent.receive()
            h.socket.incoming.send(STARTED); runCurrent()
            h.socket.incoming.send("""{"type":"session.input_transcript.delta","delta":"already received","start_ms":0,"end_ms":500}""")
            if (warningFirst) h.socket.incoming.send("""{"type":"error","error":{"code":"content_filter"}}""")
            h.socket.incoming.send("""{"type":"session.closed","reason":"content"}""")
            val failure = assertIs<TranslationFailure>(h.result.await().exceptionOrNull())
            assertEquals("CONTENT_FILTER", failure.diagnostic?.code)
            assertEquals("content_filter", failure.diagnostic?.providerCode)
            assertEquals("content", failure.diagnostic?.sessionCloseReason)
            assertEquals(1, h.notices.size)
            assertEquals("already received", h.transcripts.single().text)
            assertTrue(h.socket.closed && h.socket.cancelled)
            assertEquals(1, h.socket.sentTypes.count { it == "session.start" })
        }
    }

    @Test fun disconnectImmediatelyAfterFilterRetainsTheRelevantDiagnosis() = runTest {
      for (nativeError in listOf(false, true)) {
        val h = Harness(this)
        h.socket.sent.receive()
        h.socket.incoming.send(STARTED); runCurrent()
        h.socket.incoming.send("""{"type":"error","error":{"code":"content_filter"}}""")
        h.socket.closeCode = 1008
        h.socket.incoming.close(if (nativeError) IllegalStateException("private-test-key in native error") else null)
        val failure = assertIs<TranslationFailure>(h.result.await().exceptionOrNull())
        assertEquals("DISCONNECTED_AFTER_FILTER", failure.diagnostic?.code)
        assertEquals(1008, failure.diagnostic?.webSocketCloseCode)
        assertNull(failure.diagnostic?.sessionCloseReason)
        assertTrue(failure.message.orEmpty().contains("斷線"))
        assertFalse(failure.message.orEmpty().contains("private"))
        assertTrue(h.socket.cancelled)
      }
    }

    @Test fun recoveredFilterIsNotMisreportedAsTheCauseOfALaterNetworkClose() = runTest {
        val h = Harness(this)
        h.socket.sent.receive()
        h.socket.incoming.send(STARTED); runCurrent()
        h.socket.incoming.send("""{"type":"error","error":{"code":"content_filter"}}""")
        h.socket.incoming.send("""{"type":"session.output_transcript.delta","delta":"已恢復","start_ms":0,"end_ms":500}""")
        h.socket.closeCode = 1001
        h.socket.incoming.close()
        val failure = assertIs<TranslationFailure>(h.result.await().exceptionOrNull())
        assertEquals("CONNECTION_CLOSED", failure.diagnostic?.code)
        assertEquals(1001, failure.diagnostic?.webSocketCloseCode)
        assertNull(failure.diagnostic?.providerCode)
        assertEquals("已恢復", h.transcripts.single().text)
    }

    @Test fun arbitrarySessionCloseTextIsNeverIncludedInDiagnostics() = runTest {
        val h = Harness(this)
        h.socket.sent.receive()
        h.socket.incoming.send(STARTED); runCurrent()
        h.socket.incoming.send("""{"type":"session.closed","reason":"private-test-key SECRET speech"}""")
        val failure = assertIs<TranslationFailure>(h.result.await().exceptionOrNull())
        assertNull(failure.diagnostic?.sessionCloseReason)
        assertFalse("SECRET" in failure.toString())
        assertFalse("private" in failure.toString())
    }

    @Test fun waitsForStartedThenSendsPcmAndDrainsFinalCaptionsAfterClose() = runTest {
        val h = Harness(this)
        val start = WireJson.parseToJsonElement(h.socket.sent.receive()).jsonObject
        assertEquals("session.start", start.getValue("type").jsonPrimitive.content)
        assertEquals("custom-live", start.getValue("session").jsonObject.getValue("model").jsonPrimitive.content)
        assertEquals("wss://sample.openai.azure.com/openai/v1/live/sessions", h.url)
        assertEquals("private-test-key", h.apiKey)
        assertFalse(h.url.orEmpty().contains('?'))
        assertFalse(start.toString().contains("private-test-key"))
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

    @Test fun probeStartsAndClosesTheActualModelWithoutSendingAudio() = runTest {
        val socket = FakeFoundrySocket()
        val transport = KtorFoundryTransport(FoundrySocketFactory { _, _ -> socket })
        val result = CompletableDeferred<Result<ConnectionProbeResult>>()
        launch { result.complete(runCatching { transport.probe(FoundryConnection("https://sample.openai.azure.com", "live-test"), "private-key") }) }
        val start = WireJson.parseToJsonElement(socket.sent.receive()).jsonObject
        assertEquals("live-test", start.getValue("session").jsonObject.getValue("model").jsonPrimitive.content)
        socket.incoming.send(STARTED)
        assertEquals(FOUNDRY_CLOSE_PAYLOAD, socket.sent.receive())
        socket.incoming.send(CLOSED)
        val success = result.await().getOrThrow()
        assertEquals("OK", success.diagnostic.code)
        assertEquals(101, success.diagnostic.httpStatus)
        assertEquals(listOf("session.start", "session.close"), socket.sentTypes)
        assertTrue(socket.closed && socket.cancelled)
    }

    @Test fun failedProbeHasStartupStageAndSafeProviderCode() = runTest {
        val socket = FakeFoundrySocket()
        val transport = KtorFoundryTransport(FoundrySocketFactory { _, _ -> socket })
        val result = CompletableDeferred<Result<ConnectionProbeResult>>()
        launch { result.complete(runCatching { transport.probe(FoundryConnection("https://sample.openai.azure.com"), "private-key") }) }
        socket.sent.receive()
        socket.incoming.send("""{"type":"error","error":{"code":"DeploymentNotFound","message":"private-key"}}""")
        val failure = assertIs<TranslationFailure>(result.await().exceptionOrNull())
        assertEquals(FoundryStages.START, failure.diagnostic?.stage)
        assertEquals("DeploymentNotFound", failure.diagnostic?.providerCode)
        assertFalse(failure.message.orEmpty().contains("private-key"))
        assertTrue(socket.cancelled)
    }

    @Test fun handshakeFailureDoesNotLeakIntoLaterDiagnostic() = runTest {
        val echoedKey = "abcdef0123456789abcdef0123456789"
        var attempt = 0
        val transport = KtorFoundryTransport(FoundrySocketFactory { _, _ ->
            if (++attempt == 1) throw object : Exception("secret"), FoundryHttpFailure {
                override val statusCode = 401
                override val providerCode = "Unauthorized"
                override val requestId = echoedKey
            }
            throw IllegalStateException("private-key cannot connect")
        })
        val first = assertIs<TranslationFailure>(runCatching { transport.probe(FoundryConnection("https://sample.openai.azure.com"), echoedKey) }.exceptionOrNull())
        assertEquals(401, first.diagnostic?.httpStatus)
        assertNull(first.diagnostic?.requestId) // Even a syntactically valid ID must not reveal the active key.
        val second = assertIs<TranslationFailure>(runCatching { transport.probe(FoundryConnection("https://sample.openai.azure.com"), "private-key") }.exceptionOrNull())
        assertEquals("NETWORK_UNKNOWN", second.diagnostic?.code)
        assertNull(second.diagnostic?.httpStatus)
        assertNull(second.diagnostic?.requestId)
    }

    @Test fun stalledSessionStartSendIsBoundedAndReleased() = runTest {
        var cancelled = false
        val socket = object : FoundrySocket {
            override suspend fun send(payload: String) { awaitCancellation() }
            override suspend fun receive(): String? = awaitCancellation()
            override suspend fun close() {}
            override fun cancel() { cancelled = true }
        }
        val transport = KtorFoundryTransport(FoundrySocketFactory { _, _ -> socket }, startupTimeoutMillis = 1_000)
        val result = CompletableDeferred<Result<ConnectionProbeResult>>()
        launch { result.complete(runCatching { transport.probe(FoundryConnection("https://sample.openai.azure.com"), "private-key") }) }
        advanceTimeBy(1_001)
        runCurrent()
        val failure = assertIs<TranslationFailure>(result.await().exceptionOrNull())
        assertEquals("TIMEOUT", failure.diagnostic?.code)
        assertEquals(FoundryStages.START, failure.diagnostic?.stage)
        assertTrue(cancelled)
    }
}
