package app.livelingo.core

/** Sentence boundaries, not fixed character wrapping. Decimal points never end a sentence. */
fun sentenceEnds(text: String): List<Int> = buildList {
    text.forEachIndexed { i, ch ->
        val next = text.getOrNull(i + 1)
        val decimal = ch == '.' && text.getOrNull(i - 1)?.isDigit() == true && next?.isDigit() == true
        val stop = ch in "。！？!?" || (ch == '.' && !decimal && (next == null || next.isWhitespace()))
        if (stop) {
            var end = i + 1
            while (end < text.length && text[end] in "\"'”’）)」』") end++
            if (lastOrNull() != end) add(end)
        }
    }
}

fun paragraphText(text: String, layout: ParagraphLayout? = null): String {
    if (text.isBlank()) return ""
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    val offsets = if (layout != null && normalized.startsWith(layout.source)) layout.breakOffsets
        else emptyList()
    // Existing model line breaks are meaningful and must survive presentation.
    if (offsets.isEmpty() && '\n' in normalized) return normalized.trim()
    val boundaries = sentenceEnds(normalized)
    val basic = mutableListOf<Int>()
    var previous = 0
    var sentences = 0
    boundaries.forEach { end ->
        sentences++
        if (sentences >= 3 || (sentences >= 2 && end - previous >= 160)) {
            basic += end; previous = end; sentences = 0
        }
    }
    val selected = if (layout != null && normalized.startsWith(layout.source))
        offsets + basic.filter { it > layout.source.length } else basic
    val valid = selected.distinct().sorted().filter { it in 1 until normalized.length }
    return buildString {
        var start = 0
        valid.forEach { end ->
            append(normalized.substring(start, end).trim()); append("\n\n"); start = end
        }
        append(normalized.substring(start).trim())
    }
}
