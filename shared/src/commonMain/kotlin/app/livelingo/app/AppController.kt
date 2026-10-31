package app.livelingo.app

import app.livelingo.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

enum class ConnectionPhase { IDLE, CONNECTING, LISTENING, STOPPING, ERROR }

data class AppState(
    val preferences: AppPreferences = AppPreferences(),
    val knowledge: List<BackgroundKnowledge> = emptyList(),
    val history: List<TranscriptRecord> = emptyList(),
    val current: TranscriptRecord? = null,
    val phase: ConnectionPhase = ConnectionPhase.IDLE,
    val speaker: String = "未標記",
    val hasApiKey: Boolean = false,
    val message: String? = null,
    val storageAvailable: Boolean = true,
    val transcriptSaved: Boolean = true,
    val connectionTesting: Boolean = false,
    val lastConnectionReport: String? = null,
    val lastConnectionReportTitle: String = "Foundry 連線診斷",
    val showReportInMessage: Boolean = false,
    val paragraphStatus: String? = null,
    val audioInputStatus: AudioInputStatus = AudioInputStatus(),
    val streamNotice: String? = null,
    val sessionIssue: String? = null,
) {
    val active: Boolean get() = phase in setOf(ConnectionPhase.CONNECTING, ConnectionPhase.LISTENING, ConnectionPhase.STOPPING)
    val busy: Boolean get() = active || connectionTesting
}

class AppController(
    private val services: PlatformServices,
    private val transport: TranslationTransport = ModelTranslationTransport { services.createHttpClient() },
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private var paragraphFormatter: ParagraphFormatter? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val repository = AppRepository(services.storage)
    private val mutable = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = mutable.asStateFlow()
    private var apiKey = ""
    private var audio: Channel<ByteArray>? = null
    private var sessionJob: Job? = null
    private var probeJob: Job? = null
    private var sessionFailure: String? = null
    private var paragraphJob: Job? = null
    private val transcriptWrites = Mutex()

    init {
        try {
            val storedPreferences = repository.preferences()
            val sourceRemoved = !storedPreferences.audioSource.selectable
            val preferences = if (sourceRemoved) storedPreferences.copy(audioSource = AudioSource.MICROPHONE) else storedPreferences
            val knowledge = repository.knowledge()
            val history = repository.history()
            val connection = preferences.connection()
            var setupMessage: String? = if (sourceRemoved) "Teams 音源已移除，已改選麥克風。請在開始前確認音源；既有逐字稿會保留。" else null
            apiKey = if (connection.validationError() == null) {
                try { repository.apiKey(connection.credentialEndpoint()) }
                catch (_: Exception) {
                    setupMessage = "無法讀取已儲存的 API key。逐字稿與背景知識仍可使用；請到設定移除或重新輸入 API key。"
                    ""
                }
            } else ""
            try {
                if (sourceRemoved) repository.savePreferences(preferences)
                repository.migrateLegacyConnection(preferences)
            }
            catch (_: Exception) {
                if (setupMessage == null) setupMessage = "既有逐字稿與背景知識已保留；舊版連線設定尚未完成清理。請確認儲存空間後重新開啟 App。"
            }
            mutable.value = AppState(
                preferences = preferences,
                knowledge = knowledge,
                history = history,
                hasApiKey = apiKey.isNotBlank(),
                message = setupMessage,
            )
        } catch (_: Exception) {
            mutable.value = AppState(storageAvailable = false,
                message = "無法讀取本機資料。為避免覆寫既有逐字稿，已暫停編輯與錄音；請重新開啟 App 並確認裝置儲存空間。")
        }
    }

    fun dismissMessage() { mutable.value = mutable.value.copy(message = null, showReportInMessage = false) }
    private fun inform(message: String) { mutable.value = mutable.value.copy(message = message, showReportInMessage = false) }

    fun setLanguages(source: Language, target: Language) {
        if (mutable.value.active || target == Language.AUTO) return
        changePreferences(mutable.value.preferences.copy(source = source, target = target))
    }

    fun selectKnowledge(id: String?) {
        if (!mutable.value.active) changePreferences(mutable.value.preferences.copy(knowledgeId = id))
    }

    fun setTranslationOptions(pace: TranslationPace, quality: TranslationQuality) {
        if (mutable.value.busy || !mutable.value.storageAvailable) return
        changePreferences(mutable.value.preferences.copy(pace = pace, quality = quality))
    }

    fun availableAudioSources(): List<AudioSource> = services.microphone.availableSources().filter { it.selectable }
    fun reportsAudioLevel(): Boolean = services.microphone.reportsAudioLevel()
    fun audioSourceUnavailableReason(): String? = services.microphone.unavailableReason(mutable.value.preferences.audioSource)

    fun selectAudioSource(source: AudioSource) {
        if (mutable.value.busy || !mutable.value.storageAvailable) return
        if (source !in availableAudioSources()) { inform("此平台不支援這個音源。"); return }
        changePreferences(mutable.value.preferences.copy(audioSource = source))
    }

    fun selectProvider(provider: ModelProvider) {
        if (mutable.value.busy || !mutable.value.storageAvailable || provider == mutable.value.preferences.provider) return
        val preferences = mutable.value.preferences.copy(provider = provider)
        try {
            val connection = preferences.connection()
            val key = if (connection.validationError() == null) repository.apiKey(connection.credentialEndpoint()) else ""
            repository.savePreferences(preferences)
            apiKey = key
            mutable.value = mutable.value.copy(preferences = preferences, hasApiKey = key.isNotBlank(),
                lastConnectionReport = null, showReportInMessage = false, message = null)
        } catch (_: Exception) { inform("無法切換服務，請確認儲存空間與安全儲存區。") }
    }

    fun setAiParagraphs(enabled: Boolean) {
        if (!mutable.value.busy) changePreferences(mutable.value.preferences.copy(aiParagraphs = enabled))
    }

    private fun changePreferences(value: AppPreferences) {
        if (!mutable.value.storageAvailable) return
        try { repository.savePreferences(value); mutable.value = mutable.value.copy(preferences = value) }
        catch (_: Exception) { inform("設定無法儲存，請確認裝置儲存空間。") }
    }

    fun saveConnection(endpoint: String, deployment: String, replacementApiKey: String, paragraphModel: String? = null, speechKind: SpeechModel? = null, textKind: TextModel? = null, sourceTranscript: String? = null): Boolean {
        if (mutable.value.busy || !mutable.value.storageAvailable) return false
        if (paragraphModel != null && paragraphModel.isNotBlank() && !Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}").matches(paragraphModel.trim())) {
            inform("請填入有效的 AI 分段文字模型名稱。")
            return false
        }
        val resolved = resolveConnection(endpoint, deployment, replacementApiKey, speechKind, textKind, paragraphModel, sourceTranscript) ?: return false
        val connection = resolved.connection
        val canonicalEndpoint = connection.credentialEndpoint()
        val previous = mutable.value.preferences.connection()
        val previousEndpoint = if (previous.validationError() == null) previous.credentialEndpoint() else null
        val current = mutable.value.preferences
        val preferences = if (connection.provider == ModelProvider.OPENAI)
            current.copy(openaiModel = connection.deployment, openaiTextModel = connection.textDeployment,
                openaiSpeechKind = connection.speechModel, openaiTextKind = connection.textModelKind, openaiSourceTranscript = connection.sourceTranscriptionModel)
        else current.copy(foundryEndpoint = connection.endpoint, deployment = connection.deployment,
            foundryTextModel = connection.textDeployment, foundrySpeechKind = connection.speechModel,
            foundryTextKind = connection.textModelKind, foundrySourceTranscript = connection.sourceTranscriptionModel)
        return try {
            repository.saveApiKey(canonicalEndpoint, resolved.apiKey)
            repository.savePreferences(preferences)
            apiKey = resolved.apiKey
            mutable.value = mutable.value.copy(preferences = preferences, hasApiKey = true, message = "${connection.provider.label} 連線設定已儲存。", showReportInMessage = false)
            true
        } catch (_: Exception) {
            // Reconcile with the encrypted destination binding; never send a newly stored key to an old endpoint.
            apiKey = runCatching { previousEndpoint?.let(repository::apiKey).orEmpty() }.getOrDefault("")
            mutable.value = mutable.value.copy(hasApiKey = apiKey.isNotBlank())
            inform("連線設定未完整儲存。請確認裝置儲存空間，並重新輸入 API key。")
            false
        }
    }

    private data class ResolvedConnection(val connection: FoundryConnection, val apiKey: String)

    private fun resolveConnection(endpoint: String, deployment: String, replacementApiKey: String,
        speechKind: SpeechModel? = null, textKind: TextModel? = null, textDeployment: String? = null, sourceTranscript: String? = null): ResolvedConnection? {
        val saved = mutable.value.preferences.connection()
        val connection = saved.copy(endpoint = endpoint.trim(), deployment = deployment.trim(),
            speechModel = speechKind ?: saved.speechModel, textModelKind = textKind ?: saved.textModelKind,
            textDeployment = textDeployment?.trim() ?: saved.textDeployment,
            sourceTranscriptionModel = sourceTranscript?.trim() ?: saved.sourceTranscriptionModel)
        connection.validationError()?.let { inform(it); return null }
        if (connection.provider == ModelProvider.OPENAI) {
            val namedSpeech = SpeechModel.entries.firstOrNull { connection.deployment == it.modelId }
            if (namedSpeech != null && namedSpeech != connection.speechModel) { inform("語音模型 ID 與選擇的模型類型不符，請重新選擇。"); return null }
            val namedText = TextModel.infer(connection.textDeployment)
            if (namedText != TextModel.CUSTOM && connection.textModelKind != TextModel.CUSTOM && namedText != connection.textModelKind) {
                inform("文字模型 ID 與選擇的模型類型不符，請重新選擇。"); return null
            }
        }
        val canonicalEndpoint = connection.credentialEndpoint()
        val previous = mutable.value.preferences.connection()
        val previousEndpoint = if (previous.validationError() == null) previous.credentialEndpoint() else null
        if (canonicalEndpoint != previousEndpoint && replacementApiKey.isEmpty()) {
            inform("新增或變更服務端點時，請重新輸入這個服務的 API key。")
            return null
        }
        if (replacementApiKey.any { it.code < 0x20 || it.code == 0x7f }) {
            inform("API key 不能包含換行或控制字元。")
            return null
        }
        val nextKey = if (replacementApiKey.isEmpty()) apiKey else replacementApiKey.trim()
        if (nextKey.isBlank() || nextKey.any { it.isWhitespace() }) {
            inform("請輸入有效的 ${connection.provider.label} API key，且不要包含空白。")
            return null
        }
        return ResolvedConnection(connection, nextKey)
    }

    fun clearApiKey() {
        if (mutable.value.busy || !mutable.value.storageAvailable) return
        try {
            val provider = mutable.value.preferences.provider
            repository.clearApiKey(provider); apiKey = ""
            mutable.value = mutable.value.copy(hasApiKey = false, message = "已移除 ${provider.label} API key。", showReportInMessage = false)
        } catch (_: Exception) { inform("無法移除 API key，請重試。") }
    }

    /** Tests the displayed form without saving it, recording audio, or selecting any background knowledge. */
    fun testConnection(endpoint: String, deployment: String, replacementApiKey: String, speechKind: SpeechModel? = null, textKind: TextModel? = null, textDeployment: String? = null, sourceTranscript: String? = null) {
        if (mutable.value.busy || !mutable.value.storageAvailable) return
        val resolved = resolveConnection(endpoint, deployment, replacementApiKey, speechKind, textKind, textDeployment, sourceTranscript) ?: return
        mutable.value = mutable.value.copy(connectionTesting = true, message = null,
            lastConnectionReport = null, showReportInMessage = false)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = withTimeout(75_000) { transport.probe(resolved.connection, resolved.apiKey) }
                recordDiagnostic(resolved.connection, resolved.apiKey, result.diagnostic, result.message, success = true)
            } catch (_: TimeoutCancellationException) {
                recordDiagnostic(resolved.connection, resolved.apiKey,
                    FoundryDiagnostic("連線測試", "PROBE_TIMEOUT"), "連線測試逾時，請檢查網路與資源端點後重試。", success = false)
            } catch (_: CancellationException) {
                // User cancellation and disposal must never turn on the microphone or create a transcript.
            } catch (failure: Exception) {
                val safe = failure as? TranslationFailure
                recordDiagnostic(resolved.connection, resolved.apiKey,
                    safe?.diagnostic ?: FoundryDiagnostic("連線測試", "UNKNOWN"),
                    safe?.message ?: "連線測試失敗，請檢查網路或稍後重試。", success = false)
            } finally {
                mutable.value = mutable.value.copy(connectionTesting = false)
                probeJob = null
            }
        }
        probeJob = job
        job.start()
    }

    fun cancelConnectionTest() {
        if (!mutable.value.connectionTesting) return
        inform("已取消連線測試，沒有啟用麥克風。")
        probeJob?.cancel()
    }

    fun showConnectionReport() {
        if (mutable.value.lastConnectionReport == null) return
        mutable.value = mutable.value.copy(message = "可複製或分享以下診斷資訊。", showReportInMessage = true)
    }

    fun exportConnectionReport() {
        val report = mutable.value.lastConnectionReport ?: return
        try { services.exporter.export("LiveLingo_connection_diagnostic_${services.currentTimeMillis()}.txt", "text/plain", report) }
        catch (_: Exception) { inform("無法分享診斷資訊，請使用複製功能。") }
    }

    private fun recordDiagnostic(connection: FoundryConnection, secret: String, diagnostic: FoundryDiagnostic,
                                 message: String, success: Boolean, showDialog: Boolean = true) {
        val safeMessage = safeDiagnosticField(message, secret)
        mutable.value = mutable.value.copy(
            message = if (showDialog) safeMessage else null,
            lastConnectionReport = connectionReport(connection, diagnostic, safeMessage, success, services.currentTimeMillis(), secret),
            lastConnectionReportTitle = if (success) "連線測試成功" else "${connection.provider.label} 連線失敗",
            showReportInMessage = showDialog,
        )
    }

    fun saveKnowledge(id: String?, name: String, content: String): Boolean {
        if (mutable.value.active || !mutable.value.storageAvailable) return false
        val entry = BackgroundKnowledge(id ?: services.uniqueId(), name.trim(), content.trim())
        if (entry.name.isBlank() || entry.name.length > 80 || entry.content.isBlank() || entry.content.length > 12_000) {
            inform("請填寫名稱（1–80 字）及背景內容（1–12,000 字）。")
            return false
        }
        val entries = mutable.value.knowledge.filterNot { it.id == entry.id } + entry
        return try {
            repository.saveKnowledge(entries)
            mutable.value = mutable.value.copy(knowledge = entries)
            true
        } catch (_: Exception) { inform("背景知識無法儲存，請確認裝置儲存空間。"); false }
    }

    fun deleteKnowledge(id: String) {
        if (mutable.value.active || !mutable.value.storageAvailable) return
        try {
            val entries = mutable.value.knowledge.filterNot { it.id == id }
            repository.saveKnowledge(entries)
            mutable.value = mutable.value.copy(knowledge = entries)
            if (mutable.value.preferences.knowledgeId == id) selectKnowledge(null)
        } catch (_: Exception) { inform("背景知識無法刪除，請重試。") }
    }

    fun setSpeaker(label: String) { mutable.value = mutable.value.copy(speaker = label.trim().take(80).ifEmpty { "未標記" }) }

    fun start() {
        val snapshot = mutable.value
        if (snapshot.busy || sessionJob?.isActive == true || !snapshot.storageAvailable) return
        if (!snapshot.transcriptSaved) { inform("目前逐字稿尚未完整儲存，請先匯出並確認儲存空間，再重新開啟 App。"); return }
        audioSourceUnavailableReason()?.let { inform(it); return }
        val connection = snapshot.preferences.connection()
        connection.validationError()?.let { inform("請先到「設定」完成 ${connection.provider.label} 連線。$it"); return }
        if (apiKey.isBlank()) { inform("請先到「設定」儲存 ${connection.provider.label} API key。"); return }
        val sessionApiKey = apiKey
        val settings = SessionSettings(
            if (connection.speechModel == SpeechModel.REALTIME_TRANSLATE) Language.AUTO else snapshot.preferences.source, snapshot.preferences.target,
            if (connection.speechModel == SpeechModel.REALTIME_TRANSLATE) null else snapshot.knowledge.find { it.id == snapshot.preferences.knowledgeId },
            snapshot.preferences.pace, snapshot.preferences.quality,
            snapshot.preferences.provider,
            snapshot.preferences.audioSource,
            connection.speechModel,
        )
        settings.validationError()?.let { inform(it); return }
        if (connection.speechModel.transcribes && connection.textDeployment.isBlank()) {
            inform("此轉錄方案需設定文字翻譯模型；請到設定選擇 GPT-6 Luna 或其他文字模型。"); return
        }
        val now = services.currentTimeMillis()
        val record = TranscriptRecord(services.uniqueId(), "${settings.sourceLanguage.label} → ${settings.targetLanguage.label}", now, settings)
        try { repository.saveTranscript(record) }
        catch (_: Exception) { inform("無法建立逐字稿，尚未開始收音。請確認儲存空間。"); return }
        val queue = Channel<ByteArray>(capacity = 30)
        audio = queue
        sessionFailure = null
        var savedFragmentCount = 0
        val paragraphModel = snapshot.preferences.paragraphModel().takeIf { snapshot.preferences.aiParagraphs }.orEmpty()
        mutable.value = snapshot.copy(phase = ConnectionPhase.CONNECTING, current = record, transcriptSaved = true,
            history = listOf(record) + snapshot.history, message = null, lastConnectionReport = null, showReportInMessage = false,
            paragraphStatus = if (paragraphModel.isBlank()) "基本分段；AI 語意分段可至設定指定文字模型。" else "AI 語意分段：等待完整句子",
            audioInputStatus = AudioInputStatus(), streamNotice = null, sessionIssue = null)
        sessionJob = scope.launch {
            var listeningStarted = false
            try {
                // Android must create its microphone foreground service while Activity is visible,
                // not after the potentially slow Foundry handshake.
                try {
                    services.microphone.prepareSource(settings.audioSource, onStatus = { status ->
                        if (mutable.value.current?.id == record.id && mutable.value.phase == ConnectionPhase.LISTENING)
                            mutable.value = mutable.value.copy(audioInputStatus = status)
                    }, onError = { microphoneFailed(it, record.id) })
                    ensureActive()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    throw TranslationFailure((failure as? AudioCaptureFailure)?.message ?: "無法準備背景翻譯音源。請在 App 前景開始，並允許所需的錄音或音訊擷取授權。")
                }
                transport.run(connection, sessionApiKey, settings, queue,
                    onReady = {
                        ensureActive()
                        if (mutable.value.phase != ConnectionPhase.CONNECTING) throw CancellationException()
                        try { services.microphone.start(
                            onAudio = { bytes ->
                                val currentState = mutable.value
                                if (currentState.current?.id == record.id && currentState.active) {
                                    val result = queue.trySend(bytes.copyOf())
                                    if (result.isFailure && !result.isClosed) microphoneFailed("網路傳送速度不足，已停止收音以避免遺失音訊。已收到的字幕已保留。", record.id)
                                }
                            },
                            onError = { microphoneFailed(it, record.id) },
                        ) } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            throw TranslationFailure((failure as? AudioCaptureFailure)?.message ?: if (settings.audioSource == AudioSource.MICROPHONE)
                                "無法啟用麥克風。請允許麥克風權限，並確認沒有其他 App 或通話占用麥克風，再重新開始。"
                            else "無法啟用所選播放音源。請確認音訊擷取授權與裝置音訊狀態，再重新開始。")
                        }
                        ensureActive()
                        mutable.value = mutable.value.copy(phase = ConnectionPhase.LISTENING)
                        listeningStarted = true
                        if (paragraphModel.isNotBlank()) paragraphJob = scope.launch {
                            while (isActive && mutable.value.current?.id == record.id && mutable.value.active) {
                                delay(20_000)
                                formatParagraphs(record.id, connection, sessionApiKey, paragraphModel)
                            }
                        }
                    },
                    onTranscript = { event ->
                        if (event.startMs < 0 || event.endMs <= event.startMs || event.text.length > 65_536)
                            throw TranslationFailure("服務回傳了無效的字幕時間或內容。")
                        if (event.text.isNotEmpty()) {
                            val current = mutable.value.current ?: throw TranslationFailure("逐字稿狀態已遺失。")
                            val label = mutable.value.speaker.takeUnless { it == "未標記" }
                            val fragment = TranscriptFragment(services.uniqueId(), event.channel, event.startMs, event.endMs,
                                event.text, if (event.channel == TranscriptChannel.INPUT) label else null)
                            val next = current.copy(fragments = current.fragments + fragment)
                            // Commit each received fragment, preserving a partial transcript on any network failure.
                            replaceRecord(next)
                            if (next.contentFilterCount > 0) mutable.value = mutable.value.copy(
                                streamNotice = "後續字幕已繼續更新。本次收到 ${next.contentFilterCount} 次內容安全篩選通知，被阻擋的片段可能不完整。")
                            mutable.value = mutable.value.copy(transcriptSaved = false)
                            try {
                                persistCurrentTranscript(record.id)
                                savedFragmentCount = next.fragments.size
                                mutable.value = mutable.value.copy(transcriptSaved = true)
                            }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { throw TranslationFailure("逐字稿無法寫入裝置，已停止收音。請先匯出目前逐字稿，再確認儲存空間。") }
                        }
                    },
                    onNotice = { notice ->
                        val current = mutable.value.current?.takeIf { it.id == record.id }
                        if (current != null && notice.diagnostic.code == "CONTENT_FILTER") {
                            val count = current.contentFilterCount.coerceAtMost(Int.MAX_VALUE - 1) + 1
                            replaceRecord(current.copy(contentFilterCount = count))
                            val safeMessage = safeDiagnosticField(notice.message, sessionApiKey)
                            mutable.value = mutable.value.copy(streamNotice = safeMessage, transcriptSaved = false,
                                lastConnectionReport = connectionReport(connection, notice.diagnostic, safeMessage,
                                    false, services.currentTimeMillis(), sessionApiKey, warning = !notice.sessionEnded),
                                lastConnectionReportTitle = "${connection.provider.label} 內容篩選通知")
                            try {
                                persistCurrentTranscript(record.id)
                                mutable.value = mutable.value.copy(transcriptSaved = true)
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { throw TranslationFailure("內容篩選記錄無法寫入裝置，已停止收音；請先匯出目前逐字稿。") }
                        }
                    })
            } catch (_: TimeoutCancellationException) {
                sessionFailure = "連線等待逾時，請檢查服務狀態。已收到的字幕已保留。"
            } catch (_: CancellationException) {
                // User stop during connection and disposal intentionally cancel; microphone errors supply their own message.
            } catch (failure: Exception) {
                sessionFailure = when (failure) {
                    is TranslationFailure -> safeDiagnosticField(failure.message.orEmpty(), sessionApiKey)
                    else -> "${connection.provider.label} 連線失敗。請檢查端點、模型名稱、API key 與網路狀態；已收到的字幕已保留。"
                }
                (failure as? TranslationFailure)?.diagnostic?.let { diagnostic ->
                    recordDiagnostic(connection, sessionApiKey, diagnostic, sessionFailure.orEmpty(), success = false, showDialog = !listeningStarted)
                }
            } finally {
                runCatching { services.microphone.stop() }
                queue.cancel()
                audio = null
                withContext(NonCancellable) {
                    paragraphJob?.cancelAndJoin()
                    paragraphJob = null
                    if (sessionFailure == null && paragraphModel.isNotBlank())
                        formatParagraphs(record.id, connection, sessionApiKey, paragraphModel)
                }
                val latest = mutable.value.current?.takeIf { it.id == record.id }
                if (latest != null && (latest.fragments.size != savedFragmentCount || !mutable.value.transcriptSaved)) {
                    var saved = false
                    withContext(NonCancellable + Dispatchers.Default) {
                        try { persistCurrentTranscript(record.id); saved = true }
                        catch (_: Exception) { sessionFailure = "最後收到的字幕無法寫入裝置。請先從逐字稿匯出目前內容，再確認儲存空間。" }
                    }
                    mutable.value = mutable.value.copy(transcriptSaved = saved)
                }
                mutable.value = mutable.value.copy(
                    phase = if (sessionFailure != null) ConnectionPhase.ERROR else ConnectionPhase.IDLE,
                    // A stopped session must leave saved captions readable. Diagnostics are opt-in.
                    message = if (listeningStarted && sessionFailure != null) null else sessionFailure ?: mutable.value.message,
                    showReportInMessage = if (listeningStarted && sessionFailure != null) false else mutable.value.showReportInMessage,
                    sessionIssue = sessionFailure,
                    audioInputStatus = AudioInputStatus(),
                )
                // Do not dismiss the Android foreground service until the final network
                // frames and transcript writes have completed.
                services.microphone.releaseSession()
            }
        }
    }

    private suspend fun persistCurrentTranscript(id: String) = transcriptWrites.withLock {
        val latest = mutable.value.current?.takeIf { it.id == id } ?: return@withLock
        withContext(Dispatchers.Default) { repository.saveTranscript(latest) }
    }

    private suspend fun formatParagraphs(id: String, connection: FoundryConnection, key: String, model: String) {
        val snapshot = mutable.value.current?.takeIf { it.id == id } ?: return
        val windows = paragraphWindows(snapshot)
        if (windows.isEmpty() || windows.all { w -> snapshot.paragraphLayouts.any { it.channel == w.channel && it.source == w.source } }) return
        try {
            mutable.value = mutable.value.copy(paragraphStatus = "AI 正在整理段落…")
            val formatter = paragraphFormatter ?: HttpParagraphFormatter(services.createHttpClient()).also { paragraphFormatter = it }
            val layouts = withTimeout(22_000) { formatter.format(connection, key, model, snapshot) }
            val latest = mutable.value.current?.takeIf { it.id == id } ?: return
            val accepted = layouts.filter { layout ->
                captionSourceText(latest, layout.channel).startsWith(layout.source) &&
                    layout.breakOffsets.all { it in sentenceEnds(layout.source) }
            }
            if (accepted.isEmpty()) {
                mutable.value = mutable.value.copy(paragraphStatus = "字幕已更新，等待下一次 AI 分段。")
                return
            }
            replaceRecord(latest.copy(paragraphLayouts = latest.paragraphLayouts.filter { old -> accepted.none { it.channel == old.channel } } + accepted))
            mutable.value = mutable.value.copy(transcriptSaved = false)
            persistCurrentTranscript(id)
            mutable.value = mutable.value.copy(paragraphStatus = "AI 語意分段已更新", transcriptSaved = true)
        } catch (_: TimeoutCancellationException) {
            mutable.value = mutable.value.copy(paragraphStatus = "AI 分段逾時，暫以基本分段顯示。")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            mutable.value = mutable.value.copy(paragraphStatus = "AI 分段暫時無法使用，請檢查設定的文字模型與配額；字幕仍持續更新。")
        }
    }

    private fun microphoneFailed(message: String, recordId: String?) {
        scope.launch {
            if (!mutable.value.active || mutable.value.current?.id != recordId) return@launch
            sessionFailure = message
            services.microphone.stop()
            sessionJob?.cancel()
        }
    }

    fun stop() {
        when (mutable.value.phase) {
            ConnectionPhase.CONNECTING -> {
                mutable.value = mutable.value.copy(phase = ConnectionPhase.STOPPING)
                services.microphone.stop()
                sessionJob?.cancel()
            }
            ConnectionPhase.LISTENING -> {
                mutable.value = mutable.value.copy(phase = ConnectionPhase.STOPPING)
                services.microphone.stop()
                // A normal channel close drains captured audio before the transport sends Stop.
                audio?.close()
            }
            else -> Unit
        }
    }

    private fun replaceRecord(record: TranscriptRecord) {
        mutable.value = mutable.value.copy(
            current = if (mutable.value.current?.id == record.id) record else mutable.value.current,
            history = mutable.value.history.map { if (it.id == record.id) record else it },
        )
    }

    fun renameTranscript(id: String, title: String) {
        if (mutable.value.active || title.isBlank()) return
        val record = mutable.value.history.find { it.id == id } ?: return
        saveEditedRecord(record.copy(title = title.trim().take(120)))
    }

    fun labelFragment(recordId: String, fragmentId: String, label: String) {
        labelFragments(recordId, listOf(fragmentId), label)
    }

    fun labelFragments(recordId: String, fragmentIds: List<String>, label: String) {
        if (mutable.value.active) return
        val record = mutable.value.history.find { it.id == recordId } ?: return
        val revised = record.copy(fragments = record.fragments.map { fragment ->
            if (fragment.id in fragmentIds && fragment.channel == TranscriptChannel.INPUT)
                fragment.copy(speakerLabel = label.trim().take(80).ifBlank { null }) else fragment
        })
        saveEditedRecord(revised)
    }

    private fun saveEditedRecord(record: TranscriptRecord) {
        try { repository.saveTranscript(record); replaceRecord(record) }
        catch (_: Exception) { inform("逐字稿修改無法儲存，請重試。") }
    }

    fun deleteTranscript(id: String) {
        if (mutable.value.active || !mutable.value.storageAvailable) return
        try {
            repository.deleteTranscript(id)
            mutable.value = mutable.value.copy(history = mutable.value.history.filterNot { it.id == id },
                current = mutable.value.current?.takeUnless { it.id == id })
        } catch (_: Exception) { inform("逐字稿無法刪除，請重試。") }
    }

    fun export(record: TranscriptRecord, format: String, channel: TranscriptChannel) {
        try {
            val suffix = if (channel == TranscriptChannel.INPUT) "original" else "translation"
            val text = when (format) {
                "srt" -> SubtitleExporter.srt(record, channel)
                "vtt" -> SubtitleExporter.vtt(record, channel)
                "json" -> WireJson.encodeToString(record)
                else -> SubtitleExporter.text(record)
            }
            val mime = when (format) { "vtt" -> "text/vtt"; "json" -> "application/json"; "srt" -> "application/x-subrip"; else -> "text/plain" }
            val filename = "LiveLingo_${record.createdAtMs}_${if (format in setOf("srt", "vtt")) suffix else "transcript"}.$format"
            services.exporter.export(filename, mime, text)
        } catch (_: Exception) { inform("無法匯出逐字稿，請確認可用的分享或檔案 App。") }
    }

    fun dispose() {
        runCatching { services.microphone.stop() }
        runCatching { services.microphone.releaseSession() }
        audio?.cancel()
        scope.cancel()
        transport.close()
        paragraphFormatter?.close()
        apiKey = ""
    }
}

/** Only curated error fields enter this report; headers, provider bodies and exception text are excluded. */
internal fun safeDiagnosticField(value: String, secret: String): String {
    val masked = if (secret.isEmpty()) value else value.replace(secret, "[已遮蔽]")
    return masked.map { if (it.code < 0x20 || it.code == 0x7f) ' ' else it }.joinToString("").take(400)
}

@OptIn(ExperimentalTime::class)
private fun connectionReport(connection: FoundryConnection, diagnostic: FoundryDiagnostic, message: String,
                             success: Boolean, timestamp: Long, secret: String, warning: Boolean = false): String {
    val endpointFamily = when {
        connection.endpoint.lowercase().contains(".openai.azure.com") -> "<resource>.openai.azure.com"
        connection.endpoint.lowercase().contains(".services.ai.azure.com") -> "<resource>.services.ai.azure.com"
        connection.endpoint.lowercase().contains(".cognitiveservices.azure.com") -> "<resource>.cognitiveservices.azure.com"
        else -> "<已遮蔽資源主機>"
    }
    val originalTime = Instant.fromEpochMilliseconds(timestamp).toString()
    val taiwanTime = Instant.fromEpochMilliseconds(timestamp + 8 * 60 * 60 * 1000).toString().removeSuffix("Z")
    return buildString {
        appendLine("LiveLingo 0.1.9 · ${connection.provider.label} 連線診斷")
        appendLine("時間：$originalTime（台灣時間：$taiwanTime +08:00）")
        appendLine("結果：${if (warning) "通知（尚未收到工作階段結束事件）" else if (success) "成功" else "失敗"}")
        appendLine("階段：${safeDiagnosticField(diagnostic.stage, secret)}")
        appendLine("分類：${safeDiagnosticField(diagnostic.code, secret)}")
        appendLine("HTTP 狀態：${diagnostic.httpStatus?.toString() ?: if (diagnostic.stage in setOf(FoundryStages.STREAM, FoundryStages.STOP)) "不適用（WebSocket 事件）" else "未取得"}")
        if (diagnostic.stage in setOf(FoundryStages.START, FoundryStages.STREAM, FoundryStages.STOP)) {
            appendLine("WebSocket 關閉碼：${safeWebSocketCloseCode(diagnostic.webSocketCloseCode)?.toString() ?: "未取得"}")
            appendLine("工作階段結束原因：${safeSessionCloseReason(diagnostic.sessionCloseReason) ?: "未收到可辨識的結束原因"}")
        }
        appendLine("認證方式：${if (connection.provider == ModelProvider.OPENAI) "Authorization: Bearer" else "api-key 標頭"}")
        val safePath = when (connection.speechModel) {
            SpeechModel.GPT_LIVE -> "/live/sessions"
            SpeechModel.REALTIME_TRANSLATE -> "/realtime/translations"
            SpeechModel.LIVE_TRANSCRIBE -> "/realtime?intent=transcription"
            SpeechModel.MAI_TRANSCRIBE -> "/realtime?intent=transcription"
        }
        val basePath = if (connection.speechModel == SpeechModel.MAI_TRANSCRIBE) "/mai/v1" else if (connection.provider == ModelProvider.OPENAI) "/v1" else "/openai/v1"
        appendLine("資源：wss://${if (connection.provider == ModelProvider.OPENAI) "api.openai.com" else endpointFamily}$basePath$safePath")
        appendLine("語音模型類型：${connection.speechModel.label}")
        appendLine("${if (connection.provider == ModelProvider.OPENAI) "模型" else "部署"}名稱：已遮蔽（${connection.deployment.length} 個字元）")
        diagnostic.providerCode?.let { appendLine("服務錯誤碼：${safeDiagnosticField(it, secret)}") }
        diagnostic.requestId?.let { appendLine("Request ID：${safeDiagnosticField(it, secret)}") }
        appendLine("說明：${safeDiagnosticField(message, secret)}")
        appendLine("此報告不含 API key、授權標頭、背景知識、音訊或逐字稿。")
    }
}
