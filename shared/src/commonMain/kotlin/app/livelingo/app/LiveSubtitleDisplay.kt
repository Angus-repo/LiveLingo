package app.livelingo.app

import app.livelingo.core.TranscriptChannel
import app.livelingo.core.TranscriptRecord

/** UI-only cues. Provider timestamps are retained in the record for history and export. */
internal data class DisplayCue(val ids: List<String>, val start: Long, val end: Long, val text: String, val speaker: String?)

internal fun displayCues(record: TranscriptRecord?, channel: TranscriptChannel): List<DisplayCue> {
    val result = mutableListOf<DisplayCue>()
    // Stable ordering from oldest to newest, even if the service delivers out-of-order events.
    record?.fragments?.filter { it.channel == channel }?.sortedBy { it.startMs }?.forEach { fragment ->
        val previous = result.lastOrNull()
        if (previous != null && previous.speaker == fragment.speakerLabel &&
            fragment.startMs >= previous.end && fragment.startMs - previous.end <= 600 &&
            fragment.endMs - previous.start <= 6000 && previous.text.length + fragment.text.length <= 180
        ) {
            result[result.lastIndex] = previous.copy(
                ids = previous.ids + fragment.id, end = fragment.endMs, text = previous.text + fragment.text
            )
        } else {
            result += DisplayCue(listOf(fragment.id), fragment.startMs, fragment.endMs, fragment.text, fragment.speakerLabel)
        }
    }
    return result.filter { it.text.isNotBlank() }
}


/**
 * Live view uses one flowing text stream. GPT-Live deltas are audio cadence chunks,
 * NOT sentences. Never insert a line break or space between separate deltas:
 * some events split a word in half. Keep the raw record untouched for exports.
 */
internal fun liveCaptionText(record: TranscriptRecord?, channel: TranscriptChannel): String {
    val fragments = record?.fragments?.filter { it.channel == channel }?.sortedBy { it.startMs }.orEmpty()
    if (fragments.isEmpty()) return ""
    val fullText = buildString {
        var previousSpeaker: String? = null
        fragments.forEach { fragment ->
            val speaker = fragment.speakerLabel?.trim()?.takeIf { it.isNotEmpty() }
            if (channel == TranscriptChannel.INPUT && speaker != null && speaker != previousSpeaker) {
                if (isNotEmpty() && last() != ' ') append(' ')
                append("〔手動：").append(speaker).append("〕")
            }
            previousSpeaker = speaker
            append(fragment.text)
        }
    }
    // Incoming deltas may include newline/extra indentation that is not a sentence break.
    // This is presentation-only: original transcript and timestamps remain unchanged.
    return fullText.replace(Regex("[\\r\\n\\t]+"), " ")
        .replace(Regex(" {2,}"), " ")
        .trim()
}

/** GPT-Live does not return a reliable source-language identifier in transcript events.
 * Display a best-effort SCRIPT/TEXT inference, never claim this is audio/locale detection.
 */
internal fun inferredSourceLanguage(record: TranscriptRecord?): String? {
    val recent = liveCaptionText(record, TranscriptChannel.INPUT).takeLast(4_000)
    if (recent.isBlank()) return null
    var hangul = 0
    var kana = 0
    var han = 0
    var devanagari = 0
    var latin = 0
    recent.forEach { ch ->
        when (ch.code) {
            in 0xAC00..0xD7AF, in 0x1100..0x11FF, in 0x3130..0x318F -> hangul++
            in 0x3040..0x30FF, in 0x31F0..0x31FF -> kana++
            in 0x3400..0x4DBF, in 0x4E00..0x9FFF -> han++
            in 0x0900..0x097F -> devanagari++
            in 0x0041..0x005A, in 0x0061..0x007A -> latin++
        }
    }
    if (hangul >= 2 && hangul >= kana) return "韓文（文字推測）"
    if (kana >= 2) return "日文（文字推測）"
    if (devanagari >= 2) return "天城文字語言（待確認）"
    if (han >= 3 && (han >= latin / 3 || latin < 12)) return "中文（文字推測）"
    if (latin >= 8) {
        // Latin letters alone do not identify English: Spanish, French, Hindi
        // transliteration etc. are also Latin. Require some English words.
        val words = Regex("[A-Za-z]+").findAll(recent).map { it.value.lowercase() }.toList()
        val commonEnglish = setOf(
            "the", "and", "is", "are", "was", "were", "this", "that", "these", "those",
            "now", "so", "at", "of", "from", "have", "has", "what", "we", "they", "it",
            "not", "can", "would", "could", "should", "you", "your", "for", "with",
            "but", "there", "where", "when", "why", "how", "will", "be", "to",
        )
        return if (words.count { it in commonEnglish } >= 2) "英文（文字推測）"
            else "拉丁字母語言（待確認）"
    }
    if (han >= 3) return "中文（文字推測）"
    return null
}

internal fun liveSourceLanguageLabel(source: app.livelingo.core.Language, record: TranscriptRecord?): String =
    if (source != app.livelingo.core.Language.AUTO) source.label
    else "自動偵測（${inferredSourceLanguage(record) ?: "辨識中"}）"
