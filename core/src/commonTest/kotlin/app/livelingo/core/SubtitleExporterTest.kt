package app.livelingo.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubtitleExporterTest {
    private fun fragment(
        id: String, text: String, start: Long, end: Long,
        channel: TranscriptChannel = TranscriptChannel.INPUT, speaker: String? = null,
    ) = TranscriptFragment(id, channel, start, end, text, speaker)

    private fun record(vararg fragments: TranscriptFragment) = TranscriptRecord(
        id = "record-1", title = "會議逐字稿", createdAtMs = 1_700_000_000_000,
        settings = SessionSettings(), fragments = fragments.toList(),
    )

    @Test
    fun concatenatesCjkDeltasWithoutInventingSpaces() {
        val document = record(fragment("1", "我們", 100, 300), fragment("2", "先確認", 300, 500), fragment("3", "交易金額。", 500, 1000))
        assertEquals("1\n00:00:00,100 --> 00:00:01,000\n我們先確認交易金額。\n", SubtitleExporter.srt(document, TranscriptChannel.INPUT))
    }

    @Test
    fun preservesProviderSpacesAndSplitEnglishWords() {
        val document = record(fragment("1", "The trans", 0, 200), fragment("2", "action", 200, 400), fragment("3", " was approved.", 400, 800))
        assertTrue(SubtitleExporter.srt(document, TranscriptChannel.INPUT).contains("The transaction was approved."))
    }

    @Test
    fun interleavedTranslationUsesIndependentTimesAndDoesNotInheritInputSpeaker() {
        val document = record(
            fragment("input-1", "Good morning.", 200, 900, speaker = "Alice"),
            fragment("output-1", "早安。", 1400, 1900, TranscriptChannel.OUTPUT),
            fragment("input-2", "Next item.", 2000, 2400, speaker = "Bob"),
        )
        val output = SubtitleExporter.srt(document, TranscriptChannel.OUTPUT)
        assertEquals("1\n00:00:01,400 --> 00:00:01,900\n早安。\n", output)
        assertFalse(output.contains("Alice"))
        assertFalse(output.contains("Good morning"))
    }

    @Test
    fun speakerChangeSplitsAdjacentCuesAndExplicitlyMarksManualAttribution() {
        val document = record(fragment("1", "Hello.", 0, 300, speaker = "A"), fragment("2", "Hi.", 300, 600, speaker = "B"))
        val srt = SubtitleExporter.srt(document, TranscriptChannel.INPUT)
        assertTrue(srt.contains("[手動：A] Hello."))
        assertTrue(srt.contains("\n2\n00:00:00,300 --> 00:00:00,600\n[手動：B] Hi."))
    }

    @Test
    fun overlappingDeltasStayIndependentRatherThanLosingProviderTiming() {
        val document = record(fragment("1", "first", 0, 500), fragment("2", "second", 200, 700))
        val srt = SubtitleExporter.srt(document, TranscriptChannel.INPUT)
        assertTrue(srt.contains("00:00:00,000 --> 00:00:00,500"))
        assertTrue(srt.contains("00:00:00,200 --> 00:00:00,700"))
    }

    @Test
    fun largeGapOrDurationCreatesAnotherCue() {
        val gap = SubtitleExporter.srt(record(fragment("1", "a", 0, 200), fragment("2", "b", 900, 1200)), TranscriptChannel.INPUT)
        assertTrue(gap.contains("\n2\n"))
        val duration = SubtitleExporter.srt(record(fragment("1", "a", 0, 5000), fragment("2", "b", 5000, 6500)), TranscriptChannel.INPUT)
        assertTrue(duration.contains("\n2\n"))
    }

    @Test
    fun sortsByStartWhileRetainingOrderOfEqualTimestampDeltas() {
        val document = record(fragment("3", "later", 7000, 7300), fragment("1", "first", 0, 100), fragment("2", "second", 0, 150))
        val srt = SubtitleExporter.srt(document, TranscriptChannel.INPUT)
        assertTrue(srt.indexOf("first") < srt.indexOf("second"))
        assertTrue(srt.indexOf("second") < srt.indexOf("later"))
    }

    @Test
    fun webVttEscapesMarkupArrowsAndBlankLineCueInjection() {
        val document = record(fragment("1", "<v Bob>A & B\r\n\r\n00:01 --> 00:02\u0000", 0, 100, speaker = "Eve\n\n<v X>"))
        val vtt = SubtitleExporter.vtt(document, TranscriptChannel.INPUT)
        assertTrue(vtt.startsWith("WEBVTT\n\n1\n00:00:00.000 --> 00:00:00.100\n"))
        assertTrue(vtt.contains("&lt;v Bob&gt;A &amp; B\n00:01 --&gt; 00:02"))
        assertFalse(vtt.contains("<v"))
        assertFalse(vtt.contains('\u0000'))
        assertFalse(vtt.contains("B\n\n00:01"))
    }

    @Test
    fun emptyTracksAreValidAndDoNotCreateBlankCues() {
        assertEquals("", SubtitleExporter.srt(record(), TranscriptChannel.INPUT))
        assertEquals("WEBVTT\n\n", SubtitleExporter.vtt(record(), TranscriptChannel.OUTPUT))
        assertEquals("", SubtitleExporter.srt(record(fragment("1", " \n ", 0, 1)), TranscriptChannel.INPUT))
    }

    @Test
    fun timestampFormattingCarriesHoursAndSupportsLongSessions() {
        val srt = SubtitleExporter.srt(record(fragment("1", "long meeting", 3599999, 3600001)), TranscriptChannel.INPUT)
        assertTrue(srt.contains("00:59:59,999 --> 01:00:00,001"))
        val veryLong = SubtitleExporter.srt(record(fragment("1", "duration", 360_000_000, 360_000_001)), TranscriptChannel.INPUT)
        assertTrue(veryLong.contains("100:00:00,000 --> 100:00:00,001"))
    }

    @Test
    fun rejectsInvalidOrZeroLengthTimestampRanges() {
        assertFailsWith<IllegalArgumentException> { fragment("1", "a", -1, 20) }
        assertFailsWith<IllegalArgumentException> { fragment("1", "a", 20, 20) }
        assertFailsWith<IllegalArgumentException> { fragment("1", "a", 20, 19) }
    }

    @Test
    fun textExportKeepsBothChannelsAndMakesManualLabelsExplicit() {
        val text = SubtitleExporter.text(record(fragment("1", "Question?", 0, 500, speaker = "主持人"), fragment("2", "問題？", 700, 1200, TranscriptChannel.OUTPUT)))
        assertTrue(text.contains("[原文] [手動：主持人] Question?"))
        assertTrue(text.contains("[譯文] 問題？"))
        assertTrue(text.contains("各自的音訊時間"))
    }
}
