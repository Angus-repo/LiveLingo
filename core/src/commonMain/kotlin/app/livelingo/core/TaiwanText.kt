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
    fun convert(text: String): String = taiwan.convert(traditional.convert(text)).replace("臺灣", "台灣")
}

fun TranscriptRecord.localizedText(text: String, channel: TranscriptChannel): String =
    if ((channel == TranscriptChannel.OUTPUT && settings.targetLanguage == Language.ZH_TW) ||
        (channel == TranscriptChannel.INPUT && settings.sourceLanguage == Language.ZH_TW)) TaiwanText.convert(text)
    else text
