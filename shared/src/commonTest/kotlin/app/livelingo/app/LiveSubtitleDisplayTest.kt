package app.livelingo.app

import app.livelingo.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveSubtitleDisplayTest {
    @Test fun liveTextDoesNotBreakAtAudioChunkBoundaries() {
        val record = TranscriptRecord("r", "title", 0L, SessionSettings(), listOf(
            TranscriptFragment("p2", TranscriptChannel.INPUT, 500, 750, " trillion dollars"),
            TranscriptFragment("p1", TranscriptChannel.INPUT, 0, 499, "4.95"),
            TranscriptFragment("t1", TranscriptChannel.OUTPUT, 0, 200, "目前台灣是4.95兆"),
            TranscriptFragment("t2", TranscriptChannel.OUTPUT, 200, 500, "美元，而印度是4.92兆美元。")
        ))
        assertEquals("4.95 trillion dollars", liveCaptionText(record, TranscriptChannel.INPUT))
        assertEquals("目前台灣是4.95兆美元，而印度是4.92兆美元。", liveCaptionText(record, TranscriptChannel.OUTPUT))
    }

    @Test fun liveTextNormalizesProviderNewlinesOnlyForDisplay() {
        val text = "Now this Taiwan\n\n  4.95 trillion dollars"
        val record = TranscriptRecord("r", "title", 0, SessionSettings(), listOf(
            TranscriptFragment("p1", TranscriptChannel.INPUT, 0, 200, text)
        ))
        assertEquals("Now this Taiwan 4.95 trillion dollars", liveCaptionText(record, TranscriptChannel.INPUT))
        assertEquals(text, record.fragments.single().text)
    }

    @Test fun sourceLanguageInferenceIsConservativeAndOnlyUsesOriginal() {
        fun record(input: String, translation: String = "") = TranscriptRecord("id", "x", 0, SessionSettings(), listOf(
            TranscriptFragment("in", TranscriptChannel.INPUT, 0, 100, input),
            TranscriptFragment("out", TranscriptChannel.OUTPUT, 0, 100, translation)
        ))
        assertEquals("自動偵測（英文）", liveSourceLanguageLabel(Language.AUTO, record("Now this is Taiwan, and India is at 4.9 trillion dollars")))
        assertEquals("中文", inferredSourceLanguage(record("台灣的供應鏈是什麼？")))
        assertEquals("日文", inferredSourceLanguage(record("これは日本語です")))
        assertEquals("韓文", inferredSourceLanguage(record("안녕하세요 모두")))
        assertEquals("拉丁字母語言・待確認", inferredSourceLanguage(record("hola buenos dias amigo")))
        assertEquals("自動偵測（辨識中）", liveSourceLanguageLabel(Language.AUTO, null))
        assertEquals("印度英文", liveSourceLanguageLabel(Language.INDIAN_ENGLISH, record("Now this is Taiwan")))
    }

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
