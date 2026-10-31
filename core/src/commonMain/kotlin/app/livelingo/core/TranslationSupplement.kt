package app.livelingo.core

const val TRANSLATION_SUPPLEMENT_LABEL = "原文補充"

/**
 * Keep received source text readable when a translation service is silent. This is a
 * presentation of the original, never a fabricated provider OUTPUT or a new API call.
 * The two audio timelines are independent: do not use timestamp overlap as translation
 * coverage. Only an ordered, literal text match may hide a Chinese supplement.
 */
fun TranscriptRecord.translationSupplements(): List<TranscriptFragment> {
    val inputs = fragments.filter { it.channel == TranscriptChannel.INPUT }.sortedBy { it.startMs }
    if (inputs.isEmpty()) return emptyList()
    val source = inputs.joinToString("") { it.text }
    if (source.isBlank()) return emptyList()
    val arrival = fragments.mapIndexed { index, fragment -> fragment.id to index }.toMap()
    val outputs = fragments.filter { it.channel == TranscriptChannel.OUTPUT }.sortedBy { it.startMs }
    val outputChunks = localizedChunks(outputs.map { it.text }, TranscriptChannel.OUTPUT).map { matchingText(it).text }
    val normalizedOutput = outputChunks.joinToString("")
    val outputArrivals = outputChunks.flatMapIndexed { index, text -> List(text.length) { arrival.getValue(outputs[index].id) } }
    val noOutput = normalizedOutput.isBlank()
    // Without output, show any received language, explicitly labelled as source. With
    // output, supplement Chinese passages for a Chinese target (including code-switching).
    if (!noOutput && settings.targetLanguage != Language.ZH_TW) return emptyList()
    var outputCursor = 0
    val offsets = mutableListOf<Int>()
    var offset = 0
    inputs.forEach { offsets += offset; offset += it.text.length }
    val result = mutableListOf<TranscriptFragment>()
    fun findEligible(needle: String, from: Int, sourceArrival: Int): Int {
        var match = normalizedOutput.indexOf(needle, from)
        while (match >= 0) {
            // Never let an earlier English translation hide a newly spoken Chinese
            // repetition with identical wording. At least part must arrive afterwards.
            if ((match until match + needle.length).any { outputArrivals[it] > sourceArrival }) return match
            match = normalizedOutput.indexOf(needle, match + 1)
        }
        return -1
    }
    for (range in sourceUnits(source)) {
        var start = range.first
        val end = range.last + 1
        var raw = source.substring(start, end)
        if (raw.isBlank()) continue
        if (!noOutput) {
            // A Han character alone is not proof of Chinese. Keep Japanese/Korean
            // source out of automatic Chinese supplements; never call it a translation.
            if (settings.sourceLanguage in setOf(Language.JAPANESE, Language.KOREAN) ||
                raw.any { it.code in 0x3040..0x30ff || it.code in 0x31f0..0x31ff ||
                    it.code in 0xac00..0xd7af || it.code in 0x1100..0x11ff || it.code in 0x3130..0x318f }) continue
            val firstHan = raw.indexOfFirst { it.isHan() }
            if (firstHan < 0) continue
            // A switch can happen inside a delta, with no punctuation: "...Walkman 我要…".
            // Keep short names such as Sony/API, but omit a preceding English sentence.
            if (Regex("[A-Za-z]+").findAll(raw.take(firstHan)).count() > 3) {
                start += firstHan
                raw = source.substring(start, end)
            }
        }
        val first = inputs.indices.firstOrNull { offsets[it] + inputs[it].text.length > start } ?: continue
        val last = inputs.indices.lastOrNull { offsets[it] < end } ?: first
        val sourceArrival = arrival.getValue(inputs[first].id)
        val localized = localizedText(raw, TranscriptChannel.OUTPUT)
        var remainder = localized
        if (!noOutput) {
            val candidate = matchingText(localized)
            if (candidate.text.isEmpty()) continue
            val match = findEligible(candidate.text, outputCursor, sourceArrival)
            val unambiguous = candidate.text.length >= 4 ||
                normalizedOutput.substring(outputCursor) == candidate.text ||
                (outputCursor > 0 && match == outputCursor)
            if (match >= 0 && unambiguous) {
                // Consume each occurrence once: repeated spoken sentences must survive.
                outputCursor = match + candidate.text.length
                continue
            }
            // A late provider delta may contain only the beginning of this clause. Hide
            // only that confirmed prefix, and retain all remaining source characters.
            // A short common prefix (e.g. 我/我們) is too ambiguous to use as an anchor.
            if (candidate.text.length >= 4) {
                val anchor = findEligible(candidate.text.take(4), outputCursor, sourceArrival)
                if (anchor >= 0) {
                    var count = 0
                    while (count < candidate.text.length && anchor + count < normalizedOutput.length &&
                        candidate.text[count] == normalizedOutput[anchor + count]) count++
                    if (anchor + count == normalizedOutput.length) {
                        outputCursor = normalizedOutput.length
                        remainder = localized.substring(candidate.positions[count - 1] + 1).trimStart()
                    }
                }
            }
        }
        if (remainder.isBlank() || matchingText(remainder).text.isEmpty()) continue
        result += TranscriptFragment("source-supplement:$start:$end", TranscriptChannel.OUTPUT,
            inputs[first].startMs, maxOf(inputs[first].startMs + 1, inputs[last].endMs), remainder)
    }
    return result
}

fun TranscriptRecord.translationSupplementText(): String = translationSupplements().joinToString("") { it.text }.trim()

/** Explicit provenance also travels with plain-text and timed subtitle exports. */
fun supplementReadingText(text: String): String = if (text.isBlank()) "" else "〔$TRANSLATION_SUPPLEMENT_LABEL〕\n$text"

private fun Char.isHan(): Boolean = code in 0x3400..0x4dbf || code in 0x4e00..0x9fff || code in 0xf900..0xfaff

private data class MatchingText(val text: String, val positions: List<Int>)

private fun matchingText(value: String): MatchingText {
    val positions = mutableListOf<Int>()
    val text = buildString {
        value.forEachIndexed { index, ch ->
            // Do not erase decimal, grouping, sign or date distinctions while deduplicating.
            val numericPunctuation = ch in ",.，．-−+/:：%％" &&
                (value.getOrNull(index - 1)?.isDigit() == true || value.getOrNull(index + 1)?.isDigit() == true)
            if (ch.isLetterOrDigit() || numericPunctuation) {
                append(ch.lowercaseChar()); positions += index
            }
        }
    }
    return MatchingText(text, positions)
}

/** Clause boundaries, not audio delta boundaries; numeric commas/decimals stay intact. */
private fun sourceUnits(text: String): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var start = 0
    var index = 0
    while (index < text.length) {
        val ch = text[index]
        val numeric = text.getOrNull(index - 1)?.isDigit() == true && text.getOrNull(index + 1)?.isDigit() == true
        if (ch in "。！？!?；;\n" || (ch in ",，." && !numeric)) {
            while (index + 1 < text.length && text[index + 1].isWhitespace()) index++
            ranges += start..index
            start = index + 1
        }
        index++
    }
    if (start < text.length) ranges += start until text.length
    return ranges
}
