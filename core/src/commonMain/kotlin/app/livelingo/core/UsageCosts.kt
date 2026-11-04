@file:OptIn(kotlin.time.ExperimentalTime::class)

package app.livelingo.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.math.roundToLong
import kotlin.time.Instant

@Serializable
enum class UsageKind(val label: String) {
    VOICE("語音服務"), SOURCE("原文轉錄"), TRANSLATION("文字翻譯"), FORMATTING("AI 標點與分段"), BACKEND("其他後端用量")
}

/** A stable key identifies a request or a cumulative stream. Updates REPLACE, never add snapshots. */
@Serializable
data class UsageSample(
    val id: String,
    val kind: UsageKind,
    val seconds: Double = 0.0,
    val inputTokens: Long = 0,
    val cachedTokens: Long = 0,
    val outputTokens: Long = 0,
    val known: Boolean = true,
    val estimated: Boolean = false,
    val final: Boolean = false,
)

@Serializable
data class CostRates(
    val voicePerMinute: Double? = null,
    val sourcePerMinute: Double? = null,
    val inputPerMillion: Double? = null,
    val cachedPerMillion: Double? = null,
    val outputPerMillion: Double? = null,
    val note: String = "公開參考價；實際費用以服務帳單為準",
    val checkedDate: String = PRICE_CHECKED_DATE,
) {
    fun valid(): Boolean = listOf(voicePerMinute, sourcePerMinute, inputPerMillion, cachedPerMillion, outputPerMillion)
        .all { it == null || (it.isFinite() && it in 0.0..1_000_000.0) }
}

fun FoundryConnection.defaultCostRates(): CostRates {
    // Azure GPT-Live pricing and arbitrary OpenAI IDs cannot be inferred from a display label.
    val knownSpeech = provider == ModelProvider.FOUNDRY || deployment == speechModel.modelId
    val speech = if (!knownSpeech) null else when (speechModel) {
        SpeechModel.GPT_LIVE -> if (provider == ModelProvider.OPENAI) 0.05 else null
        SpeechModel.REALTIME_TRANSLATE -> 0.034
        SpeechModel.LIVE_TRANSCRIBE -> 0.017
        SpeechModel.MAI_TRANSCRIBE -> 0.009
    }
    val text = if (provider == ModelProvider.OPENAI && textDeployment != textModelKind.modelId) TextModel.CUSTOM else textModelKind
    val prices = when (text) {
        TextModel.GPT6_LUNA -> listOf(0.10, 0.01, 0.50)
        TextModel.GPT54_MINI -> listOf(0.75, 0.075, 4.50)
        TextModel.GPT61_SOL -> listOf(2.00, 0.10, 10.00)
        TextModel.GPT41_MINI -> if (provider == ModelProvider.OPENAI) listOf(0.40, 0.10, 1.60) else emptyList()
        TextModel.CUSTOM -> emptyList()
    }
    return CostRates(speech,
        if (provider == ModelProvider.OPENAI && sourceTranscriptionModel == "gpt-realtime-whisper") 0.017 else null,
        prices.getOrNull(0), prices.getOrNull(1), prices.getOrNull(2),
        if (provider == ModelProvider.FOUNDRY) "Foundry Global Standard 參考價；未確認費率請填寫。快取費率空白時按一般輸入估算。"
        else "OpenAI Standard 短上下文參考價；未含區域加價及特殊快取寫入費用。")
}

fun FoundryConnection.costRateKey(): String = listOf(provider.name, apiBaseUrl(), speechModel.name,
    deployment, textModelKind.name, textDeployment, sourceTranscriptionModel).joinToString("|")

@Serializable
data class MeetingUsage(
    val id: String,
    val title: String,
    val startedAtMs: Long,
    val provider: ModelProvider,
    val speechModel: String,
    val textModel: String,
    val rates: CostRates,
    val samples: List<UsageSample> = emptyList(),
    val endedAtMs: Long? = null,
) {
    fun update(sample: UsageSample): MeetingUsage {
        if (!sample.seconds.isFinite() || sample.seconds < 0 || sample.inputTokens < 0 ||
            sample.cachedTokens !in 0..sample.inputTokens || sample.outputTokens < 0) return this
        val old = samples.find { it.id == sample.id }
        if (old?.final == true && !sample.final) return this
        return copy(samples = samples.filterNot { it.id == sample.id } + sample)
    }
}

data class CostTotal(val usd: Double, val unpriced: Int = 0, val missing: Int = 0) {
    val incomplete: Boolean get() = unpriced > 0 || missing > 0
    operator fun plus(other: CostTotal) = CostTotal(usd + other.usd, unpriced + other.unpriced, missing + other.missing)
}

fun UsageSample.cost(rates: CostRates): CostTotal {
    if (!known) return CostTotal(0.0, missing = 1)
    if (kind == UsageKind.BACKEND) return CostTotal(0.0, unpriced = 1)
    var total = 0.0
    var missingRate = false
    fun add(quantity: Double, rate: Double?, divisor: Double) {
        if (quantity <= 0) return
        if (rate == null) missingRate = true else total += quantity * rate / divisor
    }
    when (kind) {
        UsageKind.VOICE -> add(seconds, rates.voicePerMinute, 60.0)
        UsageKind.SOURCE -> add(seconds, rates.sourcePerMinute, 60.0)
        else -> {
            add((inputTokens - cachedTokens).toDouble(), rates.inputPerMillion, 1_000_000.0)
            add(cachedTokens.toDouble(), rates.cachedPerMillion ?: rates.inputPerMillion, 1_000_000.0)
            add(outputTokens.toDouble(), rates.outputPerMillion, 1_000_000.0)
        }
    }
    return CostTotal(total, unpriced = if (missingRate) 1 else 0)
}

fun MeetingUsage.total(): CostTotal = samples.fold(CostTotal(0.0,
    missing = if (endedAtMs == null && samples.isNotEmpty()) 1 else 0)) { sum, sample -> sum + sample.cost(rates) }

/** Parse before inspecting choices: a truncated/refused completion still consumes tokens. */
fun chatUsage(root: JsonObject, id: String, kind: UsageKind): UsageSample {
    val usage = root["usage"] as? JsonObject
    fun tokens(name: String): Long? = (usage?.get(name) as? JsonPrimitive)?.longOrNull?.takeIf { it in 0..1_000_000_000L }
    val input = tokens("prompt_tokens")
    val output = tokens("completion_tokens") // already includes reasoning tokens
    val detail = usage?.get("prompt_tokens_details") as? JsonObject
    val cached = if (detail?.containsKey("cached_tokens") == true)
        (detail["cached_tokens"] as? JsonPrimitive)?.longOrNull else 0L
    if (input == null || output == null || cached == null || cached !in 0..input)
        return UsageSample(id, kind, known = false, final = true)
    return UsageSample(id, kind, inputTokens = input, cachedTokens = cached, outputTokens = output, final = true)
}

fun liveUsage(payload: String): UsageSample? = runCatching {
    val event = WireJson.parseToJsonElement(payload).jsonObject
    val type = (event["type"] as? JsonPrimitive)?.content
    if (type !in setOf("session.usage.updated", "session.closed")) return@runCatching null
    val seconds = ((event["usage"] as? JsonObject)?.get("seconds") as? JsonPrimitive)?.doubleOrNull
        ?.takeIf { it.isFinite() && it in 0.0..604_800.0 } ?: return@runCatching null
    UsageSample("voice", UsageKind.VOICE, seconds = seconds, final = type == "session.closed")
}.getOrNull()

enum class CostPeriod(val label: String, val points: Int) { DAY("每日", 30), WEEK("每週", 12), MONTH("每月", 12) }
private const val DAY_MS = 86_400_000L
private const val TAIPEI_MS = 28_800_000L
fun taipeiDate(ms: Long): String = Instant.fromEpochMilliseconds(ms + TAIPEI_MS).toString().take(10)
fun taipeiDateTime(ms: Long): String = Instant.fromEpochMilliseconds(ms + TAIPEI_MS).toString().take(16).replace('T', ' ')
private fun dateStart(date: String): Long = Instant.parse("${date}T00:00:00Z").toEpochMilliseconds() - TAIPEI_MS
fun periodStart(ms: Long, period: CostPeriod): Long {
    val day = (ms + TAIPEI_MS).floorDiv(DAY_MS)
    return when (period) {
        CostPeriod.DAY -> day * DAY_MS - TAIPEI_MS
        CostPeriod.WEEK -> (day - (day + 3).mod(7)) * DAY_MS - TAIPEI_MS
        CostPeriod.MONTH -> dateStart(taipeiDate(ms).take(7) + "-01")
    }
}
fun shiftPeriod(startMs: Long, period: CostPeriod, count: Int): Long {
    if (period != CostPeriod.MONTH) return startMs + DAY_MS * count * if (period == CostPeriod.WEEK) 7 else 1
    val date = taipeiDate(startMs)
    val index = date.take(4).toInt() * 12 + date.substring(5, 7).toInt() - 1 + count
    return dateStart("${index.floorDiv(12).toString().padStart(4, '0')}-${(index.mod(12) + 1).toString().padStart(2, '0')}-01")
}
data class CostBucket(val startMs: Long, val total: CostTotal, val meetings: Int)
fun costBuckets(meetings: List<MeetingUsage>, legacy: List<TranscriptRecord>, period: CostPeriod, endMs: Long): List<CostBucket> {
    val end = periodStart(endMs, period)
    val grouped = meetings.groupBy { periodStart(it.startedAtMs, period) }
    val ids = meetings.map { it.id }.toSet()
    val old = legacy.filter { it.id !in ids }.groupBy { periodStart(it.createdAtMs, period) }
    return (1 - period.points..0).map { offset ->
        val start = shiftPeriod(end, period, offset)
        val values = grouped[start].orEmpty()
        CostBucket(start, values.fold(CostTotal(0.0, missing = old[start].orEmpty().size)) { sum, meeting -> sum + meeting.total() }, values.size + old[start].orEmpty().size)
    }
}

/** Round only when presenting; never sum per-request or per-meeting rounded amounts. */
fun usd(value: Double): String {
    val units = (value * 10_000).roundToLong()
    return "US$${units / 10_000}.${(units % 10_000).toString().padStart(4, '0')}"
}
