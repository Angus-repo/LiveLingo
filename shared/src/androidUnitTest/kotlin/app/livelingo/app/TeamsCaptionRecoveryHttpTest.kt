package app.livelingo.app

import app.livelingo.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.test.*

class TeamsCaptionRecoveryHttpTest {
    private class Fixture(server: MockWebServer) {
        val values = ConcurrentHashMap<String, String>()
        val input = Channel<CapturedCaption>(Channel.UNLIMITED)
        var captureStarts = 0
        val services = object : PlatformServices {
            override val storage = object : LocalStorage {
                override fun read(key: String) = values[key]
                override fun write(key: String, value: String) { values[key] = value }
                override fun delete(key: String) { values.remove(key) }
                override fun readSecret(key: String) = read("secret-$key")
                override fun writeSecret(key: String, value: String) = write("secret-$key", value)
            }
            override val microphone = object : Microphone, TeamsCaptionInput {
                override fun availableSources() = listOf(AudioSource.TEAMS_CAPTIONS)
                override fun startCaptions(onHint: (String) -> Unit) = input.also { captureStarts++ }
                override fun selectCaptionRegion() = Unit
                override suspend fun start(onAudio: (ByteArray) -> Unit, onError: (String) -> Unit) = error("Unexpected microphone start")
                override fun stop() { input.close() }
            }
            override val exporter = object : TextExporter {
                override fun export(fileName: String, mimeType: String, text: String) = Unit
            }
            override fun currentTimeMillis() = System.currentTimeMillis()
            override fun uniqueId() = UUID.randomUUID().toString()
            override fun createHttpClient() = createAndroidFoundryHttpClient {
                addInterceptor { chain ->
                    val request = chain.request()
                    check(request.url.encodedPath == "/openai/v1/chat/completions")
                    chain.proceed(request.newBuilder().url(server.url(request.url.encodedPath)).build())
                }
            }
        }
    }

    @Test fun restoresTheMeetingAfterAnOfflineStopAndOnlySendsText() = runBlocking {
        val server = MockWebServer()
        server.start()
        val lane = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val fixture = Fixture(server)
        val services = fixture.services
        val input = fixture.input
        val repository = AppRepository(services.storage)
        val prefs = AppPreferences(audioSource = AudioSource.TEAMS_CAPTIONS, foundryEndpoint = "https://example.openai.azure.com")
        repository.savePreferences(prefs)
        repository.saveApiKey(prefs.connection().credentialEndpoint(), "test-key")
        try {
            withContext(lane) {
                withTimeout(30_000) {
                    val live = AppController(services, dispatcher = lane)
                    server.enqueue(MockResponse().setResponseCode(503))
                    try {
                        live.start()
                        live.state.first { it.phase == ConnectionPhase.LISTENING }
                        input.send(CapturedCaption("First sentence.", services.currentTimeMillis()))
                        live.state.first { it.recoveryStatus?.contains("自動補譯") == true }
                        input.send(CapturedCaption("Second sentence.", services.currentTimeMillis()))
                        live.stop()
                        live.state.first { !it.active }
                    } finally { live.dispose() }
                    val saved = repository.history().single()
                    assertEquals(listOf("First sentence.", "Second sentence."), saved.pendingCaptions().map { it.text.trim() })
                    assertFalse(fixture.values.keys.any { it.startsWith("pcm_") })
                    for (translation in listOf("第一句。", "第二句。")) {
                        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                            .setBody("""{"choices":[{"finish_reason":"stop","message":{"content":"$translation"}}]}"""))
                    }
                    val restored = AppController(services, dispatcher = lane)
                    try {
                        restored.resumeCaptions(saved.id)
                        restored.state.first { !it.active }
                        val finished = repository.history().single()
                        assertEquals(saved.id, finished.id)
                        assertTrue(finished.pendingCaptions().isEmpty())
                        assertEquals(listOf("第一句。", "第二句。"), finished.fragments.filter { it.channel == TranscriptChannel.OUTPUT }.map { it.text.trim() })
                        restored.resumeCaptions(saved.id)
                        assertEquals(3, server.requestCount)
                        assertEquals(1, fixture.captureStarts)
                    } finally { restored.dispose() }
                }
            }
        } finally { lane.close(); server.shutdown() }
    }
    @Test fun reopeningAMicrophoneTranscriptOnlyRetranslatesPendingTextAtItsOriginalTime() = runBlocking {
        val server = MockWebServer()
        server.start()
        val lane = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val fixture = Fixture(server)
        val prefs = AppPreferences(foundryEndpoint = "https://example.openai.azure.com")
        val repository = AppRepository(fixture.services.storage)
        val connection = prefs.connection()
        val pending = TranscriptFragment("missing", TranscriptChannel.INPUT, 100, 200, "Can we proceed?")
        repository.savePreferences(prefs)
        repository.saveApiKey(connection.credentialEndpoint(), "test-key")
        repository.saveTranscript(TranscriptRecord("original", "會議", 0, SessionSettings(), fragments = listOf(
            TranscriptFragment("next", TranscriptChannel.OUTPUT, 200, 300, "下一句。")),
            captionConnection = connection, pendingTranslations = listOf(pending)))
        fun enqueue(text: String) = server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"choices":[{"finish_reason":"stop","message":{"content":"$text"}}]}"""))
        try {
            withContext(lane) {
                withTimeout(30_000) {
                    enqueue("Can we proceed?")
                    enqueue("Can we proceed?")
                    enqueue("可以繼續嗎？")
                    repeat(2) { pass ->
                        val app = AppController(fixture.services, dispatcher = lane)
                        try {
                            app.resumeCaptions("original")
                            app.state.first { !it.active }
                            assertNull(app.state.value.sessionIssue)
                            val saved = repository.history().single()
                            if (pass == 0) assertEquals(listOf(pending), saved.pendingTranslations)
                            else {
                                assertTrue(saved.pendingTranslations.isEmpty())
                                assertEquals("可以繼續嗎？下一句。", liveCaptionText(saved, TranscriptChannel.OUTPUT))
                                assertEquals(100L, saved.fragments.single { it.id == "translation-missing" }.startMs)
                                app.resumeCaptions(saved.id)
                            }
                        } finally { app.dispose() }
                    }
                    assertEquals(3, server.requestCount)
                    assertEquals(0, fixture.captureStarts)
                }
            }
        } finally { lane.close(); server.shutdown() }
    }

}
