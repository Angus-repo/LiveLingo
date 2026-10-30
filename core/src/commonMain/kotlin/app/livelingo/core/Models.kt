package app.livelingo.core

import kotlinx.serialization.Serializable

@Serializable
enum class Language(val label: String) {
    AUTO("自動偵測"),
    ENGLISH("英文"),
    INDIAN_ENGLISH("印度英文"),
    ZH_TW("繁體中文（台灣）"),
    JAPANESE("日文"),
    KOREAN("韓文"),
}

@Serializable
data class BackgroundKnowledge(val id: String, val name: String, val content: String)

/** Prompt strategy only: GPT-Live does not expose Realtime VAD/temperature knobs. */
@Serializable
enum class TranslationPace(val label: String) {
    FAST("優先即時"), BALANCED("平衡")
}

@Serializable
enum class TranslationQuality(val label: String) {
    FAITHFUL("忠實原文"), NATURAL("自然流暢")
}

@Serializable
data class SessionSettings(
    val sourceLanguage: Language = Language.AUTO,
    val targetLanguage: Language = Language.ZH_TW,
    val knowledge: BackgroundKnowledge? = null,
    val pace: TranslationPace = TranslationPace.BALANCED,
    val quality: TranslationQuality = TranslationQuality.FAITHFUL,
)

/** Shared by the mobile UI and trusted relay; do not rely on UI-only validation. */
fun SessionSettings.validationError(): String? {
    if (targetLanguage == Language.AUTO) return "請選擇要翻譯成的語言；自動偵測僅適用於來源語言。"
    val profile = knowledge ?: return null
    if (profile.id.isBlank() || profile.id.length > 128) return "背景知識識別碼無效。"
    if (profile.name.isBlank() || profile.name.length > 80) return "背景知識名稱必須為 1 至 80 個字元。"
    if (profile.content.length > 12_000) return "每組背景知識最多 12,000 個字元。"
    if (profile.content.isBlank()) return "請填寫背景知識內容。"
    if (profile.name.any { it.isUnsafeControl() } || profile.content.any { it.isUnsafeControl() }) {
        return "背景知識含有不支援的控制字元。"
    }
    return null
}

@Serializable
enum class TranscriptChannel { INPUT, OUTPUT }

/**
 * A provider-timed delta, not an authoritative utterance or a word alignment.
 * Speaker labels are optional, manually supplied metadata, never model guesses.
 */
@Serializable
data class TranscriptFragment(
    val id: String,
    val channel: TranscriptChannel,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val speakerLabel: String? = null,
) {
    init {
        require(startMs >= 0) { "字幕開始時間不可為負數。" }
        require(endMs > startMs) { "字幕結束時間必須晚於開始時間。" }
    }
}

@Serializable
data class TranscriptRecord(
    val id: String,
    val title: String,
    val createdAtMs: Long,
    val settings: SessionSettings,
    val fragments: List<TranscriptFragment> = emptyList(),
)

internal fun Char.isUnsafeControl(): Boolean =
    (code < 0x20 && this != '\n' && this != '\r' && this != '\t') || code == 0x7f
