@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.livelingo.app

import app.livelingo.core.*
import io.ktor.client.HttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlin.test.*

class AppLogicTest {
    @Test fun failedTranscriptWriteCannotBeOverwrittenByStartingAnotherSession() = runTest {
        val services = configuredServices()
        val transport = FakeTransport()
        val controller = AppController(services, transport, StandardTestDispatcher(testScheduler))
        controller.start(); runCurrent()
        val originalId = controller.state.value.current!!.id
        services.memory.failTranscriptWrite = true
        transport.streamFailure = assertFailsWith<TranslationFailure> {
            transport.receiver!!(TranscriptDelta(TranscriptChannel.OUTPUT, "唯一的未儲存字幕", 0, 1000))
        }
        services.mic.emit(byteArrayOf(1, 0)); runCurrent()
        controller.state.first { it.phase == ConnectionPhase.ERROR }
        assertFalse(controller.state.value.transcriptSaved)
        controller.start(); runCurrent()
        assertEquals(1, transport.starts)
        assertEquals(originalId, controller.state.value.current!!.id)
        assertEquals("唯一的未儲存字幕", controller.state.value.current!!.fragments.single().text)
        assertTrue("請先匯出" in controller.state.value.message.orEmpty())
        controller.dispose()
    }

    @Test fun contentFilterNoticeKeepsCaptureRunningAndMarksSavedTranscriptIncomplete() = runTest {
        val services = configuredServices()
        val transport = FakeTransport()
        val controller = AppController(services, transport, StandardTestDispatcher(testScheduler))
        controller.start(); runCurrent()
        transport.noticeReceiver!!(TranslationNotice("Foundry 已阻擋部分內容，等待後續事件。", FoundryDiagnostic(FoundryStages.STREAM, "CONTENT_FILTER", providerCode = "content_filter")))
        assertEquals(ConnectionPhase.LISTENING, controller.state.value.phase)
        assertTrue(services.mic.recording)
        assertNull(controller.state.value.message) // The notice does not cover captions with a modal.
        assertEquals(1, AppRepository(services.memory).history().single().contentFilterCount)
        assertTrue(controller.state.value.lastConnectionReport.orEmpty().contains("不適用（WebSocket 事件）"))
        assertTrue(controller.state.value.lastConnectionReport.orEmpty().contains("通知（"))
        transport.receiver!!(TranscriptDelta(TranscriptChannel.OUTPUT, "後續字幕", 1000, 2000))
        assertTrue(controller.state.value.streamNotice.orEmpty().contains("後續字幕已繼續"))
        controller.stop(); controller.state.first { it.phase == ConnectionPhase.IDLE }
        val record = AppRepository(services.memory).history().single()
        assertEquals(1, record.contentFilterCount)
        assertEquals("後續字幕", record.fragments.single().text)
        assertTrue("部分片段可能不完整" in SubtitleExporter.text(record))
        assertEquals(1, transport.starts)
        controller.dispose()
    }

    @Test fun audioSourcePersistsAndIsPinnedForTheEntireSession() = runTest {
        val services = configuredServices()
        val transport = FakeTransport()
        val controller = AppController(services, transport, StandardTestDispatcher(testScheduler))
        controller.selectAudioSource(AudioSource.DEVICE_PLAYBACK)
        assertEquals(AudioSource.DEVICE_PLAYBACK, AppRepository(services.memory).preferences().audioSource)
        controller.start(); runCurrent()
        assertEquals(listOf(AudioSource.DEVICE_PLAYBACK), services.mic.selectedSources)
        assertEquals(AudioSource.DEVICE_PLAYBACK, transport.sessionSettings.single().audioSource)
        controller.selectAudioSource(AudioSource.MICROPHONE)
        assertEquals(AudioSource.DEVICE_PLAYBACK, controller.state.value.preferences.audioSource)
        assertEquals(AudioSource.DEVICE_PLAYBACK, AppRepository(services.memory).history().single().settings.audioSource)
        assertTrue("音源：裝置播放音訊" in SubtitleExporter.text(controller.state.value.current!!))
        controller.stop(); runCurrent()
        controller.selectAudioSource(AudioSource.MICROPHONE)
        controller.start(); runCurrent()
        assertEquals(listOf(AudioSource.DEVICE_PLAYBACK, AudioSource.MICROPHONE), services.mic.selectedSources)
        controller.stop(); runCurrent(); controller.dispose()
        val restored = AppController(services, FakeTransport(), StandardTestDispatcher(testScheduler))
        assertEquals(AudioSource.MICROPHONE, restored.state.value.preferences.audioSource)
        restored.dispose()
    }

    @Test fun removedTeamsPreferenceMigratesOnceWithoutChangingHistoricalSourceOrStartingAudio() = runTest {
        val services = configuredServices()
        val repository = AppRepository(services.memory)
        repository.savePreferences(repository.preferences().copy(audioSource = AudioSource.TEAMS))
        repository.saveTranscript(TranscriptRecord("legacy", "Meeting", 0, SessionSettings(audioSource = AudioSource.TEAMS)))
        val controller = AppController(services, FakeTransport(), StandardTestDispatcher(testScheduler))
        assertEquals(AudioSource.MICROPHONE, controller.state.value.preferences.audioSource)
        assertEquals(AudioSource.MICROPHONE, repository.preferences().audioSource)
        assertTrue(controller.state.value.message.orEmpty().contains("Teams 音源已移除"))
        assertFalse(AudioSource.TEAMS in controller.availableAudioSources())
        controller.selectAudioSource(AudioSource.TEAMS)
        assertEquals(AudioSource.MICROPHONE, controller.state.value.preferences.audioSource)
        assertEquals(AudioSource.TEAMS, repository.history().single().settings.audioSource)
        assertEquals(0, services.mic.starts)
        controller.dispose()
        val restored = AppController(services, FakeTransport(), StandardTestDispatcher(testScheduler))
        assertNull(restored.state.value.message)
        restored.dispose()
    }

    @Test fun filteredDisconnectLeavesCaptionsReadableAndManualNextSessionDoesNotReplayAudio() = runTest {
        val services = configuredServices()
        val transport = FakeTransport()
        val controller = AppController(services, transport, StandardTestDispatcher(testScheduler))
        controller.start(); runCurrent()
        transport.receiver!!(TranscriptDelta(TranscriptChannel.OUTPUT, "已收到的字幕", 0, 1000))
        val oldId = controller.state.value.current!!.id
        transport.noticeReceiver!!(TranslationNotice("部分內容已篩選", FoundryDiagnostic(FoundryStages.STREAM, "CONTENT_FILTER")))
        transport.streamFailure = TranslationFailure("篩選通知後斷線，字幕已保留。",
            FoundryDiagnostic(FoundryStages.STREAM, "DISCONNECTED_AFTER_FILTER", providerCode = "content_filter", webSocketCloseCode = 1008))
        services.mic.emit(byteArrayOf(7, 0)); runCurrent()
        controller.state.first { it.phase == ConnectionPhase.ERROR }
        assertEquals(1, transport.starts)
        assertFalse(services.mic.recording)
        assertNull(controller.state.value.message)
        assertFalse(controller.state.value.showReportInMessage)
        assertNotNull(controller.state.value.sessionIssue)
        assertEquals("已收到的字幕", liveCaptionText(controller.state.value.current, TranscriptChannel.OUTPUT))
        assertTrue(controller.state.value.transcriptSaved)
        val report = controller.state.value.lastConnectionReport.orEmpty()
        assertTrue("WebSocket 關閉碼：1008" in report)
        assertTrue("工作階段結束原因：未收到" in report)
        controller.showConnectionReport()
        assertTrue(controller.state.value.showReportInMessage)
        controller.dismissMessage()
        transport.streamFailure = null
        controller.start(); runCurrent()
        assertEquals(2, transport.starts)
        assertNull(controller.state.value.sessionIssue)
        assertTrue(controller.state.value.current!!.fragments.isEmpty())
        assertTrue(transport.audio.isEmpty())
        val old = AppRepository(services.memory).history().first { it.id == oldId }
        assertEquals("已收到的字幕", old.fragments.single().text)
        assertEquals(1, old.contentFilterCount)
        controller.stop(); runCurrent(); controller.dispose()
    }

    @Test fun legacyPreferencesAndTranscriptsDefaultToMicrophone() {
        val old = WireJson.decodeFromString<AppPreferences>("""{"source":"ENGLISH","target":"ZH_TW"}""")
        assertEquals(AudioSource.MICROPHONE, old.audioSource)
        val session = WireJson.decodeFromString<SessionSettings>("""{"sourceLanguage":"ENGLISH"}""")
        assertEquals(AudioSource.MICROPHONE, session.audioSource)
    }

    @Test fun unsupportedStoredSourceDoesNotSilentlyFallBackOrConnect() = runTest {
        val services = configuredServices()
        AppRepository(services.memory).savePreferences(AppRepository(services.memory).preferences().copy(audioSource = AudioSource.DEVICE_PLAYBACK))
        services.mic.supportedSources = listOf(AudioSource.MICROPHONE)
        val transport = FakeTransport()
        val controller = AppController(services, transport, StandardTestDispatcher(testScheduler))
        controller.start(); runCurrent()
        assertEquals(0, transport.starts)
        assertEquals(0, services.mic.prepares)
        assertTrue(controller.state.value.history.isEmpty())
        assertEquals(AudioSource.DEVICE_PLAYBACK, controller.state.value.preferences.audioSource)
        controller.selectAudioSource(AudioSource.MICROPHONE)
        controller.start(); runCurrent()
        assertEquals(1, services.mic.starts)
        controller.stop(); runCurrent(); controller.dispose()
    }

    @Test fun deniedPlaybackConsentReportsSafeReasonAndReleasesWithoutNetwork() = runTest {
        val services = configuredServices()
        services.mic.prepareFailure = AudioCaptureFailure("未授權音訊擷取，尚未連線或開始收音。")
        val transport = FakeTransport()
        val controller = AppController(services, transport, StandardTestDispatcher(testScheduler))
        controller.selectAudioSource(AudioSource.DEVICE_PLAYBACK)
        controller.start(); runCurrent()
        assertEquals(ConnectionPhase.ERROR, controller.state.value.phase)
        assertEquals("未授權音訊擷取，尚未連線或開始收音。", controller.state.value.message)
        assertEquals(0, transport.starts)
        assertEquals(0, services.mic.starts)
        assertEquals(1, services.mic.releases)
        controller.dispose()
    }

    @Test fun cancelledConsentCannotStartCaptureAfterLateGrant() = runTest {
        val services = configuredServices()
        val consent = CompletableDeferred<Unit>()
        services.mic.prepareGate = consent
        val transport = FakeTransport()
        val controller = AppController(services, transport, StandardTestDispatcher(testScheduler))
        controller.selectAudioSource(AudioSource.DEVICE_PLAYBACK)
        controller.start(); runCurrent()
        controller.stop(); runCurrent()
        consent.complete(Unit); runCurrent()
        assertEquals(ConnectionPhase.IDLE, controller.state.value.phase)
        assertEquals(0, transport.starts)
        assertEquals(0, services.mic.starts)
        assertEquals(1, services.mic.releases)
        controller.dispose()
    }

    @Test fun projectionRevokedDuringHandshakeStopsBeforeAudioStarts() = runTest {
        val services = configuredServices()
        val ready = CompletableDeferred<Unit>()
        val transport = FakeTransport(ready)
        val controller = AppController(services, transport, StandardTestDispatcher(testScheduler))
        controller.selectAudioSource(AudioSource.DEVICE_PLAYBACK)
        controller.start(); runCurrent()
        services.mic.interruptionCallback!!("系統已停止播放音訊擷取")
        ready.complete(Unit); runCurrent()
        assertEquals(ConnectionPhase.ERROR, controller.state.value.phase)
        assertEquals(0, services.mic.starts)
        assertEquals(1, services.mic.releases)
        assertEquals("系統已停止播放音訊擷取", controller.state.value.message)
        controller.dispose()
    }

    @Test fun captureStatusDoesNotOutliveItsSessionAndRevocationSavesTranscript() = runTest {
        val services = configuredServices()
        val transport = FakeTransport()
        val controller = AppController(services, transport, StandardTestDispatcher(testScheduler))
        controller.selectAudioSource(AudioSource.DEVICE_PLAYBACK)
        controller.start(); runCurrent()
        val oldStatus = services.mic.statusCallback!!
        val oldInterruption = services.mic.interruptionCallback!!
        oldStatus(AudioInputStatus(.5f, true))
        assertTrue(controller.state.value.audioInputStatus.signalPresent)
        transport.receiver!!(TranscriptDelta(TranscriptChannel.INPUT, "Meeting audio", 0, 1000))
        services.mic.interruptionCallback!!("已撤回擷取授權")
        controller.state.first { it.phase == ConnectionPhase.ERROR }
        assertEquals("Meeting audio", AppRepository(services.memory).history().single().fragments.single().text)
        assertFalse(controller.state.value.audioInputStatus.signalPresent)
        controller.selectAudioSource(AudioSource.MICROPHONE)
        controller.start(); runCurrent()
        oldStatus(AudioInputStatus(1f, true, "stale warning"))
        oldInterruption("stale projection revocation"); runCurrent()
        assertEquals(AudioInputStatus(), controller.state.value.audioInputStatus)
        assertEquals(ConnectionPhase.LISTENING, controller.state.value.phase)
        controller.stop(); runCurrent(); controller.dispose()
    }

    @Test fun providersKeepIndependentKeysModelsAndSurviveRestart() = runTest {
        val services = configuredServices()
        val transport = FakeTransport()
        val controller = AppController(services, transport, StandardTestDispatcher(testScheduler))
        controller.selectProvider(ModelProvider.OPENAI)
        assertFalse(controller.state.value.hasApiKey)
        assertFalse(controller.saveConnection("https://api.openai.com", "gpt-live-1", ""))
        assertTrue(controller.saveConnection("https://api.openai.com", "gpt-live-1", "openai-secret", "text-model"))
        controller.start(); runCurrent()
        assertEquals(ModelProvider.OPENAI, transport.connections.single().provider)
        assertEquals("openai-secret", transport.keys.single())
        controller.selectProvider(ModelProvider.FOUNDRY)
        assertEquals(ModelProvider.OPENAI, controller.state.value.preferences.provider) // active sessions are pinned
        controller.stop(); runCurrent()
        controller.selectProvider(ModelProvider.FOUNDRY)
        assertTrue(controller.state.value.hasApiKey)
        assertEquals("https://example.openai.azure.com", controller.state.value.preferences.foundryEndpoint)
        controller.selectProvider(ModelProvider.OPENAI)
        controller.dispose()
        val restored = AppController(services, FakeTransport(), StandardTestDispatcher(testScheduler))
        assertEquals(ModelProvider.OPENAI, restored.state.value.preferences.provider)
        assertEquals("text-model", restored.state.value.preferences.openaiTextModel)
        assertTrue(restored.state.value.hasApiKey)
        restored.clearApiKey()
        assertFalse(restored.state.value.hasApiKey)
        restored.selectProvider(ModelProvider.FOUNDRY)
        assertTrue(restored.state.value.hasApiKey)
        assertTrue(services.memory.regular.values.none { "openai-secret" in it })
        restored.dispose()
    }

    @Test fun failedProviderSwitchDoesNotReplaceActiveProviderOrCredential() = runTest {
        val services = configuredServices()
        val controller = AppController(services, FakeTransport(), StandardTestDispatcher(testScheduler))
        services.memory.failPreferencesWrite = true
        controller.selectProvider(ModelProvider.OPENAI)
        assertEquals(ModelProvider.FOUNDRY, controller.state.value.preferences.provider)
        assertTrue(controller.state.value.hasApiKey)
        controller.dispose()
    }

    @Test fun finalParagraphFormattingPersistsLayoutWithoutChangingRawTextOrTimes() = runTest {
        val services = configuredServices()
        val transport = FakeTransport()
        val formatter = object : ParagraphFormatter {
            var calls = 0
            override suspend fun format(connection: FoundryConnection, apiKey: String, model: String, record: TranscriptRecord): List<ParagraphLayout> {
                calls++
                assertEquals("paragraph-deployment", model)
                val windows = paragraphWindows(record)
                return parseParagraphLayouts("""{"output":[2]}""", windows, emptyList())
            }
        }
        val controller = AppController(services, transport, StandardTestDispatcher(testScheduler), formatter)
        assertTrue(controller.saveConnection("https://example.openai.azure.com", "gpt-live-1", "", "paragraph-deployment"))
        controller.start(); runCurrent()
        val raw = "台湾是4.95兆美元。印度是4.92兆美元。那么，台湾为何领先？"
        transport.receiver!!(TranscriptDelta(TranscriptChannel.OUTPUT, raw, 100, 1500))
        controller.stop()
        controller.state.first { it.phase == ConnectionPhase.IDLE }
        val record = controller.state.value.current!!
        assertEquals(raw, record.fragments.single().text)
        assertEquals(100L, record.fragments.single().startMs)
        assertTrue("4.92兆美元。\n\n" in liveCaptionText(record, TranscriptChannel.OUTPUT))
        assertEquals(record.paragraphLayouts, AppRepository(services.memory).history().single().paragraphLayouts)
        assertEquals(1, formatter.calls)
        controller.dispose()
    }

    @Test fun paragraphServiceFailureDoesNotLoseTranscriptOrFailTranslation() = runTest {
        val services = configuredServices()
        val transport = FakeTransport()
        val formatter = object : ParagraphFormatter {
            override suspend fun format(connection: FoundryConnection, apiKey: String, model: String, record: TranscriptRecord): List<ParagraphLayout> = error("provider-secret")
        }
        val controller = AppController(services, transport, StandardTestDispatcher(testScheduler), formatter)
        controller.saveConnection("https://example.openai.azure.com", "gpt-live-1", "", "paragraph-deployment")
        controller.start(); runCurrent()
        transport.receiver!!(TranscriptDelta(TranscriptChannel.OUTPUT, "第一句。第二句。第三句。", 0, 1000))
        controller.stop(); controller.state.first { it.phase == ConnectionPhase.IDLE }
        assertEquals(ConnectionPhase.IDLE, controller.state.value.phase)
        assertEquals(1, AppRepository(services.memory).history().single().fragments.size)
        assertFalse("provider-secret" in controller.state.value.paragraphStatus.orEmpty())
        assertTrue("暫時無法使用" in controller.state.value.paragraphStatus.orEmpty())
        controller.dispose()
    }
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

    @Test fun translationModesPersistAndAreSnapshotForEachSession() = runTest {
        val services = configuredServices()
        val transport = FakeTransport()
        val controller = AppController(services, transport, StandardTestDispatcher(testScheduler))
        controller.setTranslationOptions(TranslationPace.FAST, TranslationQuality.NATURAL)
        assertEquals(TranslationPace.FAST, AppRepository(services.memory).preferences().pace)
        assertEquals(TranslationQuality.NATURAL, AppRepository(services.memory).preferences().quality)
        controller.start()
        runCurrent()
        assertEquals(TranslationPace.FAST, transport.sessionSettings.single().pace)
        assertEquals(TranslationQuality.NATURAL, transport.sessionSettings.single().quality)
        controller.setTranslationOptions(TranslationPace.BALANCED, TranslationQuality.FAITHFUL)
        assertEquals(TranslationPace.FAST, AppRepository(services.memory).preferences().pace)
        controller.stop()
        runCurrent()
        controller.dispose()
    }

    @Test fun olderPreferencesLoadDefaultModesWithoutResettingExistingSettings() {
        val services = FakeServices()
        services.memory.regular["preferences"] =
            """{"foundryEndpoint":"https://example.openai.azure.com","deployment":"gpt-live-1","source":"ENGLISH","target":"ZH_TW","knowledgeId":"banking"}"""
        val preferences = AppRepository(services.memory).preferences()
        assertEquals(Language.ENGLISH, preferences.source)
        assertEquals("banking", preferences.knowledgeId)
        assertEquals(TranslationPace.BALANCED, preferences.pace)
        assertEquals(TranslationQuality.FAITHFUL, preferences.quality)
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
        assertTrue(controller.state.value.message.orEmpty().contains("背景翻譯音源"))
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
        assertEquals("麥克風被其他 App 中斷", controller.state.value.sessionIssue)
        assertNull(controller.state.value.message)
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

    @Test fun changingVoiceProtocolKeepsStoredKeyAndProbingDraftDoesNotSaveIt() = runTest {
        val services = configuredServices(); val transport = FakeTransport()
        val controller = AppController(services, transport, StandardTestDispatcher(testScheduler))
        controller.testConnection("https://example.openai.azure.com", "my-transcribe", "", SpeechModel.LIVE_TRANSCRIBE, TextModel.GPT6_LUNA, "my-luna")
        runCurrent()
        assertEquals(SpeechModel.LIVE_TRANSCRIBE, transport.probeConnections.single().speechModel)
        assertEquals("my-luna", transport.probeConnections.single().textDeployment)
        assertEquals("short-key", transport.probeKeys.single())
        assertEquals(SpeechModel.GPT_LIVE, AppRepository(services.memory).preferences().foundrySpeechKind)
        assertEquals(0, services.mic.starts)
        assertTrue(controller.saveConnection("https://example.openai.azure.com", "my-translate", "", "my-luna", SpeechModel.REALTIME_TRANSLATE, TextModel.GPT6_LUNA, "my-whisper"))
        controller.selectKnowledge("banking")
        controller.start(); runCurrent()
        assertEquals("short-key", transport.keys.single())
        assertEquals(SpeechModel.REALTIME_TRANSLATE, transport.connections.single().speechModel)
        assertEquals("my-whisper", transport.connections.single().sourceTranscriptionModel)
        assertNull(transport.sessionSettings.single().knowledge)
        assertEquals(Language.AUTO, transport.sessionSettings.single().sourceLanguage)
        controller.stop(); runCurrent(); controller.dispose()
    }

    @Test fun transcriptionWithoutTextDeploymentDoesNotStartCapture() = runTest {
        val services = configuredServices(); val transport = FakeTransport()
        val controller = AppController(services, transport, StandardTestDispatcher(testScheduler))
        assertTrue(controller.saveConnection("https://example.openai.azure.com", "my-transcribe", "", "", SpeechModel.LIVE_TRANSCRIBE, TextModel.GPT6_LUNA))
        controller.start(); runCurrent()
        assertEquals(0, transport.starts); assertEquals(0, services.mic.starts)
        assertTrue("文字翻譯模型" in controller.state.value.message.orEmpty())
        assertTrue(AppRepository(services.memory).history().isEmpty())
        controller.dispose()
    }

    @Test fun finalFormattingAppliesCommaBoundariesToBothTracksAndReportsEachResult() = runTest {
        val services = configuredServices(); val transport = FakeTransport()
        var formattedBothTracks = false
        val formatter = object : ParagraphFormatter {
            override suspend fun format(connection: FoundryConnection, apiKey: String, model: String, record: TranscriptRecord): List<ParagraphLayout> {
                val windows = paragraphWindows(record)
                // The periodic job may run between the input and output deltas while storage suspends.
                formattedBothTracks = formattedBothTracks || windows.map { it.channel }.toSet() == TranscriptChannel.entries.toSet()
                assertEquals("gpt-6-luna", model)
                val response = windows.joinToString(prefix = "{", postfix = "}") { window ->
                    "\"${window.key}\":[${if (window.channel == TranscriptChannel.INPUT) 1 else 2}]"
                }
                return parseParagraphLayouts(response, windows, record.paragraphLayouts)
            }
        }
        val controller = AppController(services, transport, StandardTestDispatcher(testScheduler), formatter)
        controller.start(); runCurrent()
        transport.receiver!!(TranscriptDelta(TranscriptChannel.INPUT, "Check 1,234.56 dollars. Then discuss deployment. Final step.", 0, 3000))
        val raw = "先核對1,234.56美元，完成第一項，接著討論部署，最後確認時間"
        transport.receiver!!(TranscriptDelta(TranscriptChannel.OUTPUT, raw, 0, 3000))
        controller.stop(); controller.state.first { it.phase == ConnectionPhase.IDLE }
        assertTrue(formattedBothTracks, "The final request must include the comma-only translation and original")
        val saved = AppRepository(services.memory).history().single()
        assertTrue("第一項，\n\n接著討論" in liveCaptionText(saved, TranscriptChannel.OUTPUT))
        assertEquals(raw, saved.fragments.last().text)
        assertEquals(3000L, saved.fragments.last().endMs)
        assertEquals("原文：已分段；譯文：已分段", controller.state.value.paragraphStatus)
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
    var failTranscriptWrite = false
    var failFoundryKeyRead = false
    override fun read(key: String) = regular[key]
    override fun write(key: String, value: String) {
        if (key == "preferences" && failPreferencesWrite) throw IllegalStateException("simulated disk failure")
        if (key.startsWith("transcript_") && failTranscriptWrite) throw IllegalStateException("simulated transcript disk failure")
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
    var supportedSources: List<AudioSource> = AudioSource.entries
    val selectedSources = mutableListOf<AudioSource>()
    var statusCallback: ((AudioInputStatus) -> Unit)? = null
    var interruptionCallback: ((String) -> Unit)? = null
    var releases = 0
    override fun availableSources(): List<AudioSource> = supportedSources
    override suspend fun prepareSource(source: AudioSource, onStatus: (AudioInputStatus) -> Unit, onError: (String) -> Unit) {
        selectedSources += source
        statusCallback = onStatus
        interruptionCallback = onError
        super.prepareSource(source, onStatus, onError)
    }
    override fun releaseSession() { releases++ }
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
    fun emit(bytes: ByteArray) { onAudio?.invoke(bytes) }
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
    var receiver: (suspend (TranscriptDelta) -> Unit)? = null
    var starts = 0
    var closed = false
    val audio = mutableListOf<ByteArray>()
    val connections = mutableListOf<FoundryConnection>()
    val keys = mutableListOf<String>()
    val sessionSettings = mutableListOf<SessionSettings>()
    var runFailure: Exception? = null
    var streamFailure: Exception? = null
    var probes = 0
    val probeConnections = mutableListOf<FoundryConnection>()
    val probeKeys = mutableListOf<String>()
    var probeFailure: Exception? = null
    var probeReady = CompletableDeferred(Unit)
    var probeCleanup: CompletableDeferred<Unit>? = null
    var probeInCleanup = false
    var noticeReceiver: (suspend (TranslationNotice) -> Unit)? = null
    override suspend fun run(connection: FoundryConnection, apiKey: String, settings: SessionSettings, audio: ReceiveChannel<ByteArray>, onReady: suspend () -> Unit, onTranscript: suspend (TranscriptDelta) -> Unit, onNotice: suspend (TranslationNotice) -> Unit) {
        starts++
        connections += connection
        keys += apiKey
        sessionSettings += settings
        runFailure?.let { throw it }
        ready.await()
        receiver = onTranscript
        noticeReceiver = onNotice
        onReady()
        for (chunk in audio) { streamFailure?.let { throw it }; this.audio += chunk }
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
