@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.livelingo.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.livelingo.core.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

private val Ink = Color(0xFF17342F)
private val Mint = Color(0xFF147D68)
private val Canvas = Color(0xFFF4F7F4)
private val Purple = Color(0xFF6955A3)
private val Palette = lightColorScheme(primary = Mint, onPrimary = Color.White,
    secondary = Purple, background = Canvas, surface = Color.White,
    onSurface = Ink, onBackground = Ink, primaryContainer = Color(0xFFD8F2E8),
    secondaryContainer = Color(0xFFEDE7FA), surfaceVariant = Color(0xFFE8EEEA))

private enum class Page(val label: String, val glyph: String) {
    LIVE("即時翻譯", "≋"), KNOWLEDGE("背景知識", "◇"), HISTORY("逐字稿", "▤"), SETTINGS("設定", "⚙")
}

@Composable
fun LiveLingoApp(services: PlatformServices, retainedController: AppController? = null) {
    // Android owns a process-scoped controller so background sessions survive Activity recreation.
    // Other platforms still dispose their UI-scoped controller as before.
    val controller = remember(services, retainedController) { retainedController ?: AppController(services) }
    DisposableEffect(controller, retainedController) {
        onDispose { if (retainedController == null) controller.dispose() }
    }
    val state by controller.state.collectAsState()
    var page by remember { mutableStateOf(Page.LIVE) }
    val clipboard = LocalClipboardManager.current
    var copiedReport by remember(state.lastConnectionReport) { mutableStateOf(false) }
    MaterialTheme(colorScheme = Palette) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Canvas).safeDrawingPadding()) {
            val wide = maxWidth >= 760.dp
            Row(Modifier.fillMaxSize()) {
                if (wide) NavigationRail(containerColor = Color.White, modifier = Modifier.fillMaxHeight(), windowInsets = WindowInsets(0, 0, 0, 0)) {
                    Spacer(Modifier.height(20.dp))
                    BrandMark()
                    Spacer(Modifier.height(26.dp))
                    Page.entries.forEach { item ->
                        NavigationRailItem(selected = item == page, onClick = { page = item },
                            icon = { Text(item.glyph, fontSize = 23.sp) }, label = { Text(item.label) })
                    }
                }
                Column(Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (!wide) BrandMark()
                            Column(Modifier.weight(1f)) {
                                Text("LiveLingo", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("即時譯 · 讓對話跨越語言", style = MaterialTheme.typography.labelMedium, color = Mint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        StatusPill(state.phase, state.connectionTesting)
                    }
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        Box(Modifier.widthIn(max = 980.dp).fillMaxSize()) {
                            when (page) {
                                Page.LIVE -> LiveScreen(state, controller, onSettings = { page = Page.SETTINGS })
                                Page.KNOWLEDGE -> KnowledgeScreen(state, controller)
                                Page.HISTORY -> HistoryScreen(state, controller)
                                Page.SETTINGS -> SettingsScreen(state, controller)
                            }
                        }
                    }
                    if (!wide) NavigationBar(containerColor = Color.White, windowInsets = WindowInsets(0, 0, 0, 0)) {
                        Page.entries.forEach { item ->
                            NavigationBarItem(selected = page == item, onClick = { page = item },
                                icon = { Text(item.glyph, fontSize = 22.sp) }, label = { Text(item.label, maxLines = 1) })
                        }
                    }
                }
            }
        }
        state.message?.let { message ->
            AlertDialog(onDismissRequest = controller::dismissMessage,
                title = { Text(if (state.showReportInMessage) state.lastConnectionReportTitle else "提示") },
                text = { SelectionContainer {
                    Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(message)
                        if (state.showReportInMessage) state.lastConnectionReport?.let {
                            HorizontalDivider()
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } },
                confirmButton = { TextButton(onClick = controller::dismissMessage) { Text("知道了") } },
                dismissButton = {
                    if (state.showReportInMessage && state.lastConnectionReport != null) {
                        TextButton(onClick = { clipboard.setText(AnnotatedString(state.lastConnectionReport!!)); copiedReport = true }) {
                            Text(if (copiedReport) "已複製" else "複製診斷")
                        }
                    }
                })
        }
    }
}

@Composable private fun BrandMark() {
    Box(Modifier.size(42.dp).background(Mint, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
        Text("譯", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
    }
}

@Composable private fun StatusPill(phase: ConnectionPhase, testing: Boolean = false) {
    val text = if (testing) "測試連線" else when (phase) {
        ConnectionPhase.IDLE -> "待命"
        ConnectionPhase.CONNECTING -> "準備中"
        ConnectionPhase.LISTENING -> "收音中"
        ConnectionPhase.STOPPING -> "整理字幕"
        ConnectionPhase.ERROR -> "已停止"
    }
    Surface(shape = CircleShape, color = if (phase == ConnectionPhase.LISTENING) Mint else Color(0xFFE6ECE7)) {
        Text("● $text", Modifier.padding(horizontal = 12.dp, vertical = 7.dp), fontSize = 12.sp,
            color = if (phase == ConnectionPhase.LISTENING) Color.White else Ink)
    }
}

@Composable private fun SectionCard(title: String? = null, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF657971)) }
            content()
        }
    }
}

@Composable private fun Choice(label: String, selected: String, choices: List<Pair<String, String>>, enabled: Boolean = true, wrapSelection: Boolean = false, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color(0xFF657971))
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = if (wrapSelection) 64.dp else 48.dp), shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
                Text(selected, modifier = Modifier.weight(1f), maxLines = if (wrapSelection) 3 else 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium)
                Text("⌄")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                choices.forEach { (id, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; onSelect(id) }) }
            }
        }
    }
}

@Composable private fun LiveScreen(state: AppState, controller: AppController, onSettings: () -> Unit) {
    var customSpeaker by remember { mutableStateOf(false) }
    var speakerName by remember { mutableStateOf("") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var audioHelp by rememberSaveable { mutableStateOf(false) }
    var speakerOptions by rememberSaveable { mutableStateOf(false) }
    var editSetup by remember(state.current?.id) { mutableStateOf(false) }
    val source = state.preferences.source
    val target = state.preferences.target
    val knowledge = state.knowledge.find { it.id == state.preferences.knowledgeId }
    val audioSource = state.preferences.audioSource
    val audioUnavailable = controller.audioSourceUnavailableReason()
    val needsConnection = !state.hasApiKey || state.preferences.connection().validationError() != null
    val listState = rememberLazyListState()
    LaunchedEffect(state.active) { if (state.active) listState.scrollToItem(0) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!state.active && (state.current == null || editSetup)) item {
                SectionCard("準備翻譯") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            Choice("聽到的語言", if (state.preferences.connection().speechModel == SpeechModel.REALTIME_TRANSLATE) "自動偵測（模型決定）" else source.label, Language.entries.map { it.name to it.label }, !state.busy && state.preferences.connection().speechModel != SpeechModel.REALTIME_TRANSLATE, wrapSelection = true) {
                                controller.setLanguages(Language.valueOf(it), target)
                            }
                        }
                        Text("→", modifier = Modifier.padding(top = 22.dp), color = Mint, fontSize = 22.sp)
                        Box(Modifier.weight(1f)) {
                            Choice("翻譯成", target.label, Language.entries.filterNot { it == Language.AUTO }.map { it.name to it.label }, !state.busy, wrapSelection = true) {
                                controller.setLanguages(source, Language.valueOf(it))
                            }
                        }
                    }
                    if (state.preferences.connection().speechModel == SpeechModel.REALTIME_TRANSLATE) Text("即時口譯：自動辨識語言，背景知識與翻譯策略不適用。", style = MaterialTheme.typography.bodySmall)
                    Choice("音源", audioSource.label, controller.availableAudioSources().map { it.name to it.label }, !state.busy) {
                        controller.selectAudioSource(AudioSource.valueOf(it))
                    }
                    Text(if (audioSource.isPlayback) "開始後授權音訊擷取，再切到來源 App 播放。" else "收取現場對話或喇叭播放的聲音。",
                        style = MaterialTheme.typography.bodySmall, color = Color(0xFF657971))
                    audioUnavailable?.let { Text(it, color = Color(0xFF9A473E), style = MaterialTheme.typography.bodySmall) }
                    HorizontalDivider(color = Canvas)
                    TextButton(onClick = { advanced = !advanced }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                        Text("更多選項", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Text(if (advanced) "收合 ⌃" else "展開 ⌄")
                    }
                    if (!advanced) Text(if (state.preferences.connection().speechModel == SpeechModel.REALTIME_TRANSLATE) "本模型自動決定口譯策略"
                        else "${knowledge?.name ?: "未使用背景知識"} · ${state.preferences.pace.label} · ${state.preferences.quality.label}",
                        style = MaterialTheme.typography.bodySmall, color = Color(0xFF657971))
                    if (advanced) {
                        Choice("背景知識", knowledge?.name ?: "不使用背景知識", listOf("" to "不使用背景知識") + state.knowledge.map { it.id to it.name }, !state.busy && state.preferences.connection().speechModel != SpeechModel.REALTIME_TRANSLATE) {
                            controller.selectKnowledge(it.ifBlank { null })
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.weight(1f)) {
                                Choice("翻譯速度", state.preferences.pace.label, TranslationPace.entries.map { it.name to it.label }, !state.busy && state.preferences.connection().speechModel != SpeechModel.REALTIME_TRANSLATE) {
                                    controller.setTranslationOptions(TranslationPace.valueOf(it), state.preferences.quality)
                                }
                            }
                            Box(Modifier.weight(1f)) {
                                Choice("翻譯品質", state.preferences.quality.label, TranslationQuality.entries.map { it.name to it.label }, !state.busy && state.preferences.connection().speechModel != SpeechModel.REALTIME_TRANSLATE) {
                                    controller.setTranslationOptions(state.preferences.pace, TranslationQuality.valueOf(it))
                                }
                            }
                        }
                        Text("優先即時會更快開始翻譯；平衡模式多保留一些上下文。實際速度與準確度仍依服務及收音而定。",
                            style = MaterialTheme.typography.bodySmall, color = Color(0xFF657971))
                        if (source == Language.AUTO) Text("語言名稱依原文字幕推測，無法只靠文字辨別口音。", style = MaterialTheme.typography.bodySmall, color = Color(0xFF657971))
                        if (source == Language.INDIAN_ENGLISH) Text("印度英文會加入口音與用語提示，實際辨識仍取決於收音品質。", style = MaterialTheme.typography.bodySmall, color = Color(0xFF657971))
                    }
                    TextButton(onClick = { audioHelp = !audioHelp }, contentPadding = PaddingValues(0.dp)) { Text(if (audioHelp) "收合收音說明 ⌃" else "收音與背景執行說明 ⌄") }
                    if (audioHelp) Text(if (audioSource.isPlayback)
                        "裝置播放音訊只擷取允許錄製的媒體，不混入麥克風。其他 App 的播放聲音也可能被收取，通話或禁止錄製的內容可能無聲。每次開始須同意 Android 的螢幕分享／錄製授權；LiveLingo 只讀取音訊、不擷取畫面。鎖定螢幕可能停止擷取。"
                    else "Android 開始翻譯後可切換 App 或鎖定螢幕，並從常駐通知停止。iOS 目前須保持 App 在前景。",
                        style = MaterialTheme.typography.bodySmall, color = Color(0xFF657971))
                    if (editSetup) TextButton(onClick = { editSetup = false }) { Text("完成設定，回到字幕") }
                }
            }
            if (state.active || (state.current != null && !editSetup)) item {
                SectionCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${if (state.active) liveSourceLanguageLabel(source, state.current) else source.label} → ${target.label}", fontWeight = FontWeight.SemiBold)
                            Text("${audioSource.label} · ${state.preferences.provider.label}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF657971))
                        }
                        if (!state.active) TextButton(onClick = { editSetup = true }, enabled = !state.busy) { Text("調整設定") }
                    }
                    if (state.phase == ConnectionPhase.LISTENING && controller.reportsAudioLevel()) {
                        LinearProgressIndicator(progress = { state.audioInputStatus.level }, modifier = Modifier.fillMaxWidth(), color = Mint)
                        Text(if (state.audioInputStatus.signalPresent) "正在收音" else "等待聲音…", style = MaterialTheme.typography.labelSmall, color = Color(0xFF657971))
                        state.audioInputStatus.warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF9A473E)) }
                    }
                }
            }
            if (state.sessionIssue != null || (state.current?.contentFilterCount ?: 0) > 0) item {
                SectionCard(if (state.sessionIssue != null) "翻譯已中斷" else "部分片段可能不完整") {
                    Text(if (state.sessionIssue != null && (state.current?.contentFilterCount ?: 0) > 0)
                        "收到內容篩選通知後，翻譯連線已結束。最後片段可能不完整，詳細原因請查看診斷。"
                    else state.sessionIssue ?: state.streamNotice ?: "本次有內容安全篩選記錄，已收到的字幕仍保留。",
                        style = MaterialTheme.typography.bodySmall, color = Color(0xFF9A473E))
                    if (!state.active) Text(if (state.transcriptSaved) "逐字稿已儲存。點下方「開始下一段」即可收取新的音訊。" else "逐字稿尚未完整儲存，請先到「逐字稿」匯出。",
                        style = MaterialTheme.typography.bodySmall, color = Ink)
                    if (state.lastConnectionReport != null) TextButton(onClick = controller::showConnectionReport) { Text("查看診斷") }
                }
            }
            if (state.current != null) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(if (state.active) "即時字幕" else "上一段字幕", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(when { state.current.fragments.isEmpty() -> "等待對話"; state.transcriptSaved -> "已自動儲存"; state.phase == ConnectionPhase.ERROR -> "請先匯出"; else -> "儲存中" },
                            style = MaterialTheme.typography.labelMedium, color = Mint)
                    }
                }
                item { TranscriptPanel("譯文", state.current.settings.targetLanguage.label, state.current, TranscriptChannel.OUTPUT, Purple) }
                item { TranscriptPanel("原文", liveSourceLanguageLabel(state.current.settings.sourceLanguage, state.current), state.current, TranscriptChannel.INPUT, Mint) }
                state.paragraphStatus?.let { status -> item { Text(status, style = MaterialTheme.typography.bodySmall, color = Color(0xFF657971)) } }
                if (state.active) item {
                    SectionCard {
                        TextButton(onClick = { speakerOptions = !speakerOptions }, contentPadding = PaddingValues(0.dp)) { Text("發言者：${state.speaker}  ${if (speakerOptions) "⌃" else "⌄"}") }
                        if (speakerOptions) {
                            Text("手動標記接下來收到的原文，可在逐字稿修正。", style = MaterialTheme.typography.bodySmall, color = Color(0xFF657971))
                            Choice("發言者標記", state.speaker,
                                listOf("未標記" to "未標記", "發言者 A" to "發言者 A", "發言者 B" to "發言者 B", "發言者 C" to "發言者 C", "CUSTOM" to "自訂姓名…")) {
                                if (it == "CUSTOM") { speakerName = ""; customSpeaker = true } else controller.setSpeaker(it)
                            }
                        }
                    }
                }
                if (!state.active && state.current.fragments.isNotEmpty()) item {
                    Text("完整內容與字幕匯出可在「逐字稿」查看。開始下一段後，這一段仍會保留。", style = MaterialTheme.typography.bodySmall, color = Color(0xFF657971))
                }
            }
        }
        // Outside the scrolling content: the primary action remains reachable above navigation.
        Surface(color = Color.White, shadowElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!state.active) Text("音訊${if (knowledge != null) "與所選背景知識" else ""}會傳至 ${state.preferences.provider.label}。",
                    style = MaterialTheme.typography.labelSmall, color = Color(0xFF657971))
                Button(onClick = { if (state.active) controller.stop() else if (needsConnection) onSettings() else controller.start() },
                    enabled = state.phase != ConnectionPhase.STOPPING && state.storageAvailable && !state.connectionTesting &&
                        (state.active || (state.transcriptSaved && audioUnavailable == null)),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (state.active) Color(0xFF9A473E) else Mint)) {
                    if (state.phase in setOf(ConnectionPhase.CONNECTING, ConnectionPhase.STOPPING)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(if (state.connectionTesting) "連線測試進行中…" else when (state.phase) {
                        ConnectionPhase.CONNECTING -> "取消連線／音源授權"
                        ConnectionPhase.LISTENING -> "■  停止並儲存"
                        ConnectionPhase.STOPPING -> "接收最後的字幕…"
                        else -> if (needsConnection) "先設定翻譯服務 →" else if (state.current == null) "開始即時翻譯" else "開始下一段"
                    }, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    if (customSpeaker) AlertDialog(onDismissRequest = { customSpeaker = false }, title = { Text("自訂發言者") },
        text = { OutlinedTextField(speakerName, { speakerName = it.take(80) }, label = { Text("姓名或稱呼") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { controller.setSpeaker(speakerName); customSpeaker = false }) { Text("套用") } },
        dismissButton = { TextButton(onClick = { customSpeaker = false }) { Text("取消") } })
}

@Composable private fun TranscriptPanel(title: String, language: String, record: TranscriptRecord?, channel: TranscriptChannel, color: Color) {
    // Join split words first, then preserve model/AI paragraph boundaries.
    val text = liveCaptionText(record, channel)
    val scroll = rememberScrollState()
    LaunchedEffect(record?.id, text, scroll.maxValue) {
        // maxValue updates after text layout; following it keeps the newest character visible.
        scroll.scrollTo(scroll.maxValue)
    }
    SectionCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = color, fontWeight = FontWeight.Bold)
            Text(language, color = Color(0xFF657971), style = MaterialTheme.typography.labelMedium)
        }
        if (text.isEmpty()) {
            Text(if (channel == TranscriptChannel.OUTPUT) "開始說話後，翻譯會出現在這裡。" else "原始語音的辨識文字會保留在這裡。",
                color = Color(0xFF8C9A92), modifier = Modifier.padding(vertical = 18.dp))
        } else {
            Box(Modifier.fillMaxWidth().height(230.dp).verticalScroll(scroll)) {
                SelectionContainer {
                    Text(text, fontSize = 18.sp, lineHeight = 29.sp, softWrap = true)
                }
            }
        }
    }
}

@Composable private fun KnowledgeScreen(state: AppState, controller: AppController) {
    var editorOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<BackgroundKnowledge?>(null) }
    var deleting by remember { mutableStateOf<BackgroundKnowledge?>(null) }
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageIntro("背景知識", "把術語、會議脈絡與偏好用語先準備好。每次翻譯前，選擇適合的一組。") }
        item { Button(onClick = { editing = null; editorOpen = true }, enabled = !state.active && state.storageAvailable, shape = RoundedCornerShape(12.dp)) { Text("＋ 新增背景知識") } }
        if (state.active) item { Text("翻譯進行中無法修改背景知識；停止後即可編輯。", style = MaterialTheme.typography.bodySmall) }
        if (state.knowledge.isEmpty()) item { SectionCard("尚未建立背景知識", "可以先加入專案名稱、常用縮寫與人名。內容會作為翻譯參考，不是保證採用的字典。") {} }
        items(state.knowledge, key = { it.id }) { entry ->
            SectionCard(entry.name) {
                Text(entry.content, maxLines = 5, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { controller.selectKnowledge(entry.id) }, enabled = !state.active) { Text(if (state.preferences.knowledgeId == entry.id) "✓ 已選用" else "選用") }
                    TextButton(onClick = { editing = entry; editorOpen = true }, enabled = !state.active) { Text("編輯") }
                    TextButton(onClick = { deleting = entry }, enabled = !state.active) { Text("刪除", color = Color(0xFFA8443D)) }
                }
            }
        }
    }
    if (editorOpen) KnowledgeEditor(editing, onDismiss = { editorOpen = false }, onSave = { name, text ->
        if (controller.saveKnowledge(editing?.id, name, text)) editorOpen = false
    })
    deleting?.let { entry -> ConfirmDelete("刪除「${entry.name}」？", "已儲存逐字稿中的背景知識快照會保留。", { deleting = null }) { controller.deleteKnowledge(entry.id); deleting = null } }
}

@Composable private fun KnowledgeEditor(value: BackgroundKnowledge?, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember(value?.id) { mutableStateOf(value?.name.orEmpty()) }
    var content by remember(value?.id) { mutableStateOf(value?.content.orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (value == null) "新增背景知識" else "編輯背景知識") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it.take(80) }, label = { Text("名稱") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(content, { content = it.take(12_000) }, label = { Text("場景、術語與翻譯偏好") }, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 280.dp))
            Text("${content.length} / 12,000 字。啟動翻譯時，這些內容會傳送至 Microsoft Foundry。", style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { TextButton(onClick = { onSave(name, content) }, enabled = name.isNotBlank() && content.isNotBlank()) { Text("儲存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable private fun HistoryScreen(state: AppState, controller: AppController) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<TranscriptRecord?>(null) }
    val record = state.history.find { it.id == selectedId }
    if (record != null) {
        TranscriptDetail(record, state.active, controller, onBack = { selectedId = null })
    } else {
        LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { PageIntro("對話留下來", "每一段翻譯都自動儲存在這台裝置；可回看、修正手動發言者標記，或匯出字幕。") }
            if (state.history.isEmpty()) item { SectionCard("還沒有逐字稿", "完成第一段翻譯後，內容就會出現在這裡。") {} }
            items(state.history, key = { it.id }) { item ->
                SectionCard(item.title) {
                    Text(formatDate(item.createdAtMs), style = MaterialTheme.typography.labelMedium, color = Mint)
                    Text("${item.fragments.size} 個串流片段 · ${formatTime(item.fragments.maxOfOrNull { it.endMs } ?: 0)}", style = MaterialTheme.typography.bodySmall)
                    if (item.contentFilterCount > 0) Text("有內容篩選記錄，部分片段可能不完整", style = MaterialTheme.typography.bodySmall, color = Color(0xFF9A473E))
                    item.settings.knowledge?.let { Text("背景：${it.name}", style = MaterialTheme.typography.bodySmall) }
                    Text(item.fragments.filter { it.channel == TranscriptChannel.OUTPUT }.take(8).joinToString("") { it.text }.ifBlank { "尚未收到譯文" }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row {
                        TextButton(onClick = { selectedId = item.id }) { Text("開啟逐字稿 →") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { deleting = item }, enabled = !state.active) { Text("刪除", color = Color(0xFFA8443D)) }
                    }
                }
            }
        }
    }
    deleting?.let { item -> ConfirmDelete("刪除這份逐字稿？", "此動作會刪除裝置上的記錄，無法復原；已匯出的檔案不受影響。", { deleting = null }) { controller.deleteTranscript(item.id); deleting = null } }
}

@Composable private fun TranscriptDetail(record: TranscriptRecord, active: Boolean, controller: AppController, onBack: () -> Unit) {
    var channel by remember(record.id) { mutableStateOf(TranscriptChannel.OUTPUT) }
    var exportOpen by remember { mutableStateOf(false) }
    var labeling by remember { mutableStateOf<DisplayCue?>(null) }
    var label by remember { mutableStateOf("") }
    var rename by remember { mutableStateOf(false) }
    var title by remember(record.id) { mutableStateOf(record.title) }
    var showTimestamps by remember(record.id) { mutableStateOf(false) }
    val cues = displayCues(record, channel)
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = onBack) { Text("← 所有逐字稿") } }
        item { PageIntro(record.title, formatDate(record.createdAtMs)) }
        item { Text("音源：${record.settings.audioSource.label}", style = MaterialTheme.typography.bodySmall) }
        if (record.contentFilterCount > 0) item {
            Text("本次收到 ${record.contentFilterCount} 次內容安全篩選通知，部分片段可能不完整。", style = MaterialTheme.typography.bodySmall, color = Color(0xFF9A473E))
        }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { exportOpen = true }, shape = RoundedCornerShape(12.dp)) { Text("匯出字幕") }
            OutlinedButton(onClick = { title = record.title; rename = true }, enabled = !active, shape = RoundedCornerShape(12.dp)) { Text("重新命名") }
        } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = channel == TranscriptChannel.OUTPUT, onClick = { channel = TranscriptChannel.OUTPUT }, label = { Text("譯文") })
            FilterChip(selected = channel == TranscriptChannel.INPUT, onClick = { channel = TranscriptChannel.INPUT }, label = { Text("原文") })
        } }
        item { Text("原文與譯文的時間戳各自保留，並非逐句精準對齊。發言者標記由使用者手動指定。", style = MaterialTheme.typography.bodySmall, color = Color(0xFF657971)) }
        if (cues.isEmpty()) item { SectionCard("這個軌道還沒有字幕") {} }
        else item { SectionCard("閱讀段落") {
            SelectionContainer { Text(liveCaptionText(record, channel), fontSize = 18.sp, lineHeight = 28.sp) }
            TextButton(onClick = { showTimestamps = !showTimestamps }) {
                Text(if (showTimestamps) "收起時間戳明細" else "顯示時間戳與發言者編輯")
            }
        } }
        if (showTimestamps) items(cues, key = { it.ids.first() }) { cue ->
            SectionCard {
                Text("${formatTime(cue.start)} → ${formatTime(cue.end)}", style = MaterialTheme.typography.labelMedium, color = if (channel == TranscriptChannel.INPUT) Mint else Purple)
                cue.speaker?.let { Text("手動標記：$it", style = MaterialTheme.typography.labelMedium) }
                SelectionContainer { Text(cue.text.trim(), fontSize = 18.sp, lineHeight = 28.sp) }
                if (channel == TranscriptChannel.INPUT) TextButton(onClick = { labeling = cue; label = cue.speaker.orEmpty() }, enabled = !active) { Text("標記發言者") }
            }
        }
    }
    if (exportOpen) ExportDialog(record, controller) { exportOpen = false }
    labeling?.let { cue -> AlertDialog(onDismissRequest = { labeling = null }, title = { Text("手動標記發言者") },
        text = { Column { Text("只套用到這段原文；留空可清除標記。", style = MaterialTheme.typography.bodySmall); OutlinedTextField(label, { label = it.take(80) }, label = { Text("姓名或稱呼") }, singleLine = true) } },
        confirmButton = { TextButton(onClick = { controller.labelFragments(record.id, cue.ids, label); labeling = null }) { Text("儲存") } },
        dismissButton = { TextButton(onClick = { labeling = null }) { Text("取消") } }) }
    if (rename) AlertDialog(onDismissRequest = { rename = false }, title = { Text("逐字稿名稱") },
        text = { OutlinedTextField(title, { title = it.take(120) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { controller.renameTranscript(record.id, title); rename = false }, enabled = title.isNotBlank()) { Text("儲存") } },
        dismissButton = { TextButton(onClick = { rename = false }) { Text("取消") } })
}

@Composable private fun ExportDialog(record: TranscriptRecord, controller: AppController, onDismiss: () -> Unit) {
    var format by remember { mutableStateOf("srt") }
    var channel by remember { mutableStateOf(TranscriptChannel.OUTPUT) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("匯出逐字稿") }, text = { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Choice("檔案格式", listOf("srt" to "SRT 字幕", "vtt" to "WebVTT 字幕", "txt" to "純文字（含原文與譯文）", "json" to "JSON 完整資料").first { it.first == format }.second,
            listOf("srt" to "SRT 字幕", "vtt" to "WebVTT 字幕", "txt" to "純文字（含原文與譯文）", "json" to "JSON 完整資料")) { format = it }
        if (format in setOf("srt", "vtt")) Choice("字幕軌道", if (channel == TranscriptChannel.OUTPUT) "譯文" else "原文", listOf("OUTPUT" to "譯文", "INPUT" to "原文")) { channel = TranscriptChannel.valueOf(it) }
        Text("原文與譯文分別匯出，保留各自時間戳。分享前請確認內容及手動發言者標記。", style = MaterialTheme.typography.bodySmall)
        if (format == "json") Text("JSON 會包含本次使用的背景知識全文。", style = MaterialTheme.typography.bodySmall, color = Purple)
    } }, confirmButton = { TextButton(onClick = { controller.export(record, format, channel); onDismiss() }) { Text("匯出／分享") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable private fun SettingsScreen(state: AppState, controller: AppController) {
    val provider = state.preferences.provider
    val isOpenAi = provider == ModelProvider.OPENAI
    var endpoint by remember(provider, state.preferences.foundryEndpoint) { mutableStateOf(if (isOpenAi) "https://api.openai.com" else state.preferences.foundryEndpoint) }
    var deployment by remember(provider, state.preferences.connection().deployment) { mutableStateOf(state.preferences.connection().deployment) }
    var paragraphModel by remember(provider, state.preferences.paragraphModel()) { mutableStateOf(state.preferences.paragraphModel()) }
    var speechKind by remember(provider, state.preferences.connection().speechModel) { mutableStateOf(state.preferences.connection().speechModel) }
    var textKind by remember(provider, state.preferences.connection().textModelKind) { mutableStateOf(state.preferences.connection().textModelKind) }
    var sourceTranscript by remember(provider, state.preferences.connection().sourceTranscriptionModel) { mutableStateOf(state.preferences.connection().sourceTranscriptionModel) }
    var apiKey by remember(provider) { mutableStateOf("") }
    var clearConfirm by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    var copiedReport by remember(state.lastConnectionReport) { mutableStateOf(false) }
    val previewConnection = FoundryConnection(endpoint.trim(), deployment.trim(), provider, speechKind, textKind, paragraphModel.trim(), sourceTranscript.trim())
    val webSocketPreview = if (previewConnection.validationError() == null) previewConnection.webSocketUrl() else null
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageIntro("翻譯服務與模型", "選擇服務，分別保存模型與 API key。") }
        item { SectionCard("服務提供者") {
            Choice("使用哪個服務", provider.label, ModelProvider.entries.map { it.name to it.label }, !state.busy) {
                controller.selectProvider(ModelProvider.valueOf(it))
            }
            Text("Microsoft Foundry 與 OpenAI 的設定、金鑰獨立保存，切換後可繼續使用原本的資料。", style = MaterialTheme.typography.bodySmall)
        } }
        item { SectionCard("連線設定", "API key 使用裝置的安全儲存機制加密保存。") {
            if (!isOpenAi) OutlinedTextField(endpoint, { endpoint = it }, label = { Text("Azure OpenAI／Foundry 資源端點") }, placeholder = { Text("https://your-resource.openai.azure.com") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
            else Text("OpenAI 官方端點：api.openai.com", style = MaterialTheme.typography.bodySmall, color = Mint)
            Choice("即時語音模型", speechKind.label,
                SpeechModel.entries.filter { it.supportedBy(provider) }.map { it.name to it.label }, !state.busy, wrapSelection = true) {
                val next = SpeechModel.valueOf(it)
                if (isOpenAi || deployment.isBlank() || SpeechModel.entries.any { model -> model.modelId == deployment }) deployment = next.modelId
                speechKind = next
            }
            Text(speechKind.description, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(deployment, { deployment = it }, label = { Text(if (isOpenAi) "模型 ID" else "實際語音部署名稱") }, placeholder = { Text(speechKind.modelId) }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
            Text(if (isOpenAi) "模型 ID 須對應上方模型類型，並可由此 API key 使用。" else "上方選擇部署的基礎模型類型；此欄填入 Foundry 實際部署名稱，可保留自訂名稱。", style = MaterialTheme.typography.bodySmall)
            if (speechKind == SpeechModel.REALTIME_TRANSLATE) {
                OutlinedTextField(sourceTranscript, { sourceTranscript = it }, label = { Text(if (isOpenAi) "原文字幕轉錄模型（選填）" else "原文字幕轉錄部署（選填）") },
                    placeholder = { Text("gpt-realtime-whisper") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                Text(if (isOpenAi) "使用 gpt-realtime-whisper 同時產生原文字幕；留空只顯示譯文，原文轉錄另計用量。" else "若需原文字幕，請另部署 gpt-realtime-whisper 並填入部署名稱；留空只顯示譯文。", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(apiKey, { apiKey = it }, label = { Text(if (state.hasApiKey) "新 API key（留空保留原值）" else "${provider.label} API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                enabled = !state.busy, modifier = Modifier.fillMaxWidth())
            if (state.hasApiKey) Text("✓ API key 已加密儲存在這台裝置", style = MaterialTheme.typography.bodySmall, color = Mint)
            if (!isOpenAi) Text("使用 Azure 資源的 HTTPS 根網址；變更端點時須重新輸入對應的 API key。", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("AI 語意分段", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Switch(state.preferences.aiParagraphs, controller::setAiParagraphs, enabled = !state.busy)
            }
            if (state.preferences.aiParagraphs || speechKind.transcribes) {
                Choice(if (speechKind.transcribes) "文字模型（翻譯與 AI 分段）" else "AI 分段文字模型", textKind.label,
                    TextModel.entries.map { it.name to it.label }, !state.busy, wrapSelection = true) {
                    val next = TextModel.valueOf(it)
                    if (next != TextModel.CUSTOM && (isOpenAi || paragraphModel.isBlank() || TextModel.infer(paragraphModel) != TextModel.CUSTOM)) paragraphModel = next.modelId
                    textKind = next
                }
                OutlinedTextField(paragraphModel, { paragraphModel = it }, label = { Text(if (isOpenAi) "文字模型 ID" else "實際文字部署名稱") }, placeholder = { Text("gpt-6-luna") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                Text(if (isOpenAi) "預設 GPT-6 Luna；依上方類型使用對應的請求參數。" else "預設 GPT-6 Luna；請先在同一資源部署文字模型，填入實際名稱並選擇對應類型。", style = MaterialTheme.typography.bodySmall)
                if (speechKind.transcribes) Text("每約 3 秒完成一段原文轉錄，再由文字模型翻譯；即使關閉 AI 分段，文字翻譯仍會執行並計費。", style = MaterialTheme.typography.bodySmall)
                if (state.preferences.aiParagraphs) Text("約每 20 秒與停止時整理原文、譯文的完整句子。AI 只決定段落位置，不改寫內容；失敗時保留基本分段。", style = MaterialTheme.typography.bodySmall)
            }
            webSocketPreview?.let { preview ->
                SelectionContainer { Text("實際連線網址：\n$preview", style = MaterialTheme.typography.labelSmall, color = Mint) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (controller.saveConnection(endpoint, deployment, apiKey, paragraphModel, speechKind, textKind, sourceTranscript)) apiKey = "" }, enabled = !state.busy && state.storageAvailable, shape = RoundedCornerShape(12.dp)) { Text("儲存連線設定") }
                TextButton(onClick = { clearConfirm = true }, enabled = !state.busy && state.storageAvailable) { Text("移除 API key") }
            }
            OutlinedButton(
                onClick = { if (state.connectionTesting) controller.cancelConnectionTest() else controller.testConnection(endpoint, deployment, apiKey, speechKind, textKind, paragraphModel, sourceTranscript) },
                enabled = !state.active && state.storageAvailable,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
            ) {
                if (state.connectionTesting) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Text(if (state.connectionTesting) "取消連線測試" else "測試連線")
            }
            Text("測試目前欄位中的語音模型連線，不啟用麥克風、不儲存欄位。文字翻譯與 AI 分段需有字幕時才能驗證。", style = MaterialTheme.typography.bodySmall)
            Text("測試會建立並關閉所選語音模型的工作階段，可能產生服務用量。", style = MaterialTheme.typography.bodySmall)
        } }
        state.lastConnectionReport?.let { report -> item { SectionCard("最近一次連線診斷", "資源名稱、部署名稱與金鑰已遮蔽，可將報告提供給維護人員。") {
            Text(report, style = MaterialTheme.typography.bodySmall, maxLines = 7, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = controller::showConnectionReport) { Text("查看完整診斷") }
                TextButton(onClick = { clipboard.setText(AnnotatedString(report)); copiedReport = true }) { Text(if (copiedReport) "已複製" else "複製") }
                TextButton(onClick = controller::exportConnectionReport) { Text("分享") }
            }
        } } }
        item { SectionCard("開始使用") {
            Text("1  選擇 Microsoft Foundry 或 OpenAI。\n\n2  選擇模型並填入實際部署名稱與該服務的 API key，儲存設定。\n\n3  回到即時翻譯，選語言與背景知識，允許麥克風後開始。", style = MaterialTheme.typography.bodyMedium)
        } }
        item { SectionCard("翻譯策略說明") {
            Text("開始翻譯前，可在「即時翻譯」選擇速度與品質。優先即時偏向短語盡早輸出；平衡保留較多上下文。忠實原文偏重數字、語義與完整性；自然流暢偏重譯句自然。所有策略均禁止增添未說出的事實。", style = MaterialTheme.typography.bodyMedium)
            Text("GPT-Live 的 session.start 不支援直接指定 temperature、推理強度或 Realtime VAD 靜音閾值；因此上述控制採用提示指令，不能保證固定延遲。", style = MaterialTheme.typography.bodySmall, color = Color(0xFF657971))
        } }
        item { SectionCard("資料與收音") {
            Text("逐字稿與背景知識儲存在這台裝置；App 不保存原始錄音。音訊及模型適用的背景知識只送至所選服務；轉錄方案會再傳送原文進行文字翻譯，啟用 AI 分段時也會傳送字幕。", style = MaterialTheme.typography.bodyMedium)
            Text("API key 僅用於對應服務，不會包含在逐字稿或匯出內容中。繁中台灣另經本機字詞轉換，數字不變。", style = MaterialTheme.typography.bodySmall)
            Text("Android 開始翻譯後可切換至背景或鎖定螢幕，系統會顯示麥克風前景服務通知及停止操作。來電、音訊中斷、系統強制停止或網路錯誤仍可能中止；iOS 目前需維持前景。", style = MaterialTheme.typography.bodySmall)
            Text("目前提供手動發言者標記，不自動推測多人身分。轉錄模式的字幕時間由音訊區段估算；口譯模式使用串流時間，均非逐字對齊。", style = MaterialTheme.typography.bodySmall)
        } }
        item { Text("LiveLingo 0.1.9 · Kotlin Multiplatform\nAndroid / iOS · Microsoft Foundry / OpenAI", style = MaterialTheme.typography.labelSmall, color = Color(0xFF657971), modifier = Modifier.padding(6.dp)) }
    }
    if (clearConfirm) ConfirmDelete("移除 ${provider.label} API key？", "只移除目前所選服務的金鑰。", { clearConfirm = false }) { controller.clearApiKey(); clearConfirm = false }
}

@Composable private fun PageIntro(title: String, subtitle: String) {
    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF657971), lineHeight = 23.sp)
    }
}

@Composable private fun ConfirmDelete(title: String, message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("刪除", color = Color(0xFFA8443D)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

private fun formatTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "${(seconds / 3600).toString().padStart(2, '0')}:${(seconds / 60 % 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
}

@OptIn(ExperimentalTime::class)
private fun formatDate(ms: Long): String = Instant.fromEpochMilliseconds(ms + 8 * 60 * 60 * 1000)
    .toString().replace('T', ' ').take(16) + "（台灣時間）"
