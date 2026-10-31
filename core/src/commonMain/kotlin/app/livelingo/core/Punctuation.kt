package app.livelingo.core

const val RESTORED_PUNCTUATION = "，。！？；：、,.!?;:"

/** Safe token ends; IDs never land inside an ASCII word, URL, email, or a number. */
fun punctuationTokenEnds(text: String): List<Int> = buildList {
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        when {
            ch.isWhitespace() -> i++
            ch.code in 33..126 && ch.isLetterOrDigit() -> {
                // Keep technical identifiers (Spring-Boot, user@example.com, 4.95,
                // 1,00,000, URLs) intact. Trailing sentence punctuation is still retained.
                i++
                while (i < text.length && text[i].code in 33..126) i++
                add(i)
            }
            else -> { i++; add(i) }
        }
    }
}

fun canInsertPunctuation(text: String, offset: Int, allowEnd: Boolean = false): Boolean {
    if (offset !in 1..text.length || (offset == text.length && !allowEnd)) return false
    val before = text[offset - 1]
    val next = text.drop(offset).firstOrNull { !it.isWhitespace() }
    if (next == null && !allowEnd) return false
    if (!before.isLetterOrDigit() || (next != null && !next.isLetterOrDigit())) return false
    if (before.code in 0xd800..0xdbff || next?.code in 0xdc00..0xdfff) return false
    // Chinese number/quantity expressions are protected too: 四萬個人、一千人、4.95兆.
    val numbers = "零〇一二三四五六七八九十百千萬万億亿兆兩两壹貳參肆伍陸柒捌玖拾佰仟"
    if (next != null && (before.isDigit() || before in numbers) && (next.isLetterOrDigit() || next in numbers)) return false
    if (before.code < 128 && next != null && next.code < 128 &&
        before.isLetterOrDigit() && next.isLetterOrDigit() && !text[offset].isWhitespace()) return false
    return true
}

fun validPunctuation(text: String, edits: List<PunctuationInsertion>, allowEnd: Boolean = false): Boolean {
    if (edits.map { it.offset }.distinct().size != edits.size) return false
    val ends = punctuationTokenEnds(text).toSet()
    return edits.all { it.mark.length == 1 && it.mark[0] in RESTORED_PUNCTUATION &&
        it.offset in ends && canInsertPunctuation(text, it.offset, allowEnd) }
}

/** Match a normalized layout back to its unchanged source, allowing whitespace only. */
fun alignedSourceLayout(text: String, layout: ParagraphLayout?): ParagraphLayout? {
    layout ?: return null
    val positions = mutableMapOf<Int, Int>()
    var cursor = 0
    layout.source.forEachIndexed { index, ch ->
        if (!ch.isWhitespace()) {
            while (cursor < text.length && text[cursor].isWhitespace()) cursor++
            if (text.getOrNull(cursor) != ch) return null
            cursor++
        }
        positions[index + 1] = cursor
    }
    return layout.copy(source = text.take(cursor),
        breakOffsets = layout.breakOffsets.mapNotNull { positions[it] }.distinct(),
        punctuation = layout.punctuation.mapNotNull { edit -> positions[edit.offset]?.let { edit.copy(offset = it) } })
}

/** Apply original formatting before target-script conversion and before deduplication. */
fun formattedSourceSlice(text: String, layout: ParagraphLayout?, start: Int, end: Int): String {
    val raw = text.substring(start, end)
    if (layout == null || start >= layout.source.length) return raw
    val through = minOf(end, layout.source.length)
    val sliced = layout.copy(source = text.substring(start, through),
        breakOffsets = layout.breakOffsets.filter { it > start && it < through }.map { it - start },
        punctuation = layout.punctuation.filter { it.offset > start && it.offset <= through }.map { it.copy(offset = it.offset - start) },
        // An edit at a clause's end was already validated in its whole-source context.
        finalized = true)
    val ending = if (end in layout.breakOffsets) "\n\n" else raw.takeLastWhile { it.isWhitespace() }
    return paragraphText(raw, sliced) + ending
}
