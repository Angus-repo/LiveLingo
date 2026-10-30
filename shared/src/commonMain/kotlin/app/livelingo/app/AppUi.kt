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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
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
fun LiveLingoApp(services: PlatformServices) {
    val controller = remember(services) { AppController(services) }
    DisposableEffect(controller) { onDispose { controller.dispose() } }
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
                    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (!wide) BrandMark()
                            Column {
                                Text("LiveLingo", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text("即時譯 · 讓對話跨越語言", style = MaterialTheme.typography.labelMedium, color = Mint)
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

@Composable private fun Choice(label: String, selected: String, choices: List<Pair<String, String>>, enabled: Boolean = true, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color(0xFF657971))
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 13.dp, vertical = 14.dp)) {
                Text(selected, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    val source = state.preferences.source
    val target = state.preferences.target
    val knowledge = state.knowledge.find { it.id == state.preferences.knowledgeId }
    val listState = rememberLazyListState()
    LaunchedEffect(state.active) { if (state.active) listState.scrollToItem(0) }
    LazyColumn(state = listState, contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!state.active) item {
            Box(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF153F35), Color(0xFF218675))), RoundedCornerShape(24.dp)).padding(24.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("LISTEN  ·  UNDERSTAND  ·  KEEP", color = Color(0xFFA8DECB), fontSize = 10.sp, letterSpacing = 2.sp)
                    Text("專注對話，\n讓翻譯自然跟上。", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 37.sp)
                    Text("原文與譯文即時留下，會議之後也能回看。", color = Color(0xFFD6EAE1), fontSize = 13.sp)
                }
            }
        }
        if (!state.active) item { SectionCard("準備這一場對話") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { Choice("聽到的語言", source.label, Language.entries.map { it.name to it.label }, !state.active) { controller.setLanguages(Language.valueOf(it), target) } }
                Box(Modifier.weight(1f)) { Choice("翻譯成", target.label, Language.entries.filterNot { it == Language.AUTO }.map { it.name to it.label }, !state.active) { controller.setLanguages(source, Language.valueOf(it)) } }
            }
            Choice("背景知識", knowledge?.name ?: "不使用背景知識", listOf("" to "不使用背景知識") + state.knowledge.map { it.id to it.name }, !state.active) { controller.selectKnowledge(it.ifBlank { null }) }
            if (source == Language.INDIAN_ENGLISH) Text("印度英文使用口音與用語提示；實際辨識效果仍受收音品質影響。", style = MaterialTheme.typography.bodySmall)
            if (!state.hasApiKey || state.preferences.foundryEndpoint.isBlank()) {
                TextButton(onClick = onSettings) { Text("先完成 Foundry 連線 →") }
            }
            Button(onClick = { if (state.active) controller.stop() else controller.start() },
                enabled = state.phase != ConnectionPhase.STOPPING && state.storageAvailable && !state.connectionTesting,
                modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (state.active) Color(0xFF9A473E) else Mint)) {
                if (state.phase in setOf(ConnectionPhase.CONNECTING, ConnectionPhase.STOPPING)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(Modifier.width(10.dp))
                }
                Text(if (state.connectionTesting) "連線測試進行中…" else when (state.phase) {
                    ConnectionPhase.CONNECTING -> "取消連線／麥克風授權"
                    ConnectionPhase.LISTENING -> "■  停止並儲存"
                    ConnectionPhase.STOPPING -> "接收最後的字幕…"
                    else -> if (state.current == null) "●  開始即時翻譯" else "●  開始新的一段翻譯"
                }, fontWeight = FontWeight.SemiBold)
            }
            Text("音訊與所選背景知識會直接傳至 Microsoft Foundry。此版只顯示字幕；翻譯期間請保持 App 在前景。", style = MaterialTheme.typography.bodySmall, color = Color(0xFF657971))
        } }
        if (state.active) item { SectionCard {
            Text("${source.label} → ${target.label}", fontWeight = FontWeight.Bold)
            Text("背景：${knowledge?.name ?: "無"}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF657971))
            Button(onClick = controller::stop, enabled = state.phase != ConnectionPhase.STOPPING,
                modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9A473E))) {
                Text(when (state.phase) {
                    ConnectionPhase.CONNECTING -> "取消連線／麥克風授權"
                    ConnectionPhase.STOPPING -> "接收最後的字幕…"
                    else -> "■  停止並儲存"
                })
            }
        } }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("即時字幕", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(when { state.current?.fragments?.isNotEmpty() != true -> "等待對話"; state.transcriptSaved -> "已自動儲存"; state.phase == ConnectionPhase.ERROR -> "請先匯出"; else -> "儲存中" }, style = MaterialTheme.typography.labelMedium, color = Mint)
            }
        }
        item { TranscriptPanel("譯文", target.label, state.current, TranscriptChannel.OUTPUT, Purple) }
        item { TranscriptPanel("原文", source.label, state.current, TranscriptChannel.INPUT, Mint) }
        item { SectionCard("發言者標記", "手動標記接下來收到的原文；不是自動聲紋辨識。字幕可能延遲抵達，結束後可在逐字稿修正。") {
            Choice("接下來收到的原文", state.speaker,
                listOf("未標記" to "未標記", "發言者 A" to "發言者 A", "發言者 B" to "發言者 B", "發言者 C" to "發言者 C", "CUSTOM" to "自訂姓名…")) {
                if (it == "CUSTOM") { speakerName = ""; customSpeaker = true } else controller.setSpeaker(it)
            }
        } }
        if (state.current?.fragments?.isNotEmpty() == true && !state.active) item {
            Text("完整內容及 SRT／WebVTT 匯出，請到「逐字稿」。原文與譯文使用各自的時間軸。", style = MaterialTheme.typography.bodySmall, color = Color(0xFF657971))
        }
    }
    if (customSpeaker) AlertDialog(onDismissRequest = { customSpeaker = false }, title = { Text("自訂發言者") },
        text = { OutlinedTextField(speakerName, { speakerName = it.take(80) }, label = { Text("姓名或稱呼") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { controller.setSpeaker(speakerName); customSpeaker = false }) { Text("套用") } },
        dismissButton = { TextButton(onClick = { customSpeaker = false }) { Text("取消") } })
}

private data class DisplayCue(val ids: List<String>, val start: Long, val end: Long, val text: String, val speaker: String?)
private fun displayCues(record: TranscriptRecord?, channel: TranscriptChannel): List<DisplayCue> {
    val result = mutableListOf<DisplayCue>()
    record?.fragments?.filter { it.channel == channel }?.sortedBy { it.startMs }?.forEach { f ->
        val last = result.lastOrNull()
        if (last != null && last.speaker == f.speakerLabel && f.startMs >= last.end && f.startMs - last.end <= 600 && f.endMs - last.start <= 6000 && last.text.length + f.text.length <= 180) {
            result[result.lastIndex] = last.copy(ids = last.ids + f.id, end = f.endMs, text = last.text + f.text)
        } else result += DisplayCue(listOf(f.id), f.startMs, f.endMs, f.text, f.speakerLabel)
    }
    return result.filter { it.text.isNotBlank() }
}

@Composable private fun TranscriptPanel(title: String, language: String, record: TranscriptRecord?, channel: TranscriptChannel, color: Color) {
    SectionCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = color, fontWeight = FontWeight.Bold)
            Text(language, color = Color(0xFF657971), style = MaterialTheme.typography.labelMedium)
        }
        val cues = displayCues(record, channel).takeLast(3).asReversed()
        if (cues.isNotEmpty()) Text("最新字幕在上方", style = MaterialTheme.typography.labelSmall, color = Color(0xFF657971))
        if (cues.isEmpty()) Text(if (channel == TranscriptChannel.OUTPUT) "開始說話後，翻譯會出現在這裡。" else "原始語音的辨識文字會保留在這裡。", color = Color(0xFF8C9A92), modifier = Modifier.padding(vertical = 18.dp))
        cues.forEach { cue ->
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("${formatTime(cue.start)} – ${formatTime(cue.end)}${cue.speaker?.let { " · 手動：$it" }.orEmpty()}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF657971))
                SelectionContainer { Text(cue.text.trim(), fontSize = 18.sp, lineHeight = 28.sp) }
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
    val cues = displayCues(record, channel)
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = onBack) { Text("← 所有逐字稿") } }
        item { PageIntro(record.title, formatDate(record.createdAtMs)) }
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
        items(cues, key = { it.ids.first() }) { cue ->
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
    var endpoint by remember(state.preferences.foundryEndpoint) { mutableStateOf(state.preferences.foundryEndpoint) }
    var deployment by remember(state.preferences.deployment) { mutableStateOf(state.preferences.deployment) }
    var apiKey by remember { mutableStateOf("") }
    var clearConfirm by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    var copiedReport by remember(state.lastConnectionReport) { mutableStateOf(false) }
    val previewConnection = FoundryConnection(endpoint.trim(), deployment.trim())
    val webSocketPreview = if (previewConnection.validationError() == null) previewConnection.webSocketUrl() else null
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageIntro("Microsoft Foundry", "App 直接連接你的 Foundry 資源，使用 GPT-Live 進行即時翻譯。") }
        item { SectionCard("連線設定", "API key 使用裝置的安全儲存機制加密保存。") {
            OutlinedTextField(endpoint, { endpoint = it }, label = { Text("Azure OpenAI／Foundry 資源端點") }, placeholder = { Text("https://your-resource.openai.azure.com") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(deployment, { deployment = it }, label = { Text("GPT-Live 部署名稱") }, placeholder = { Text("gpt-live-1") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(apiKey, { apiKey = it }, label = { Text(if (state.hasApiKey) "新 API key（端點相同時可留空）" else "Foundry API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                enabled = !state.busy, modifier = Modifier.fillMaxWidth())
            if (state.hasApiKey) Text("✓ API key 已加密儲存在這台裝置", style = MaterialTheme.typography.bodySmall, color = Mint)
            Text("變更資源端點時，請重新輸入對應的 API key。部署名稱請填入 Foundry 中實際設定的名稱。", style = MaterialTheme.typography.bodySmall)
            Text("請填入 Azure 資源的 HTTPS 根網址，例如 your-resource.openai.azure.com；不要填專案頁面、管理入口網址或其他模型的 API 路徑。", style = MaterialTheme.typography.bodySmall)
            webSocketPreview?.let { preview ->
                SelectionContainer { Text("實際連線網址：\n$preview", style = MaterialTheme.typography.labelSmall, color = Mint) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (controller.saveConnection(endpoint, deployment, apiKey)) apiKey = "" }, enabled = !state.busy && state.storageAvailable, shape = RoundedCornerShape(12.dp)) { Text("儲存連線設定") }
                TextButton(onClick = { clearConfirm = true }, enabled = !state.busy && state.storageAvailable) { Text("移除 API key") }
            }
            OutlinedButton(
                onClick = { if (state.connectionTesting) controller.cancelConnectionTest() else controller.testConnection(endpoint, deployment, apiKey) },
                enabled = !state.active && state.storageAvailable,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
            ) {
                if (state.connectionTesting) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Text(if (state.connectionTesting) "取消連線測試" else "測試連線")
            }
            Text("測試目前欄位中的端點、金鑰與部署，不啟用麥克風、不傳送背景知識，也不修改已儲存的設定。", style = MaterialTheme.typography.bodySmall)
            Text("測試會建立並關閉短暫的 GPT-Live 工作階段，可能產生服務用量。", style = MaterialTheme.typography.bodySmall)
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
            Text("1  在 Microsoft Foundry 部署 GPT-Live 模型。\n\n2  填入資源端點、部署名稱與該資源的 API key。\n\n3  回到即時翻譯，選語言與背景知識，允許麥克風後即可開始。", style = MaterialTheme.typography.bodyMedium)
        } }
        item { SectionCard("資料與收音") {
            Text("逐字稿與背景知識儲存在這台裝置；App 不保存原始錄音。錄音期間音訊會透過加密連線直接送至 Microsoft Foundry。", style = MaterialTheme.typography.bodyMedium)
            Text("API key 僅用於所設定的 Foundry 資源，不會包含在逐字稿或匯出內容中。", style = MaterialTheme.typography.bodySmall)
            Text("切換到背景、來電或音訊中斷時會停止收音。恢復後請手動開始新的一段。", style = MaterialTheme.typography.bodySmall)
            Text("目前提供手動發言者標記。GPT-Live 不回傳多人身分；原文與譯文字幕也可能有辨識或翻譯誤差。", style = MaterialTheme.typography.bodySmall)
        } }
        item { Text("LiveLingo 0.1.2 · Kotlin Multiplatform\nAndroid / iOS · Microsoft Foundry GPT-Live", style = MaterialTheme.typography.labelSmall, color = Color(0xFF657971), modifier = Modifier.padding(6.dp)) }
    }
    if (clearConfirm) ConfirmDelete("移除 Foundry API key？", "之後開始翻譯前，需要重新輸入 API key。", { clearConfirm = false }) { controller.clearApiKey(); clearConfirm = false }
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
