package app.livelingo.app

import app.livelingo.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveSubtitleDisplayTest {
    @Test fun liveOriginalAndTranslationAreIndependentAndChronological() {
        val fragments = listOf(
            TranscriptFragment("out2", TranscriptChannel.OUTPUT, 4000, 5000, "譯文二"),
            TranscriptFragment("in2", TranscriptChannel.INPUT, 2000, 3000, "原文二"),
            TranscriptFragment("in1", TranscriptChannel.INPUT, 0, 500, "原文一"),
            TranscriptFragment("out1", TranscriptChannel.OUTPUT, 1000, 1300, "譯文一"),
        )
        val record = TranscriptRecord("rec", "會議", 1000, SessionSettings(), fragments)
        assertEquals(listOf("原文一", "原文二"), displayCues(record, TranscriptChannel.INPUT).map { it.text })
        assertEquals(listOf("譯文一", "譯文二"), displayCues(record, TranscriptChannel.OUTPUT).map { it.text })
    }

    @Test fun liveCueAggregationKeepsLastLineAndDoesNotTruncateHistory() {
        val fragments = (0..10).map { i ->
            TranscriptFragment("p$i", TranscriptChannel.INPUT, i * 10_000L, i * 10_000L + 500, "文字$i")
        }
        val record = TranscriptRecord("rec", "會議", 1000, SessionSettings(), fragments)
        val cues = displayCues(record, TranscriptChannel.INPUT)
        assertEquals(11, cues.size)
        assertEquals("文字0", cues.first().text)
        assertEquals("文字10", cues.last().text)
        assertTrue(displayCues(record, TranscriptChannel.OUTPUT).isEmpty())
    }

    @Test fun adjacentDeltasAreMergedWithoutExtraSpaces() {
        val record = TranscriptRecord("rec", "會議", 1000, SessionSettings(), listOf(
            TranscriptFragment("a", TranscriptChannel.INPUT, 0, 100, "資"),
            TranscriptFragment("b", TranscriptChannel.INPUT, 100, 250, "金"),
        ))
        assertEquals("資金", displayCues(record, TranscriptChannel.INPUT).single().text)
    }
}
