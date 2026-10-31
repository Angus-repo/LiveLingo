package app.livelingo.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.livelingo.core.*
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val CostGreen = Color(0xFF147D68)
private val CostAmber = Color(0xFF966323)
private fun costLabel(total: CostTotal): String = when {
    total.incomplete && total.usd == 0.0 -> "待計價"
    total.incomplete -> "${usd(total.usd)}＋未計價"
    else -> usd(total.usd)
}

@Composable
internal fun CostScreen(state: AppState, controller: AppController, services: PlatformServices) {
    var now by remember { mutableLongStateOf(services.currentTimeMillis()) }
    LaunchedEffect(services) { while (true) { now = services.currentTimeMillis(); delay(10_000) } }
    var period by rememberSaveable { mutableStateOf(CostPeriod.DAY) }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var selectedMeeting by remember { mutableStateOf<String?>(null) }
    var editRates by remember { mutableStateOf(false) }
    var editMeeting by remember { mutableStateOf<MeetingUsage?>(null) }
    val end = shiftPeriod(periodStart(now, period), period, offset)
    val buckets = remember(state.meetingCosts, state.history, period, end) { costBuckets(state.meetingCosts, state.history, period, end) }
    val rangeEnd = shiftPeriod(end, period, 1)
    val visibleMeetings = state.meetingCosts.filter { it.startedAtMs >= buckets.first().startMs && it.startedAtMs < rangeEnd }
    val knownIds = state.meetingCosts.map { it.id }.toSet()
    val legacy = state.history.filter { it.id !in knownIds && it.createdAtMs >= buckets.first().startMs && it.createdAtMs < rangeEnd }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), contentPadding = PaddingValues(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("費用統計", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("估算費用 · USD · 台灣時間", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { editMeeting = null; editRates = true }, enabled = !state.busy && state.storageAvailable) { Text("設定費率") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CostPeriod.entries.forEach { unit ->
                    val bucket = costBuckets(state.meetingCosts, state.history, unit, now).last()
                    Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(when (unit) { CostPeriod.DAY -> "今日"; CostPeriod.WEEK -> "本週"; CostPeriod.MONTH -> "本月" }, style = MaterialTheme.typography.labelLarge)
                            Text(costLabel(bucket.total), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text("${bucket.meetings} 場會議", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("費用趨勢", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CostPeriod.entries.forEach { unit ->
                            FilterChip(selected = unit == period, onClick = { period = unit; offset = 0 }, label = { Text(unit.label) })
                        }
                    }
                    Text("${taipeiDate(buckets.first().startMs)} ～ ${taipeiDate(rangeEnd - 1)}", style = MaterialTheme.typography.bodySmall)
                    CostLineChart(buckets, period)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { offset -= period.points }) { Text("‹ 前一段") }
                        TextButton(onClick = { offset = 0 }, enabled = offset < 0) { Text("回到現在") }
                        TextButton(onClick = { offset = (offset + period.points).coerceAtMost(0) }, enabled = offset < 0) { Text("後一段 ›") }
                    }
                    Text("期間合計：${costLabel(buckets.fold(CostTotal(0.0)) { sum, bucket -> sum + bucket.total })}", fontWeight = FontWeight.SemiBold)
                    Text("依會議開始日期歸帳，跨日會議歸同一天；週一至週日。橘點表示尚有未計價用量，線段只顯示已計價部分。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Text("各期總額", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                buckets.asReversed().forEach { bucket ->
                    OutlinedCard {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(periodLabel(bucket.startMs, period), style = MaterialTheme.typography.labelMedium)
                            Text(costLabel(bucket.total), fontWeight = FontWeight.SemiBold)
                            Text("${bucket.meetings} 場", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        item { Text("會議明細 · ${visibleMeetings.size + legacy.size} 場", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        if (visibleMeetings.isEmpty() && legacy.isEmpty()) item {
            Text("這段期間還沒有會議紀錄。開始翻譯後，會自動記錄用量與費用。", Modifier.padding(vertical = 16.dp))
        }
        items(visibleMeetings, key = { "cost-${it.id}" }) { meeting ->
            Card(Modifier.fillMaxWidth().clickable { selectedMeeting = meeting.id }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(meeting.title, fontWeight = FontWeight.SemiBold)
                    Text("${taipeiDateTime(meeting.startedAtMs)} · ${meeting.provider.label}", style = MaterialTheme.typography.bodySmall)
                    Text(meeting.speechModel, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(costLabel(meeting.total()), color = CostGreen, fontWeight = FontWeight.Bold)
                        Text(if (state.active && state.current?.id == meeting.id) "統計中" else "查看明細 ›", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        items(legacy, key = { "legacy-${it.id}" }) { record ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(record.title)
                    Text(taipeiDateTime(record.createdAtMs), style = MaterialTheme.typography.bodySmall)
                    Text("舊版無用量資料 · 無法回算費用", color = CostAmber)
                }
            }
        }
        item {
            Text("金額為本機記錄用量 × 費率的估算，非服務帳單；不含稅、折扣或其他裝置用量。未收到 token 回報的請求保留為未計價。刪除逐字稿仍保留費用紀錄。",
                style = MaterialTheme.typography.bodySmall)
        }
    }
    selectedMeeting?.let { id -> state.meetingCosts.find { it.id == id }?.let { meeting ->
        AlertDialog(onDismissRequest = { selectedMeeting = null }, title = { Text(meeting.title) },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${taipeiDateTime(meeting.startedAtMs)}（台灣時間）")
                    Text("估算合計：${costLabel(meeting.total())}", fontWeight = FontWeight.Bold)
                    if (meeting.samples.isNotEmpty() && meeting.endedAtMs == null && !(state.active && state.current?.id == id)) Text("未完成結算；顯示最後保存的用量。", color = CostAmber)
                    UsageKind.entries.forEach { kind ->
                        val samples = meeting.samples.filter { it.kind == kind }
                        if (samples.isNotEmpty()) {
                            HorizontalDivider()
                            Text(kind.label, fontWeight = FontWeight.SemiBold)
                            Text(costLabel(samples.fold(CostTotal(0.0)) { sum, sample -> sum + sample.cost(meeting.rates) }))
                            if (kind == UsageKind.VOICE || kind == UsageKind.SOURCE)
                                Text("${(samples.sumOf { it.seconds } * 10).roundToInt() / 10.0} 秒 · ${if (samples.any { it.estimated }) "依本機傳送／連線時間估算" else "服務回報"}")
                            else Text("輸入 ${samples.sumOf { it.inputTokens }} · 快取 ${samples.sumOf { it.cachedTokens }} · 輸出 ${samples.sumOf { it.outputTokens }} token")
                            if (samples.any { !it.known }) Text("有請求尚未取得用量，未納入金額。", color = CostAmber)
                        }
                    }
                    HorizontalDivider()
                    Text("費率日期：${meeting.rates.checkedDate}")
                    Text(meeting.rates.note, style = MaterialTheme.typography.bodySmall)
                    Text("語音 ${rateText(meeting.rates.voicePerMinute)} USD／分\n原文轉錄 ${rateText(meeting.rates.sourcePerMinute)} USD／分\n文字輸入／快取／輸出：${rateText(meeting.rates.inputPerMillion)}／${rateText(meeting.rates.cachedPerMillion)}／${rateText(meeting.rates.outputPerMillion)} USD／百萬 token", style = MaterialTheme.typography.bodySmall)
                }
            }, confirmButton = { TextButton(onClick = { selectedMeeting = null }) { Text("關閉") } },
            dismissButton = { TextButton(enabled = !state.busy && state.storageAvailable, onClick = { editMeeting = meeting; editRates = true; selectedMeeting = null }) { Text("修訂本場費率") } })
    } }
    if (editRates) CostRatesDialog(editMeeting?.rates ?: controller.costRates(), editMeeting?.title,
        modelLabel = editMeeting?.let { "${it.provider.label} · ${it.speechModel}\n${it.textModel}" }
            ?: state.preferences.connection().let { "${it.provider.label} · ${it.deployment}\n${it.textDeployment}" },
        onDismiss = { editRates = false }, onSave = { rates -> if (controller.saveCostRates(rates, editMeeting?.id)) editRates = false })
}

private fun rateText(value: Double?): String = value?.toString() ?: "未設定"
private fun periodLabel(start: Long, period: CostPeriod): String = when (period) {
    CostPeriod.DAY -> taipeiDate(start)
    CostPeriod.WEEK -> "${taipeiDate(start)} 起"
    CostPeriod.MONTH -> taipeiDate(start).take(7)
}

@Composable
private fun CostLineChart(buckets: List<CostBucket>, period: CostPeriod) {
    val maximum = maxOf(0.01, buckets.maxOfOrNull { it.total.usd } ?: 0.0)
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("已計價金額（USD）· 上限 ${usd(maximum)}", style = MaterialTheme.typography.labelSmall)
        Canvas(Modifier.fillMaxWidth().height(160.dp).semantics {
            contentDescription = "費用折線圖。" + buckets.joinToString("；") { "${periodLabel(it.startMs, period)} ${costLabel(it.total)}" }
        }) {
            val pad = 6.dp.toPx()
            val width = size.width - pad * 2
            val height = size.height - pad * 2
            for (line in 0..4) drawLine(grid, Offset(pad, pad + height * line / 4), Offset(size.width - pad, pad + height * line / 4), 1.dp.toPx())
            val points = buckets.mapIndexed { index, bucket -> Offset(pad + width * index / (buckets.size - 1).coerceAtLeast(1), pad + height * (1 - bucket.total.usd / maximum).toFloat()) }
            val path = Path()
            points.forEachIndexed { index, point -> if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y) }
            drawPath(path, CostGreen, style = Stroke(2.5.dp.toPx()))
            points.forEachIndexed { index, point -> drawCircle(if (buckets[index].total.incomplete) CostAmber else CostGreen, 3.dp.toPx(), point) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(buckets.first(), buckets[buckets.size / 2], buckets.last()).forEach { Text(taipeiDate(it.startMs).let { date -> if (period == CostPeriod.MONTH) date.take(7) else date.substring(5) }, style = MaterialTheme.typography.labelSmall) }
        }
        Text("基準 US$0", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun CostRatesDialog(rates: CostRates, meetingTitle: String?, modelLabel: String, onDismiss: () -> Unit, onSave: (CostRates) -> Unit) {
    val initial = listOf(rates.voicePerMinute, rates.sourcePerMinute, rates.inputPerMillion, rates.cachedPerMillion, rates.outputPerMillion)
    var fields by remember(rates) { mutableStateOf(initial.map { it?.toString().orEmpty() }) }
    val valid = fields.all { it.isBlank() || it.toDoubleOrNull()?.let { value -> value.isFinite() && value in 0.0..1_000_000.0 } == true }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (meetingTitle == null) "設定計價費率" else "修訂本場費率") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (meetingTitle == null) "套用目前服務、資源與模型組合，只影響之後開始的會議。" else "套用「$meetingTitle」並重新計算本場費用，不改動其他會議。")
            Text(modelLabel, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            Text("美元 USD；空白表示未知，不視為免費。快取空白時按一般輸入價估算。", style = MaterialTheme.typography.bodySmall)
            listOf("語音／每分鐘", "原文轉錄／每分鐘", "文字輸入／百萬 token", "快取輸入／百萬 token", "文字輸出／百萬 token").forEachIndexed { index, label ->
                OutlinedTextField(value = fields[index], onValueChange = { text -> fields = fields.toMutableList().also { it[index] = text.trim() } },
                    label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            if (!valid) Text("請填寫有效且非負的費率。", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(enabled = valid, onClick = {
        val values = fields.map { it.toDoubleOrNull() }
        onSave(rates.copy(voicePerMinute = values[0], sourcePerMinute = values[1], inputPerMillion = values[2], cachedPerMillion = values[3], outputPerMillion = values[4]))
    }) { Text("儲存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
