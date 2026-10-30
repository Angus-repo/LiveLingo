package app.livelingo.core

import app.livelingo.core.generated.*

/** Offline, contextual character/phrase conversion. No network, rewriting or numeric changes. */
object TaiwanText {
    private class Dictionary(chunks: List<String>) {
        val entries = buildMap {
            chunks.forEach { chunk -> chunk.lineSequence().forEach { row ->
                val split = row.indexOf('\t')
                if (split > 0) put(row.substring(0, split), row.substring(split + 1))
            } }
        }
        val lengths = entries.keys.groupBy { it.first() }
            .mapValues { (_, keys) -> keys.map { it.length }.distinct().sortedDescending() }
        fun convert(value: String): String = buildString {
            var position = 0
            while (position < value.length) {
                var consumed = 0
                for (length in lengths[value[position]].orEmpty()) {
                    if (position + length > value.length) continue
                    val match = entries[value.substring(position, position + length)] ?: continue
                    append(match); consumed = length; break
                }
                if (consumed == 0) { append(value[position]); consumed = 1 }
                position += consumed
            }
        }
    }
    private val traditional by lazy { Dictionary(sTCharactersData() + sTPhrasesData()) }
    private val taiwan by lazy { Dictionary(tWVariantsData() + tWVariantsPhrasesData() + tWPhrasesData()) }
    // Match the application's Taiwan spelling without changing 臺 in other proper names.
    fun convert(text: String, previousChar: Char? = null, nextChar: Char? = null): String =
        fullWidthCommas(taiwan.convert(traditional.convert(text)).replace("臺灣", "台灣"), previousChar, nextChar)

    /** Format sentence commas, preserving numeric separators (including Indian grouping). */
    private fun fullWidthCommas(text: String, previousChar: Char?, nextChar: Char?): String = buildString {
        text.forEachIndexed { index, char ->
            val before = text.getOrNull(index - 1) ?: previousChar
            val after = text.getOrNull(index + 1) ?: nextChar
            val numeric = before in '0'..'9' && after in '0'..'9'
            when {
                char == ',' && !numeric -> append('，')
                // Do not remove newlines or whitespace inside numbers/English names.
                char in " \t" && (lastOrNull() == '，' || (isEmpty() && previousChar in listOf(',', '，'))) -> Unit
                else -> append(char)
            }
        }
    }
}

fun TranscriptRecord.localizedText(text: String, channel: TranscriptChannel, previousChar: Char? = null, nextChar: Char? = null): String =
    if ((channel == TranscriptChannel.OUTPUT && settings.targetLanguage == Language.ZH_TW) ||
        (channel == TranscriptChannel.INPUT && settings.sourceLanguage == Language.ZH_TW)) TaiwanText.convert(text, previousChar, nextChar)
    else text

/** Cue/timestamp boundaries may split a numeric comma across separate provider deltas. */
fun TranscriptRecord.localizedChunks(chunks: List<String>, channel: TranscriptChannel): List<String> {
    val following = arrayOfNulls<Char>(chunks.size)
    var next: Char? = null
    for (index in chunks.indices.reversed()) {
        following[index] = next
        next = chunks[index].firstOrNull() ?: next
    }
    var previous: Char? = null
    return chunks.mapIndexed { index, text ->
        localizedText(text, channel, previous, following[index]).also {
            previous = text.lastOrNull() ?: previous
        }
    }
}
