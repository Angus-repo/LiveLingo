package app.livelingo.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.*

class PunctuationTest {
    @Test fun punctuationAndParagraphsDecorateSourceWithoutChangingStoredWords() {
        val source = "今天討論付款流程明天進行整合測試最後確認部署時間"
        val first = source.indexOf("明天")
        val second = source.indexOf("最後")
        val layout = ParagraphLayout(TranscriptChannel.INPUT, source, listOf(second), punctuation = listOf(
            PunctuationInsertion(first, "，"), PunctuationInsertion(second, "。"), PunctuationInsertion(source.length, "。")), finalized = true)
        assertEquals("今天討論付款流程，明天進行整合測試。\n\n最後確認部署時間。", paragraphText(source, layout))
        val record = TranscriptRecord("r", "x", 0, SessionSettings(), listOf(TranscriptFragment("in", TranscriptChannel.INPUT, 0, 8000, source)), listOf(layout))
        assertEquals(paragraphText(source, layout), record.translationSupplementText())
        val restored = WireJson.decodeFromString<TranscriptRecord>(WireJson.encodeToString(record))
        assertEquals(record, restored)
        assertEquals(source, restored.fragments.single().text)
        for (channel in TranscriptChannel.entries) {
            val srt = SubtitleExporter.srt(restored, channel)
            assertTrue("付款流程，" in srt)
            assertTrue("整合測試。" in srt)
            assertTrue("部署時間。" in srt)
            assertTrue("部署時間。" in SubtitleExporter.vtt(restored, channel))
        }
        assertTrue("整合測試。\n\n最後" in SubtitleExporter.text(restored))
    }

    @Test fun numericExpressionsUrlsAndTechnicalWordsHaveNoInsertionInsideThem() {
        val source = "金額1,234.56美元與四萬個人使用SpringBoot前往https://bank.example/a?x=1.2 完成測試"
        val ends = punctuationTokenEnds(source)
        for (protected in listOf("1,234.56", "SpringBoot", "https://bank.example/a?x=1.2")) {
            val start = source.indexOf(protected)
            assertTrue((start + 1 until start + protected.length).none { it in ends })
        }
        for (prefix in listOf("金額1", "金額1,234.56", source.substringBefore("萬"), source.substringBefore("個")))
            assertFalse(canInsertPunctuation(source, prefix.length))
    }

    @Test fun unfinishedTailCannotReceiveFinalPeriodAndInvalidSavedMarksAreIgnored() {
        val source = "我們今天要討論付款流程以及明天的測試"
        assertFalse(validPunctuation(source, listOf(PunctuationInsertion(source.length, "。"))))
        assertTrue(validPunctuation(source, listOf(PunctuationInsertion(source.length, "。")), true))
        for (edits in listOf(listOf(PunctuationInsertion(2, "改寫")), listOf(PunctuationInsertion(2, "，"), PunctuationInsertion(2, "。")))) {
            assertFalse(validPunctuation(source, edits))
            assertEquals(source, paragraphText(source, ParagraphLayout(TranscriptChannel.INPUT, source, emptyList(), punctuation = edits)))
        }
    }

    @Test fun supplementUsesNormalizedSourceFormattingBeforeTraditionalConversion() {
        val source = "我要去台南  然後與秘书確認需求\r\n\r\n最後確認測試"
        val snapshot = "我要去台南 然後與秘书確認需求\n\n最後確認測試"
        val boundary = snapshot.indexOf("然後") - 1
        val layout = ParagraphLayout(TranscriptChannel.INPUT, snapshot, emptyList(), punctuation = listOf(PunctuationInsertion(boundary, "，")))
        val record = TranscriptRecord("r", "x", 0, SessionSettings(), listOf(TranscriptFragment("in", TranscriptChannel.INPUT, 0, 1000, source)), listOf(layout))
        assertEquals("我要去臺南，然後與祕書確認需求\n\n最後確認測試", record.translationSupplementText())
        assertTrue("台南，" in SubtitleExporter.srt(record, TranscriptChannel.INPUT))
    }

    @Test fun oldLayoutsDecodeWithoutPunctuationAndKeepOldBehavior() {
        val layout = WireJson.decodeFromString<ParagraphLayout>("""{"channel":"INPUT","source":"甲。乙。","breakOffsets":[2]}""")
        assertTrue(layout.punctuation.isEmpty())
        assertFalse(layout.finalized)
        assertEquals("甲。\n\n乙。", paragraphText(layout.source, layout))
    }
}
