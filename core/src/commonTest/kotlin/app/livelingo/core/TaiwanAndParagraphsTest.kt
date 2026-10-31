package app.livelingo.core

import kotlin.test.*

class TaiwanAndParagraphsTest {
    @Test fun acceptedReflowReplacesOldBreaksButPreservesWordsNumbersAndNewTail() {
        val source = "金額1,234.56美元。\n\n核對完成。\n接著討論部署。"
        val end = source.indexOf("核對完成。") + "核對完成。".length
        val layout = ParagraphLayout(TranscriptChannel.OUTPUT, source, listOf(end), replaceExistingBreaks = true)
        val tail = "\n\n新抵達的尾段\n保留換行"
        val formatted = paragraphText(source + tail, layout)
        assertEquals("金額1,234.56美元。 核對完成。\n\n接著討論部署。" + tail, formatted)
        assertEquals((source + tail).filterNot(Char::isWhitespace), formatted.filterNot(Char::isWhitespace))
        assertEquals("金額1,234.56美元。 核對完成。 接著討論部署。", paragraphText(source, layout.copy(breakOffsets = emptyList())))
    }

    @Test fun legacyLayoutsDoNotRemoveExistingNewlines() {
        val layout = WireJson.decodeFromString(ParagraphLayout.serializer(), """{"channel":"OUTPUT","source":"第一句。\n\n第二句。","breakOffsets":[]}""")
        assertTrue('\n' in layout.source)
        assertFalse(layout.replaceExistingBreaks)
        assertNull(layout.originalReference)
        assertEquals(layout.source, paragraphText(layout.source, layout))
    }

    @Test fun paragraphCandidatesProtectNumbersQuotesAndParentheses() {
        val text = "營收1,234.56美元，印度1,00,000盧比，舉例“Peter，Peter”，備註（甲，乙）；下一個主題\n再談計畫"
        val ends = paragraphCandidateEnds(text)
        assertEquals(listOf("營收1,234.56美元，", "印度1,00,000盧比，", "舉例“Peter，Peter”，", "備註（甲，乙）；", "下一個主題\n"),
            ends.mapIndexed { i, end -> text.substring(ends.getOrNull(i - 1) ?: 0, end) })
        assertEquals(emptyList(), paragraphCandidateEnds("1,234.56 1,00,000 4.95 2026.10.31"))
        assertEquals(listOf(7, 10), paragraphCandidateEnds("\"a, b\", x, y"))
    }

    @Test fun aiClauseBreakOnlyAddsWhitespaceAndDoesNotForceEveryCommaToNewLine() {
        val text = "先核對1,234.56美元，這是第一個主題，接著討論部署，最後確認時間"
        assertEquals(text, paragraphText(text))
        val boundary = text.indexOf("接著討論")
        val formatted = paragraphText(text, ParagraphLayout(TranscriptChannel.OUTPUT, text, listOf(boundary)))
        assertTrue("主題，\n\n接著" in formatted)
        assertEquals(text, formatted.replace("\n\n", ""))
        assertTrue("1,234.56" in formatted)
    }

    @Test fun numericCommasSurviveSeparateSubtitleCuesAndTimestampDetails() {
        val record = TranscriptRecord("r", "x", 0, SessionSettings(), listOf(
            TranscriptFragment("a", TranscriptChannel.OUTPUT, 0, 100, "收入1,"),
            TranscriptFragment("b", TranscriptChannel.OUTPUT, 1000, 2000, "234.56美元,"),
            TranscriptFragment("c", TranscriptChannel.OUTPUT, 3000, 4000, " 明天繼續。"),
        ))
        val srt = SubtitleExporter.srt(record, TranscriptChannel.OUTPUT)
        assertTrue("收入1,\n" in srt)
        assertTrue("234.56美元，\n" in srt)
        val text = SubtitleExporter.text(record)
        assertTrue("收入1,234.56美元，明天繼續。" in text)
        assertFalse("收入1，" in text)
        assertEquals(listOf("1", ",", "234"), record.localizedChunks(listOf("1", ",", "234"), TranscriptChannel.OUTPUT))
    }

    @Test fun traditionalChineseSentenceCommasAreFullWidthWithoutChangingNumbers() {
        assertEquals("台灣，今天成長，明天繼續。", TaiwanText.convert("台湾, 今天成长,明天继续。"))
        assertEquals("1,234.56 美元，1,00,000 盧比，4.95%", TaiwanText.convert("1,234.56 美元, 1,00,000 卢比, 4.95%"))
        assertEquals("第一句，\n第二句，第三句", TaiwanText.convert("第一句,\n第二句， 第三句"))
        val record = TranscriptRecord("r", "x", 0, SessionSettings(targetLanguage = Language.ENGLISH))
        assertEquals("Hello, world", record.localizedText("Hello, world", TranscriptChannel.OUTPUT))
    }

    @Test fun traditionalCommasAppearInEveryReadableExportButNotRawFragmentsOrSrtTimes() {
        val raw = "台湾成长, 收入1,234.56美元, 明天继续。"
        val record = TranscriptRecord("r", "x", 0, SessionSettings(), listOf(TranscriptFragment("f", TranscriptChannel.OUTPUT, 5, 1000, raw)))
        val expected = "台灣成長，收入1,234.56美元，明天繼續。"
        assertTrue(expected in SubtitleExporter.text(record))
        assertTrue(expected in SubtitleExporter.srt(record, TranscriptChannel.OUTPUT))
        assertTrue(expected in SubtitleExporter.vtt(record, TranscriptChannel.OUTPUT))
        assertTrue("00:00:00,005 --> 00:00:01,000" in SubtitleExporter.srt(record, TranscriptChannel.OUTPUT))
        assertEquals(raw, record.fragments.single().text)
    }

    @Test fun screenshotRegressionPreservesNumbersAndConvertsMixedScript() {
        val text = TaiwanText.convert("台湾是4.95兆美元，而印度是4.92兆美元。印度的经济体规模更大，人口几乎是台湾的六十倍。印度有更多工程师、创业公司、消费者，也有更多上市公司。")
        listOf("台灣", "經濟", "規模", "幾乎", "工程師", "消費者", "4.95", "4.92").forEach { assertTrue(it in text, text) }
        listOf('湾','济','规','几','师','业','费').forEach { assertFalse(it in text, text) }
    }

    @Test fun contextualConversionUsesTaiwanTermsWithoutChangingEnglishOrDecimals() {
        assertEquals("頭髮的發展", TaiwanText.convert("头发的发展"))
        val result = TaiwanText.convert("软件、信息、网络、视频、服务器：TSMC 4.95 4.9592 2,300 60%")
        listOf("軟體", "資訊", "網路", "影片", "伺服器", "TSMC 4.95 4.9592 2,300 60%").forEach { assertTrue(it in result, result) }
    }

    @Test fun onlyTaiwanSelectedTracksAreConverted() {
        val record = TranscriptRecord("r", "x", 0, SessionSettings(targetLanguage = Language.JAPANESE))
        assertEquals("台湾", record.localizedText("台湾", TranscriptChannel.OUTPUT))
        assertEquals("台湾", record.copy(settings = SessionSettings()).localizedText("台湾", TranscriptChannel.INPUT))
        assertEquals("台灣", record.copy(settings = SessionSettings()).localizedText("台湾", TranscriptChannel.OUTPUT))
    }

    @Test fun paragraphsDoNotSplitDecimalNumbersOrIncompleteSentences() {
        val source = "Taiwan is 4.95 trillion dollars. India is at 4.9592 trillion. How did Taiwan pull ahead? The answer is"
        val ends = sentenceEnds(source)
        assertEquals(3, ends.size)
        val formatted = paragraphText(source, ParagraphLayout(TranscriptChannel.INPUT, source, listOf(ends[1])))
        assertTrue("4.9592 trillion.\n\nHow" in formatted)
        assertTrue(formatted.endsWith("The answer is"))
        assertEquals(source.filterNot(Char::isWhitespace), formatted.filterNot(Char::isWhitespace))
    }

    @Test fun staleAiLayoutDoesNotApplyToReorderedText() {
        val layout = ParagraphLayout(TranscriptChannel.OUTPUT, "第一句。第二句。", listOf(4))
        assertEquals("新句子。", paragraphText("新句子。", layout))
        assertEquals("第一句。\n\n第二句。第三句", paragraphText("第一句。第二句。第三句", layout))
    }

    @Test fun openAiEndpointIsPinnedAndUsesLiveProtocol() {
        val connection = FoundryConnection("https://api.openai.com", "gpt-live-1", ModelProvider.OPENAI)
        assertNull(connection.validationError())
        assertEquals("wss://api.openai.com/v1/live/sessions", connection.webSocketUrl())
        assertEquals("https://api.openai.com/v1/chat/completions", connection.chatCompletionsUrl())
        assertTrue("session.start" in foundryStartPayload(connection, SessionSettings()))
        listOf("https://api.openai.com.evil.test", "https://api.openai.com@evil.test", "https://api.openai.com?key=x", "http://api.openai.com").forEach {
            assertNotNull(connection.copy(endpoint = it).validationError())
        }
        assertEquals("https://test.openai.azure.com/openai/v1/chat/completions", FoundryConnection("https://test.openai.azure.com").chatCompletionsUrl())
    }

    @Test fun subtitleAndReadableTextExportsUseTraditionalChineseAndKeepTimes() {
        val record = TranscriptRecord("r", "x", 0, SessionSettings(), listOf(TranscriptFragment("f", TranscriptChannel.OUTPUT, 5, 1000, "台湾经济4.95兆美元。")))
        assertTrue("台灣經濟4.95兆美元。" in SubtitleExporter.srt(record, TranscriptChannel.OUTPUT))
        assertTrue("00:00:00,005 --> 00:00:01,000" in SubtitleExporter.srt(record, TranscriptChannel.OUTPUT))
        assertTrue("台灣經濟4.95兆美元。" in SubtitleExporter.text(record))
        assertEquals("台湾经济4.95兆美元。", record.fragments.single().text)
    }
}
