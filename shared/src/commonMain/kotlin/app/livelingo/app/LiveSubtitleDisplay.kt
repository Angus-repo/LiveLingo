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
