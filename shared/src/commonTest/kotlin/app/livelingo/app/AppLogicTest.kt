@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.livelingo.app

import app.livelingo.core.*
import io.ktor.client.HttpClient
import kotlinx.coroutines.CompletableDeferred
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
    var firstChunk: ByteArray? = null
    var finalChunk: ByteArray? = null
    var startFailure: Exception? = null
    private var onAudio: ((ByteArray) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
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
    private var sequence = 0
    override val storage: LocalStorage = memory
    override val microphone: Microphone = mic
    override val exporter = object : TextExporter { override fun export(fileName: String, mimeType: String, text: String) = Unit }
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
    override suspend fun run(connection: FoundryConnection, apiKey: String, settings: SessionSettings, audio: ReceiveChannel<ByteArray>, onReady: suspend () -> Unit, onTranscript: suspend (TranscriptDelta) -> Unit) {
        starts++
        connections += connection
        keys += apiKey
        ready.await()
        onReady()
        for (chunk in audio) this.audio += chunk
    }
    override fun close() { closed = true }
}
