package app.livelingo.core

import kotlin.test.*

class TranslationTrackTest {
    @Test fun oldRecordsExportOnlyTheSelectedTrackEvenWhenNoTranslationArrived() {
        val source = TranscriptFragment("i", TranscriptChannel.INPUT, 0, 100, "Call the API.")
        val translated = TranscriptFragment("o", TranscriptChannel.OUTPUT, 0, 100, "呼叫 API。")
        for (fragments in listOf(listOf(source), listOf(source, translated))) {
            val record = TranscriptRecord("r", "會議", 0, SessionSettings(), fragments)
            val text = SubtitleExporter.text(record)
            assertTrue(source.text in text)
            assertFalse("原文補充" in text)
            for (export in listOf(SubtitleExporter.srt(record, TranscriptChannel.OUTPUT),
                SubtitleExporter.vtt(record, TranscriptChannel.OUTPUT),
                text.substringAfter("【譯文・閱讀段落】").substringBefore("【時間戳明細】"))) {
                assertFalse(source.text in export)
                assertEquals(translated in fragments, translated.text in export)
            }
        }
    }
}
