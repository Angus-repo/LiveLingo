package app.livelingo.app

import app.livelingo.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) {
    val active: Boolean get() = phase in setOf(ConnectionPhase.CONNECTING, ConnectionPhase.LISTENING, ConnectionPhase.STOPPING)
    val busy: Boolean get() = active || connectionTesting
}

class AppController(
    private val services: PlatformServices,
    private val transport: TranslationTransport = KtorFoundryTransport(services.createHttpClient()),
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
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

    init {
        try {
            val preferences = repository.preferences()
            val knowledge = repository.knowledge()
            val history = repository.history()
            val connection = FoundryConnection(preferences.foundryEndpoint, preferences.deployment)
            var setupMessage: String? = null
            apiKey = if (connection.validationError() == null) {
                try { repository.apiKey(connection.webSocketUrl()) }
                catch (_: Exception) {
                    setupMessage = "無法讀取已儲存的 API key。逐字稿與背景知識仍可使用；請到設定移除或重新輸入 API key。"
                    ""
                }
            } else ""
            try { repository.migrateLegacyConnection(preferences) }
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

    private fun changePreferences(value: AppPreferences) {
        if (!mutable.value.storageAvailable) return
        try { repository.savePreferences(value); mutable.value = mutable.value.copy(preferences = value) }
        catch (_: Exception) { inform("設定無法儲存，請確認裝置儲存空間。") }
    }

    fun saveConnection(endpoint: String, deployment: String, replacementApiKey: String): Boolean {
        if (mutable.value.busy || !mutable.value.storageAvailable) return false
        val resolved = resolveConnection(endpoint, deployment, replacementApiKey) ?: return false
        val connection = resolved.connection
        val canonicalEndpoint = connection.webSocketUrl()
        val previous = FoundryConnection(mutable.value.preferences.foundryEndpoint, mutable.value.preferences.deployment)
        val previousEndpoint = if (previous.validationError() == null) previous.webSocketUrl() else null
        val preferences = mutable.value.preferences.copy(foundryEndpoint = connection.endpoint, deployment = connection.deployment)
        return try {
            repository.saveApiKey(canonicalEndpoint, resolved.apiKey)
            repository.savePreferences(preferences)
            apiKey = resolved.apiKey
            mutable.value = mutable.value.copy(preferences = preferences, hasApiKey = true, message = "Foundry 連線設定已儲存。", showReportInMessage = false)
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

    private fun resolveConnection(endpoint: String, deployment: String, replacementApiKey: String): ResolvedConnection? {
        val connection = FoundryConnection(endpoint.trim(), deployment.trim())
        connection.validationError()?.let { inform(it); return null }
        val canonicalEndpoint = connection.webSocketUrl()
        val previous = FoundryConnection(mutable.value.preferences.foundryEndpoint, mutable.value.preferences.deployment)
        val previousEndpoint = if (previous.validationError() == null) previous.webSocketUrl() else null
        if (canonicalEndpoint != previousEndpoint && replacementApiKey.isEmpty()) {
            inform("新增或變更 Foundry 端點時，請重新輸入這個資源的 API key。")
            return null
        }
        if (replacementApiKey.any { it.code < 0x20 || it.code == 0x7f }) {
            inform("API key 不能包含換行或控制字元。")
            return null
        }
        val nextKey = if (replacementApiKey.isEmpty()) apiKey else replacementApiKey.trim()
        if (nextKey.isBlank() || nextKey.any { it.isWhitespace() }) {
            inform("請輸入有效的 Foundry API key，且不要包含空白。")
            return null
        }
        return ResolvedConnection(connection, nextKey)
    }

    fun clearApiKey() {
        if (mutable.value.busy || !mutable.value.storageAvailable) return
        try {
            repository.clearApiKey(); apiKey = ""
            mutable.value = mutable.value.copy(hasApiKey = false, message = "已移除 Foundry API key。", showReportInMessage = false)
        } catch (_: Exception) { inform("無法移除 API key，請重試。") }
    }

    /** Tests the displayed form without saving it, recording audio, or selecting any background knowledge. */
    fun testConnection(endpoint: String, deployment: String, replacementApiKey: String) {
        if (mutable.value.busy || !mutable.value.storageAvailable) return
        val resolved = resolveConnection(endpoint, deployment, replacementApiKey) ?: return
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
                                 message: String, success: Boolean) {
        val safeMessage = safeDiagnosticField(message, secret)
        mutable.value = mutable.value.copy(
            message = safeMessage,
            lastConnectionReport = connectionReport(connection, diagnostic, safeMessage, success, services.currentTimeMillis(), secret),
            lastConnectionReportTitle = if (success) "連線測試成功" else "Foundry 連線失敗",
            showReportInMessage = true,
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
        val connection = FoundryConnection(snapshot.preferences.foundryEndpoint, snapshot.preferences.deployment)
        connection.validationError()?.let { inform("請先到「設定」完成 Foundry 連線。$it"); return }
        if (apiKey.isBlank()) { inform("請先到「設定」儲存 Foundry API key。"); return }
        val sessionApiKey = apiKey
        val settings = SessionSettings(snapshot.preferences.source, snapshot.preferences.target,
            snapshot.knowledge.find { it.id == snapshot.preferences.knowledgeId })
        settings.validationError()?.let { inform(it); return }
        val now = services.currentTimeMillis()
        val record = TranscriptRecord(services.uniqueId(), "${settings.sourceLanguage.label} → ${settings.targetLanguage.label}", now, settings)
        try { repository.saveTranscript(record) }
        catch (_: Exception) { inform("無法建立逐字稿，尚未開始收音。請確認儲存空間。"); return }
        val queue = Channel<ByteArray>(capacity = 30)
        audio = queue
        sessionFailure = null
        var savedFragmentCount = 0
        mutable.value = snapshot.copy(phase = ConnectionPhase.CONNECTING, current = record, transcriptSaved = true,
            history = listOf(record) + snapshot.history, message = null, lastConnectionReport = null, showReportInMessage = false)
        sessionJob = scope.launch {
            try {
                transport.run(connection, sessionApiKey, settings, queue,
                    onReady = {
                        ensureActive()
                        if (mutable.value.phase != ConnectionPhase.CONNECTING) throw CancellationException()
                        try { services.microphone.start(
                            onAudio = { bytes ->
                                val currentState = mutable.value
                                if (currentState.current?.id == record.id && currentState.active) {
                                    val result = queue.trySend(bytes.copyOf())
                                    if (result.isFailure && !result.isClosed) microphoneFailed("網路傳送速度不足，已停止收音以避免遺失音訊。已收到的字幕已保留。")
                                }
                            },
                            onError = ::microphoneFailed,
                        ) } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            throw TranslationFailure("無法啟用麥克風。請允許麥克風權限，並確認沒有其他 App 或通話占用麥克風，再重新開始。")
                        }
                        ensureActive()
                        mutable.value = mutable.value.copy(phase = ConnectionPhase.LISTENING)
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
                            mutable.value = mutable.value.copy(transcriptSaved = false)
                            try {
                                withContext(Dispatchers.Default) { repository.saveTranscript(next) }
                                savedFragmentCount = next.fragments.size
                                mutable.value = mutable.value.copy(transcriptSaved = true)
                            }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { throw TranslationFailure("逐字稿無法寫入裝置，已停止收音。請先匯出目前逐字稿，再確認儲存空間。") }
                        }
                    })
            } catch (_: TimeoutCancellationException) {
                sessionFailure = "連線等待逾時，請檢查服務狀態。已收到的字幕已保留。"
            } catch (_: CancellationException) {
                // User stop during connection and disposal intentionally cancel; microphone errors supply their own message.
            } catch (failure: Exception) {
                sessionFailure = when (failure) {
                    is TranslationFailure -> safeDiagnosticField(failure.message.orEmpty(), sessionApiKey)
                    else -> "Foundry 連線失敗。請檢查端點、部署名稱、API key 與網路狀態；已收到的字幕已保留。"
                }
                (failure as? TranslationFailure)?.diagnostic?.let { diagnostic ->
                    recordDiagnostic(connection, sessionApiKey, diagnostic, sessionFailure.orEmpty(), success = false)
                }
            } finally {
                runCatching { services.microphone.stop() }
                queue.cancel()
                audio = null
                val latest = mutable.value.current?.takeIf { it.id == record.id }
                if (latest != null && latest.fragments.size != savedFragmentCount) {
                    var saved = false
                    withContext(NonCancellable + Dispatchers.Default) {
                        try { repository.saveTranscript(latest); saved = true }
                        catch (_: Exception) { sessionFailure = "最後收到的字幕無法寫入裝置。請先從逐字稿匯出目前內容，再確認儲存空間。" }
                    }
                    mutable.value = mutable.value.copy(transcriptSaved = saved)
                }
                mutable.value = mutable.value.copy(
                    phase = if (sessionFailure != null) ConnectionPhase.ERROR else ConnectionPhase.IDLE,
                    message = sessionFailure ?: mutable.value.message,
                )
            }
        }
    }

    private fun microphoneFailed(message: String) {
        scope.launch {
            if (!mutable.value.active) return@launch
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
        audio?.cancel()
        scope.cancel()
        transport.close()
        apiKey = ""
    }
}

/** Only curated error fields enter this report; headers, provider bodies and exception text are excluded. */
private fun safeDiagnosticField(value: String, secret: String): String {
    val masked = if (secret.isEmpty()) value else value.replace(secret, "[已遮蔽]")
    return masked.map { if (it.code < 0x20 || it.code == 0x7f) ' ' else it }.joinToString("").take(400)
}

@OptIn(ExperimentalTime::class)
private fun connectionReport(connection: FoundryConnection, diagnostic: FoundryDiagnostic, message: String,
                             success: Boolean, timestamp: Long, secret: String): String {
    val endpointFamily = when {
        connection.endpoint.lowercase().contains(".openai.azure.com") -> "<resource>.openai.azure.com"
        connection.endpoint.lowercase().contains(".services.ai.azure.com") -> "<resource>.services.ai.azure.com"
        connection.endpoint.lowercase().contains(".cognitiveservices.azure.com") -> "<resource>.cognitiveservices.azure.com"
        else -> "<已遮蔽資源主機>"
    }
    val originalTime = Instant.fromEpochMilliseconds(timestamp).toString()
    val taiwanTime = Instant.fromEpochMilliseconds(timestamp + 8 * 60 * 60 * 1000).toString().removeSuffix("Z")
    return buildString {
        appendLine("LiveLingo 0.1.2 · Foundry 連線診斷")
        appendLine("時間：$originalTime（台灣時間：$taiwanTime +08:00）")
        appendLine("結果：${if (success) "成功" else "失敗"}")
        appendLine("階段：${safeDiagnosticField(diagnostic.stage, secret)}")
        appendLine("分類：${safeDiagnosticField(diagnostic.code, secret)}")
        appendLine("HTTP 狀態：${diagnostic.httpStatus?.toString() ?: "未取得"}")
        appendLine("資源：wss://$endpointFamily/openai/v1/live/sessions")
        appendLine("部署名稱：已遮蔽（${connection.deployment.length} 個字元）")
        diagnostic.providerCode?.let { appendLine("服務錯誤碼：${safeDiagnosticField(it, secret)}") }
        diagnostic.requestId?.let { appendLine("Request ID：${safeDiagnosticField(it, secret)}") }
        appendLine("說明：${safeDiagnosticField(message, secret)}")
        appendLine("此報告不含 API key、授權標頭、背景知識、音訊或逐字稿。")
    }
}
