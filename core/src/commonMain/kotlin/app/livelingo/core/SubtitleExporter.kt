package app.livelingo.core

/**
 * Exports separate original/translation tracks on their own provider timelines.
 * Deltas are concatenated exactly: adding spaces would damage CJK and split words.
 * Grouping is a display heuristic, not a claim of sentence or speaker boundaries.
 */
object SubtitleExporter {
    private const val MAX_CUE_DURATION_MS = 6_000L
    private const val MAX_JOIN_GAP_MS = 600L
    private const val MAX_CUE_CHARACTERS = 120

    fun srt(record: TranscriptRecord, channel: TranscriptChannel): String =
        cues(record, channel).mapIndexed { index, cue ->
            "${index + 1}\n${timestamp(cue.startMs, ',')} --> ${timestamp(cue.endMs, ',')}\n${cueText(cue)}\n"
        }.joinToString("\n")

    fun vtt(record: TranscriptRecord, channel: TranscriptChannel): String = buildString {
        append("WEBVTT\n\n")
        cues(record, channel).forEachIndexed { index, cue ->
            append("${index + 1}\n${timestamp(cue.startMs, '.')} --> ${timestamp(cue.endMs, '.')}\n${cueText(cue)}\n\n")
        }
    }

    fun text(record: TranscriptRecord): String = buildString {
        appendLine(cleanSingleLine(record.title))
        appendLine("來源：${record.settings.sourceLanguage.label}　譯文：${record.settings.targetLanguage.label}")
        appendLine("音源：${record.settings.audioSource.label}")
        if (record.contentFilterCount > 0) appendLine("注意：本次收到 ${record.contentFilterCount} 次內容安全篩選通知，部分片段可能不完整。")
        record.settings.knowledge?.let { appendLine("背景知識：${cleanSingleLine(it.name)}") }
        appendLine("原文與譯文使用各自的音訊時間；發言者標記由使用者手動指定。")
        appendLine()
        for (channel in TranscriptChannel.entries) {
            val full = record.fragments.filter { it.channel == channel }.sortedBy { it.startMs }.joinToString("") { it.text }
            appendLine(if (channel == TranscriptChannel.INPUT) "【原文・閱讀段落】" else "【譯文・閱讀段落】")
            appendLine(paragraphText(record.localizedText(full, channel), record.paragraphLayouts.find { it.channel == channel }))
            if (channel == TranscriptChannel.OUTPUT) {
                val supplement = record.translationSupplementText()
                if (supplement.isNotBlank()) { appendLine(); appendLine(supplementReadingText(supplement)) }
            }
            appendLine()
        }
        appendLine("【時間戳明細】")
        val localizedFragments = buildMap {
            for (channel in TranscriptChannel.entries) {
                val fragments = record.fragments.filter { it.channel == channel }.sortedBy { it.startMs }
                val texts = record.localizedChunks(fragments.map { it.text }, channel)
                fragments.forEachIndexed { index, fragment -> put(fragment.id, texts[index]) }
            }
        }
        record.fragments.sortedWith(compareBy<TranscriptFragment> { it.startMs }.thenBy { it.endMs }).forEach { fragment ->
            val channel = if (fragment.channel == TranscriptChannel.INPUT) "原文" else "譯文"
            val speaker = fragment.speakerLabel?.let(::cleanSingleLine)?.takeIf(String::isNotBlank)
            append("[${timestamp(fragment.startMs, '.')} → ${timestamp(fragment.endMs, '.')}] [$channel]")
            speaker?.let { append(" [手動：$it]") }
            appendLine(" ${cleanText(localizedFragments.getValue(fragment.id))}")
        }
        record.translationSupplements().forEach { fragment ->
            appendLine("[${timestamp(fragment.startMs, '.')} → ${timestamp(fragment.endMs, '.')}] [$TRANSLATION_SUPPLEMENT_LABEL・原文時間] ${cleanText(fragment.text)}")
        }
    }.trimEnd() + "\n"

    private data class Cue(
        val startMs: Long,
        val endMs: Long,
        val text: String,
        val speaker: String?,
    )

    private fun cues(record: TranscriptRecord, channel: TranscriptChannel): List<Cue> {
        val result = mutableListOf<Cue>()
        // A stable sort keeps same-timestamp deltas in arrival order.
        record.fragments.filter { it.channel == channel }.sortedBy { it.startMs }.forEach { fragment ->
            val label = fragment.speakerLabel?.let(::cleanSingleLine)?.takeIf(String::isNotBlank)
            val previous = result.lastOrNull()
            val canJoin = previous != null && previous.speaker == label &&
                fragment.startMs >= previous.endMs &&
                fragment.startMs - previous.endMs <= MAX_JOIN_GAP_MS &&
                fragment.endMs - previous.startMs <= MAX_CUE_DURATION_MS &&
                previous.text.length + fragment.text.length <= MAX_CUE_CHARACTERS
            if (canJoin) {
                result[result.lastIndex] = previous!!.copy(endMs = fragment.endMs, text = previous.text + fragment.text)
            } else {
                result += Cue(fragment.startMs, fragment.endMs, fragment.text, label)
            }
        }
        // Keep whitespace deltas while grouping, but don't export a cue with no content.
        val texts = record.localizedChunks(result.map { it.text }, channel)
        .map { it.replace("\r\n", "\n").replace('\r', '\n') }
        val joined = texts.joinToString("")
        val layout = alignedSourceLayout(joined, record.paragraphLayouts.find { it.channel == channel })
        var offset = 0
        val actual = result.mapIndexed { index, cue ->
            val end = offset + texts[index].length
            cue.copy(text = formattedSourceSlice(joined, layout, offset, end)).also { offset = end }
        }.filter { cleanText(it.text).isNotBlank() }
        val supplements = if (channel == TranscriptChannel.OUTPUT) record.translationSupplements().map {
            Cue(it.startMs, it.endMs, supplementReadingText(it.text), null)
        } else emptyList()
        return (actual + supplements).sortedBy { it.startMs }
    }

    private fun cueText(cue: Cue): String {
        val prefix = cue.speaker?.let { "[手動：$it] " }.orEmpty()
        return escapeSubtitle(prefix + cleanText(cue.text))
    }

    private fun cleanText(value: String): String = value
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .filterNot { it.isUnsafeControl() }
        // A blank line would terminate a cue, so keep only nonempty text lines.
        .lineSequence()
        .filter { it.isNotBlank() }
        .joinToString("\n")
        .trim()

    private fun cleanSingleLine(value: String): String = value
        .map { if (it == '\r' || it == '\n' || it == '\t') ' ' else it }
        .joinToString("")
        .filterNot { it.isUnsafeControl() }
        .replace('[', '(').replace(']', ')')
        .trim()

    private fun escapeSubtitle(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun timestamp(milliseconds: Long, separator: Char): String {
        require(milliseconds >= 0)
        val hours = milliseconds / 3_600_000
        val minutes = (milliseconds / 60_000) % 60
        val seconds = (milliseconds / 1_000) % 60
        val millis = milliseconds % 1_000
        return "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}$separator${millis.toString().padStart(3, '0')}"
    }
}
