package app.livelingo.core

import kotlin.test.*

class TaiwanAndParagraphsTest {
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
