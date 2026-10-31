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

/** Optional AI break positions, not automatic wraps or claims that each clause is a sentence. */
fun paragraphCandidateEnds(text: String): List<Int> {
    val candidates = sentenceEnds(text).toMutableSet()
    val closing = ArrayDeque<Char>()
    val pairs = mapOf('“' to '”', '‘' to '’', '「' to '」', '『' to '』', '(' to ')', '（' to '）', '[' to ']', '【' to '】', '{' to '}')
    text.forEachIndexed { i, ch ->
        when {
            ch == closing.lastOrNull() -> closing.removeLast()
            ch == '"' -> closing.addLast('"')
            ch in pairs -> closing.addLast(pairs.getValue(ch))
        }
        val numericComma = ch in ",，" && text.getOrNull(i - 1)?.isDigit() == true && text.getOrNull(i + 1)?.isDigit() == true
        if (closing.isEmpty() && ((ch in ",，;；" && !numericComma) || ch == '\n')) candidates += i + 1
    }
    // Adjacent punctuation/newlines must not become empty reference units.
    var previous = 0
    return candidates.sorted().filter { end ->
        text.substring(previous, end).isNotBlank().also { if (it) previous = end }
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
