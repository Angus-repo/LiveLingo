@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.livelingo.app

import app.livelingo.core.*
import io.ktor.client.HttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlin.test.*

class AppLogicTest {
    @Test fun onlyValidatedFoundryResourcesAreAccepted() {
        assertNull(FoundryConnection("https://example.openai.azure.com").validationError())
        listOf("ws://example.openai.azure.com", "https://example.com", "https://example.openai.azure.com?key=secret")
            .forEach { assertNotNull(FoundryConnection(it).validationError(), it) }
    }

    @Test fun apiKeyNeverAppearsInOrdinaryAppFilesAndIsBoundToItsEndpoint() {
        val storage = MemoryStorage()
        val repository = AppRepository(storage)
        val canonical = FoundryConnection("https://example.openai.azure.com").webSocketUrl()
        repository.saveApiKey(canonical, "provider-secret")
        repository.savePreferences(AppPreferences(foundryEndpoint = "https://example.openai.azure.com"))
        assertEquals("provider-secret", repository.apiKey(canonical))
        assertEquals("", repository.apiKey(FoundryConnection("https://other.openai.azure.com").webSocketUrl()))
        assertTrue(storage.regular.values.none { "provider-secret" in it })
    }

    @Test fun transcriptAndKnowledgeAreRoundTrippedIndependently() {
        val repository = AppRepository(MemoryStorage())
        val originalKnowledge = BackgroundKnowledge("meeting", "原背景", "原內容")
        val record = TranscriptRecord("r1", "會議", 1000, SessionSettings(knowledge = originalKnowledge),
            listOf(TranscriptFragment("f1", TranscriptChannel.INPUT, 50, 100, "你好", "王先生")))
        repository.saveTranscript(record)
        repository.saveKnowledge(listOf(originalKnowledge.copy(content = "已修改")))
        assertEquals("原內容", repository.history().single().settings.knowledge?.content)
        assertEquals("王先生", repository.history().single().fragments.single().speakerLabel)
        repository.deleteTranscript("r1")
        assertTrue(repository.history().isEmpty())
    }

    @Test fun corruptStorageDoesNotGetSilentlyOverwritten() = runTest {
        val services = FakeServices()
        services.memory.regular["preferences"] = "broken JSON"
        val controller = AppController(services, FakeTransport(), StandardTestDispatcher(testScheduler))
        assertFalse(controller.state.value.storageAvailable)
        controller.start()
        controller.saveConnection("https://example.openai.azure.com", "gpt-live-1", "fresh-key")
        assertEquals("broken JSON", services.memory.regular["preferences"])
        assertFalse(services.mic.recording)
        controller.dispose()
    }

    @Test fun foregroundMicrophonePreparationCompletesBeforeFoundryHandshake() = runTest {
        val services = configuredServices()
        val gate = CompletableDeferred<Unit>()
        services.mic.prepareGate = gate
        val transport = FakeTransport()
        val controller = AppController(services, transport, StandardTestDispatcher(testScheduler))
        controller.start()
        runCurrent()
        assertEquals(1, services.mic.prepares)
        assertEquals(0, transport.starts) // Android foreground service must be prepared first.
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, transport.starts)
        assertEquals(1, services.mic.starts)
        controller.stop()
        runCurrent()
        controller.dispose()
    }

    @Test fun microphonePreparationFailureNeverConnectsToFoundry() = runTest {
        val services = configuredServices()
        services.mic.prepareFailure = IllegalStateException("foreground service unavailable")
        val transport = FakeTransport()
        val controller = AppController(services, transport, StandardTestDispatcher(testScheduler))
        controller.start()
        runCurrent()
        assertEquals(ConnectionPhase.ERROR, controller.state.value.phase)
        assertEquals(0, transport.starts)
        assertEquals(0, services.mic.starts)
        assertTrue(controller.state.value.message.orEmpty().contains("背景翻譯麥克風"))
        controller.dispose()
    }

    @Test fun microphoneWaitsForReadyAndDuplicateStartDoesNotCreateTwoSessions() = runTest {
        val services = configuredServices()
        val transport = FakeTransport(ready = CompletableDeferred())
        val controller = AppController(services, transport, StandardTestDispatcher(testScheduler))
        controller.start()
        controller.start()
        runCurrent()
        assertEquals(1, transport.starts)
        assertFalse(services.mic.recording)
        assertEquals(ConnectionPhase.CONNECTING, controller.state.value.phase)
        transport.ready.complete(Unit)
        runCurrent()
        assertTrue(services.mic.recording)
        controller.stop()
        runCurrent()
        controller.dispose()
    }

    @Test fun stopFlushesFinalCapturedAudioBeforeClosingTheQueue() = runTest {
        val services = configuredServices()
        services.mic.firstChunk = byteArrayOf(1, 0)
        services.mic.finalChunk = byteArrayOf(2, 0)
        val transport = FakeTransport()
        val controller = AppController(services, transport, StandardTestDispatcher(testScheduler))
        controller.start()
        runCurrent()
        assertEquals(ConnectionPhase.LISTENING, controller.state.value.phase)
        controller.stop()
        runCurrent()
        assertEquals(2, transport.audio.size)
        assertContentEquals(byteArrayOf(1, 0), transport.audio[0])
        assertContentEquals(byteArrayOf(2, 0), transport.audio[1])
        assertFalse(services.mic.recording)
        assertEquals(ConnectionPhase.IDLE, controller.state.value.phase)
        controller.dispose()
    }

    @Test fun stopWhileConnectingNeverStartsMicrophoneAfterLateReady() = runTest {
        val services = configuredServices()
        val transport = FakeTransport(ready = CompletableDeferred())
        val controller = AppController(services, transport, StandardTestDispatcher(testScheduler))
        controller.start()
        runCurrent()
        controller.stop()
        transport.ready.complete(Unit)
        runCurrent()
        assertFalse(services.mic.recording)
        assertEquals(0, services.mic.starts)
        controller.dispose()
    }

    @Test fun microphoneInterruptionStopsSessionAndPreservesError() = runTest {
        val services = configuredServices()
        val controller = AppController(services, FakeTransport(), StandardTestDispatcher(testScheduler))
        controller.start()
        runCurrent()
        services.mic.fail("麥克風被其他 App 中斷")
        runCurrent()
        assertFalse(services.mic.recording)
        assertEquals(ConnectionPhase.ERROR, controller.state.value.phase)
        assertEquals("麥克風被其他 App 中斷", controller.state.value.message)
        assertEquals(1, AppRepository(services.memory).history().size)
        controller.dispose()
    }

    @Test fun deniedPermissionReportsMicrophoneProblemAndReleasesCapture() = runTest {
        val services = configuredServices()
        services.mic.startFailure = IllegalStateException("permission denied")
        val controller = AppController(services, FakeTransport(), StandardTestDispatcher(testScheduler))
        controller.start()
        runCurrent()
        assertEquals(ConnectionPhase.ERROR, controller.state.value.phase)
        assertTrue(controller.state.value.message.orEmpty().contains("麥克風權限"))
        assertFalse(controller.state.value.message.orEmpty().contains("API key"))
        assertFalse(services.mic.recording)
        controller.dispose()
    }

    @Test fun disposalReleasesMicrophoneAndNetworkClient() = runTest {
        val services = configuredServices()
        val transport = FakeTransport()
        val controller = AppController(services, transport, StandardTestDispatcher(testScheduler))
        controller.start()
        runCurrent()
        controller.dispose()
        runCurrent()
        assertFalse(services.mic.recording)
        assertTrue(transport.closed)
    }

    @Test fun upgradingRetainsAllUserContentAndNeverPromotesLegacyCredential() {
        val services = FakeServices()
        val repository = AppRepository(services.memory)
        val knowledge = BackgroundKnowledge("custom", "保留背景", "保留內容")
        repository.saveKnowledge(listOf(knowledge))
        repository.saveTranscript(TranscriptRecord("old-record", "既有逐字稿", 123,
            SessionSettings(Language.INDIAN_ENGLISH, Language.ZH_TW, knowledge)))
        services.memory.regular["preferences"] = """{"relayUrl":"wss://old.example/translate","source":"INDIAN_ENGLISH","target":"ZH_TW","knowledgeId":"custom"}"""
        services.memory.secrets["relay_token"] = "legacy-secret-must-not-be-used"
        val controller = AppController(services, FakeTransport(), StandardTestDispatcher())
        assertTrue(controller.state.value.storageAvailable)
        assertEquals(Language.INDIAN_ENGLISH, controller.state.value.preferences.source)
        assertEquals("custom", controller.state.value.preferences.knowledgeId)
        assertEquals(listOf(knowledge), controller.state.value.knowledge)
        assertEquals("old-record", controller.state.value.history.single().id)
        assertEquals("", controller.state.value.preferences.foundryEndpoint)
        assertFalse(controller.state.value.hasApiKey)
        assertNull(services.memory.secrets["relay_token"])
        assertNull(services.memory.secrets["foundry_api_key"])
        assertFalse(services.memory.regular.getValue("preferences").contains("relayUrl"))
        controller.dispose()
    }

    @Test fun sameResourceCanRetainKeyAndChangeDeploymentWithoutLengthRequirement() {
        val services = configuredServices()
        val controller = AppController(services, FakeTransport(), StandardTestDispatcher())
        assertTrue(controller.saveConnection("https://example.openai.azure.com/openai/v1/", "my-live-deployment", ""))
        assertEquals("my-live-deployment", controller.state.value.preferences.deployment)
        assertEquals("short-key", AppRepository(services.memory).apiKey(FoundryConnection("https://example.openai.azure.com").webSocketUrl()))
        controller.dispose()
    }

    @Test fun changingResourceRequiresFreshKeyAndSendsOnlyItsBoundCredential() = runTest {
        val services = configuredServices()
        val transport = FakeTransport()
        val controller = AppController(services, transport, StandardTestDispatcher(testScheduler))
        assertFalse(controller.saveConnection("https://other.openai.azure.com", "gpt-live-1", ""))
        assertEquals("https://example.openai.azure.com", controller.state.value.preferences.foundryEndpoint)
        assertTrue(controller.saveConnection("https://other.openai.azure.com", "gpt-live-1", "new-key"))
        controller.start()
        runCurrent()
        assertEquals("https://other.openai.azure.com", transport.connections.single().endpoint)
        assertEquals(listOf("new-key"), transport.keys)
        controller.stop()
        runCurrent()
        controller.dispose()
    }

    @Test fun replacementAndRemovalPersistWithoutExposingKeyInSettings() {
        val services = configuredServices()
        val controller = AppController(services, FakeTransport(), StandardTestDispatcher())
        assertTrue(controller.saveConnection("https://example.openai.azure.com", "gpt-live-1", "replacement"))
        assertTrue(controller.state.value.hasApiKey)
        assertTrue(services.memory.regular.values.none { "replacement" in it })
        controller.dispose()
        val reopened = AppController(services, FakeTransport(), StandardTestDispatcher())
        assertTrue(reopened.state.value.hasApiKey)
        reopened.clearApiKey()
        assertFalse(reopened.state.value.hasApiKey)
        assertNull(services.memory.secrets["foundry_api_key"])
        reopened.dispose()
    }

    @Test fun providerKeyRejectsControlCharactersAndExplicitWhitespace() {
        val services = configuredServices()
        val controller = AppController(services, FakeTransport(), StandardTestDispatcher())
        listOf("new\nkey", "new\rkey", "new\u0000key", "   ", "new key").forEach {
            assertFalse(controller.saveConnection("https://example.openai.azure.com", "gpt-live-1", it))
        }
        assertEquals("short-key", AppRepository(services.memory).apiKey(FoundryConnection("https://example.openai.azure.com").webSocketUrl()))
        controller.dispose()
    }

    @Test fun failedPreferencesWriteCannotAssociateNewKeyWithPreviousResource() = runTest {
        val services = configuredServices()
        val transport = FakeTransport()
        val controller = AppController(services, transport, StandardTestDispatcher(testScheduler))
        services.memory.failPreferencesWrite = true
        assertFalse(controller.saveConnection("https://other.openai.azure.com", "gpt-live-1", "other-secret"))
        assertEquals("https://example.openai.azure.com", controller.state.value.preferences.foundryEndpoint)
        assertFalse(controller.state.value.hasApiKey)
        controller.start()
        runCurrent()
        assertEquals(0, transport.starts)
        assertFalse(services.mic.recording)
        controller.dispose()
    }

    @Test fun unreadableKeyDoesNotLockHistoryAndCanBeReplaced() {
        val services = configuredServices()
        val repository = AppRepository(services.memory)
        repository.saveTranscript(TranscriptRecord("retained", "保留會議", 123, SessionSettings()))
        services.memory.failFoundryKeyRead = true
        val controller = AppController(services, FakeTransport(), StandardTestDispatcher())
        assertTrue(controller.state.value.storageAvailable)
        assertFalse(controller.state.value.hasApiKey)
        assertEquals("retained", controller.state.value.history.single().id)
        assertTrue(controller.state.value.knowledge.isNotEmpty())
        assertTrue(controller.state.value.message.orEmpty().contains("重新輸入 API key"))
        controller.clearApiKey()
        assertNull(services.memory.secrets["foundry_api_key"])
        services.memory.failFoundryKeyRead = false
        assertTrue(controller.saveConnection("https://example.openai.azure.com", "gpt-live-1", "recovered-key"))
        assertTrue(controller.state.value.hasApiKey)
        assertEquals("retained", controller.state.value.history.single().id)
        controller.dispose()
    }

    @Test fun connectionProbeUsesFormWithoutRecordingKnowledgeOrSavingSettings() = runTest {
        val services = configuredServices()
        val repository = AppRepository(services.memory)
        val originalPreferences = repository.preferences().copy(knowledgeId = "banking")
        repository.savePreferences(originalPreferences)
        val transport = FakeTransport()
        val controller = AppController(services, transport, StandardTestDispatcher(testScheduler))
        controller.testConnection("https://other.openai.azure.com", "trial-deployment", "trial-key")
        runCurrent()
        assertEquals(1, transport.probes)
        assertEquals("https://other.openai.azure.com", transport.probeConnections.single().endpoint)
        assertEquals("trial-deployment", transport.probeConnections.single().deployment)
        assertEquals("trial-key", transport.probeKeys.single())
        assertEquals(0, transport.starts)
        assertEquals(0, services.mic.starts)
        assertTrue(repository.history().isEmpty())
        assertEquals(originalPreferences, repository.preferences())
        assertEquals("short-key", repository.apiKey(FoundryConnection(originalPreferences.foundryEndpoint).webSocketUrl()))
        assertFalse(controller.state.value.connectionTesting)
        assertTrue(controller.state.value.lastConnectionReport.orEmpty().contains("結果：成功"))
        assertFalse(controller.state.value.lastConnectionReport.orEmpty().contains("trial-key"))
        controller.dispose()
    }

    @Test fun probeReusesStoredKeyOnlyForSameCanonicalResource() = runTest {
        val services = configuredServices()
        val transport = FakeTransport()
        val controller = AppController(services, transport, StandardTestDispatcher(testScheduler))
        controller.testConnection("https://other.openai.azure.com", "gpt-live-1", "")
        runCurrent()
        assertEquals(0, transport.probes)
        controller.testConnection("https://EXAMPLE.openai.azure.com/openai/v1/", "gpt-live-1", "")
        runCurrent()
        assertEquals(1, transport.probes)
        assertEquals("short-key", transport.probeKeys.single())
        controller.dispose()
    }

    @Test fun structuredProbeFailureProducesShareableRedactedDiagnostics() = runTest {
        val services = configuredServices()
        val transport = FakeTransport().apply {
            probeFailure = TranslationFailure("認證失敗 short-key", FoundryDiagnostic("WebSocket 握手", "HTTP_401", 401,
                requestId = "request-short-key", providerCode = "invalid_api_key"))
        }
        val controller = AppController(services, transport, StandardTestDispatcher(testScheduler))
        controller.testConnection("https://example.openai.azure.com", "gpt-live-1", "")
        runCurrent()
        val report = assertNotNull(controller.state.value.lastConnectionReport)
        assertTrue(report.contains("HTTP 狀態：401"))
        assertTrue(report.contains("WebSocket 握手"))
        assertTrue(report.contains("invalid_api_key"))
        assertTrue(report.contains("Request ID：request-[已遮蔽]"))
        assertFalse(report.contains("short-key"))
        assertFalse(report.contains("example.openai.azure.com"))
        assertFalse(report.contains("gpt-live-1"))
        assertFalse(controller.state.value.message.orEmpty().contains("short-key"))
        assertTrue(controller.state.value.showReportInMessage)
        controller.exportConnectionReport()
        assertEquals(report, services.exports.single())
        assertEquals(0, services.mic.starts)
        controller.dispose()
    }

    @Test fun unexpectedProbeExceptionNeverAppearsInDiagnosticReport() = runTest {
        val services = configuredServices()
        val transport = FakeTransport().apply { probeFailure = IllegalStateException("Authorization Bearer short-key RAW_EXCEPTION") }
        val controller = AppController(services, transport, StandardTestDispatcher(testScheduler))
        controller.testConnection("https://example.openai.azure.com", "gpt-live-1", "")
        runCurrent()
        val report = assertNotNull(controller.state.value.lastConnectionReport)
        assertTrue(report.contains("UNKNOWN"))
        assertFalse(report.contains("short-key"))
        assertFalse(report.contains("RAW_EXCEPTION"))
        controller.dispose()
    }

    @Test fun cancelledProbeRemainsBusyUntilSocketCleanupAndCannotOverlapRecording() = runTest {
        val services = configuredServices()
        val cleanup = CompletableDeferred<Unit>()
        val transport = FakeTransport().apply {
            probeReady = CompletableDeferred()
            probeCleanup = cleanup
        }
        val controller = AppController(services, transport, StandardTestDispatcher(testScheduler))
        controller.testConnection("https://example.openai.azure.com", "gpt-live-1", "")
        runCurrent()
        controller.cancelConnectionTest()
        runCurrent()
        assertTrue(transport.probeInCleanup)
        assertTrue(controller.state.value.connectionTesting)
        controller.start()
        controller.testConnection("https://example.openai.azure.com", "gpt-live-1", "")
        runCurrent()
        assertEquals(0, transport.starts)
        assertEquals(1, transport.probes)
        assertEquals(0, services.mic.starts)
        cleanup.complete(Unit)
        runCurrent()
        assertFalse(controller.state.value.connectionTesting)
        controller.start()
        runCurrent()
        assertEquals(1, transport.starts)
        controller.stop()
        runCurrent()
        controller.dispose()
    }

    @Test fun recordingConnectionFailureAlsoShowsStructuredDiagnostics() = runTest {
        val services = configuredServices()
        val transport = FakeTransport().apply {
            runFailure = TranslationFailure("找不到此路徑。", FoundryDiagnostic("WebSocket 握手", "HTTP_404", 404))
        }
        val controller = AppController(services, transport, StandardTestDispatcher(testScheduler))
        controller.start()
        runCurrent()
        assertEquals(ConnectionPhase.ERROR, controller.state.value.phase)
        assertTrue(controller.state.value.lastConnectionReport.orEmpty().contains("HTTP 狀態：404"))
        assertTrue(controller.state.value.showReportInMessage)
        assertEquals(0, services.mic.starts)
        controller.clearApiKey()
        assertFalse(controller.state.value.showReportInMessage)
        controller.dispose()
    }

    private fun configuredServices() = FakeServices().also {
        val repository = AppRepository(it.memory)
        repository.savePreferences(AppPreferences(foundryEndpoint = "https://example.openai.azure.com"))
        repository.saveApiKey(FoundryConnection("https://example.openai.azure.com").webSocketUrl(), "short-key")
    }

}

private class MemoryStorage : LocalStorage {
    val regular = mutableMapOf<String, String>()
    val secrets = mutableMapOf<String, String>()
    var failPreferencesWrite = false
    var failFoundryKeyRead = false
    override fun read(key: String) = regular[key]
    override fun write(key: String, value: String) {
        if (key == "preferences" && failPreferencesWrite) throw IllegalStateException("simulated disk failure")
        regular[key] = value
    }
    override fun delete(key: String) { regular.remove(key) }
    override fun readSecret(key: String): String? {
        if (key == "foundry_api_key" && failFoundryKeyRead) throw IllegalStateException("simulated decryption failure")
        return secrets[key]
    }
    override fun writeSecret(key: String, value: String) { if (value.isEmpty()) secrets.remove(key) else secrets[key] = value }
}

private class FakeMic : Microphone {
    var recording = false
    var starts = 0
    var prepares = 0
    var prepareGate: CompletableDeferred<Unit>? = null
    var prepareFailure: Exception? = null
    var firstChunk: ByteArray? = null
    var finalChunk: ByteArray? = null
    var startFailure: Exception? = null
    private var onAudio: ((ByteArray) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    override suspend fun prepare() {
        prepares++
        prepareFailure?.let { throw it }
        prepareGate?.await()
    }
    override suspend fun start(onAudio: (ByteArray) -> Unit, onError: (String) -> Unit) {
        starts++
        startFailure?.let { throw it }
        this.onAudio = onAudio
        this.onError = onError
        recording = true
        firstChunk?.let(onAudio)
    }
    override fun stop() {
        if (!recording) return
        finalChunk?.let { onAudio?.invoke(it) }
        recording = false
    }
    fun fail(message: String) { onError?.invoke(message) }
}

private class FakeServices : PlatformServices {
    val memory = MemoryStorage()
    val mic = FakeMic()
    val exports = mutableListOf<String>()
    private var sequence = 0
    override val storage: LocalStorage = memory
    override val microphone: Microphone = mic
    override val exporter = object : TextExporter { override fun export(fileName: String, mimeType: String, text: String) { exports += text } }
    override fun currentTimeMillis() = 123456L
    override fun uniqueId() = "test-${++sequence}"
    override fun createHttpClient(): HttpClient = error("Fake transport must be supplied")
}

private class FakeTransport(val ready: CompletableDeferred<Unit> = CompletableDeferred(Unit)) : TranslationTransport {
    var starts = 0
    var closed = false
    val audio = mutableListOf<ByteArray>()
    val connections = mutableListOf<FoundryConnection>()
    val keys = mutableListOf<String>()
    var runFailure: Exception? = null
    var probes = 0
    val probeConnections = mutableListOf<FoundryConnection>()
    val probeKeys = mutableListOf<String>()
    var probeFailure: Exception? = null
    var probeReady = CompletableDeferred(Unit)
    var probeCleanup: CompletableDeferred<Unit>? = null
    var probeInCleanup = false
    override suspend fun run(connection: FoundryConnection, apiKey: String, settings: SessionSettings, audio: ReceiveChannel<ByteArray>, onReady: suspend () -> Unit, onTranscript: suspend (TranscriptDelta) -> Unit) {
        starts++
        connections += connection
        keys += apiKey
        runFailure?.let { throw it }
        ready.await()
        onReady()
        for (chunk in audio) this.audio += chunk
    }
    override suspend fun probe(connection: FoundryConnection, apiKey: String): ConnectionProbeResult {
        probes++
        probeConnections += connection
        probeKeys += apiKey
        try {
            probeReady.await()
            probeFailure?.let { throw it }
            return ConnectionProbeResult("Foundry 連線與部署測試成功。", FoundryDiagnostic("完成", "OK", 101))
        } finally {
            probeCleanup?.let { gate -> withContext(NonCancellable) { probeInCleanup = true; gate.await(); probeInCleanup = false } }
        }
    }
    override fun close() { closed = true }
}
