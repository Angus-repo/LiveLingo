package app.livelingo.app

import app.livelingo.core.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ParagraphFormatterTest {
    @Test fun commaOnlyTranslationIsIncludedAlongsidePunctuatedOriginal() {
        val source = "All right, I'm sure you can hear that difference. So go away and practise that.\n\nAnd I might do a part two. So hit like on this"
        val translated = "好就是它，就是它所以“Vater，Vater”另一個詞可以是“Peter，Peter”“Peter，ich sprach，Peter，am Telefon”“我在電話裡和 Peter 說了話”好吧，所以我想你能聽出其中的區別而且我試著把這些特別的、聽起來不同的音給拆解出來所以去練練這個你知道，我可能會做第二部分呢，如果你覺得這個影片有幫助，就點個讚"
        val r = TranscriptRecord("screenshot", "x", 0, SessionSettings(), listOf(
            TranscriptFragment("in", TranscriptChannel.INPUT, 0, 20_000, source),
            TranscriptFragment("out", TranscriptChannel.OUTPUT, 0, 20_000, translated),
        ))
        assertEquals(0, sentenceEnds(translated).size)
        val windows = paragraphWindows(r)
        assertEquals(setOf(TranscriptChannel.INPUT, TranscriptChannel.OUTPUT), windows.map { it.channel }.toSet())
        val output = windows.single { it.channel == TranscriptChannel.OUTPUT }
        val breakId = output.ends.indexOf(translated.indexOf("我可能會做第二部分")) + 1
        assertTrue(breakId > 0)
        val layouts = parseParagraphLayouts("""{"input":[2],"output":[$breakId]}""", windows, emptyList())
        val updated = r.copy(paragraphLayouts = layouts)
        val displayed = liveCaptionText(updated, TranscriptChannel.OUTPUT)
        assertTrue("知道，\n\n我可能會做第二部分" in displayed)
        assertEquals(translated.filterNot(Char::isWhitespace), displayed.filterNot(Char::isWhitespace))
        assertTrue("知道，\n\n我可能會做第二部分" in SubtitleExporter.text(updated))
        assertEquals(r.fragments, updated.fragments)
        assertEquals("原文：已分段；譯文：已分段", paragraphProgress(updated))
    }
    private val record = TranscriptRecord("r", "x", 0, SessionSettings(), listOf(
        TranscriptFragment("in", TranscriptChannel.INPUT, 0, 1000, "Taiwan is 4.95 trillion. India is 4.92 trillion. How did Taiwan pull ahead?"),
        TranscriptFragment("out", TranscriptChannel.OUTPUT, 5, 1500, "台湾是4.95兆美元。印度是4.92兆美元。那么，台湾是如何领先的？")
    ))

    @Test fun idsAreTranslatedToValidatedOffsetsWithoutModelRewriting() {
        val windows = paragraphWindows(record)
        val layouts = parseParagraphLayouts("""{"input":[2],"output":[2]}""", windows, emptyList())
        val formatted = liveCaptionText(record.copy(paragraphLayouts = layouts), TranscriptChannel.OUTPUT)
        assertTrue("4.92兆美元。\n\n" in formatted)
        assertFalse("台湾" in formatted)
        assertEquals(captionSourceText(record, TranscriptChannel.OUTPUT).filterNot(Char::isWhitespace), formatted.filterNot(Char::isWhitespace))
    }

    @Test fun invalidIdsOrRewrittenTextAreRejected() {
        listOf("""{"input":[0],"output":[2]}""", """{"input":[999],"output":[2]}""", """{"input":[1.5],"output":[]}""", """{"input":["2"],"output":[]}""", """{"input":"rewritten","output":[]}""", """{"input":[],"output":[],"text":"replacement"}""").forEach {
            assertFails { parseParagraphLayouts(it, paragraphWindows(record), emptyList()) }
        }
    }

    @Test fun commaUnitsAndUnfinishedTailAreSentWithoutInventingPunctuation() {
        val raw = "營收1,234.56美元，接著分析原因，明天再"
        val r = record.copy(fragments = listOf(TranscriptFragment("out", TranscriptChannel.OUTPUT, 0, 1000, raw)))
        val window = paragraphWindows(r).single()
        val request = WireJson.parseToJsonElement(paragraphRequest("gpt-6-luna", listOf(window))).jsonObject
        val data = WireJson.parseToJsonElement(request["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonPrimitive.content).jsonObject
        val units = data["output"]!!.jsonArray
        assertEquals(listOf("營收1,234.56美元，", "接著分析原因，"), units.map { it.jsonObject["text"]!!.jsonPrimitive.content })
        assertEquals("明天再", data["reference_tails"]!!.jsonObject["output"]!!.jsonPrimitive.content)
        val layout = parseParagraphLayouts("""{"output":[2]}""", listOf(window), emptyList()).single()
        assertEquals("營收1,234.56美元，接著分析原因，\n\n明天再", liveCaptionText(r.copy(paragraphLayouts = listOf(layout)), TranscriptChannel.OUTPUT))
        val grown = r.copy(fragments = listOf(r.fragments.single().copy(text = raw + "檢查")), paragraphLayouts = listOf(layout))
        assertEquals("譯文：待 AI 整理", paragraphProgress(grown))
    }

    @Test fun statusDistinguishesSkippedTrackFromAiChoosingOneParagraph() {
        val windows = paragraphWindows(record)
        val inputLayout = parseParagraphLayouts("""{"input":[1]}""", windows.filter { it.channel == TranscriptChannel.INPUT }, emptyList())
        val skipped = record.copy(fragments = record.fragments.map { if (it.channel == TranscriptChannel.OUTPUT) it.copy(text = "還沒有可切分的位置") else it }, paragraphLayouts = inputLayout)
        assertEquals("原文：已分段；譯文：等待可分段內容", paragraphProgress(skipped))
        val completed = record.copy(paragraphLayouts = parseParagraphLayouts("""{"input":[1],"output":[]}""", windows, emptyList()))
        assertEquals("原文：已分段；譯文：AI 判斷維持單段", paragraphProgress(completed))
        assertFails { parseParagraphLayouts("""{"input":[1]}""", windows, emptyList()) }
        val output = windows.single { it.channel == TranscriptChannel.OUTPUT }
        assertTrue(parseParagraphLayouts("""{"output":[${output.ends.size}]}""", listOf(output), emptyList()).single().breakOffsets.isEmpty())
    }

    @Test fun incompleteTailIsNotTreatedAsAFinishedSentence() {
        val tail = record.copy(fragments = record.fragments + TranscriptFragment("tail", TranscriptChannel.INPUT, 1000, 2000, " Next number is 4."))
        val window = paragraphWindows(tail).first { it.channel == TranscriptChannel.INPUT }
        assertTrue(window.source.contains("4.95"))
        val request = WireJson.parseToJsonElement(paragraphRequest("test-model", listOf(window))).jsonObject
        assertEquals("test-model", request["model"]?.jsonPrimitive?.content)
        assertFalse("apiKey" in request.toString())
    }

    @Test fun longSessionsSendOnlyBoundedRecentSentencesAndRetainEarlierBreaks() {
        val text = (1..400).joinToString(" ") { "This is sentence $it." }
        val longRecord = record.copy(fragments = listOf(TranscriptFragment("long", TranscriptChannel.INPUT, 0, 10000, text)))
        val window = paragraphWindows(longRecord).single()
        assertEquals(40, window.ends.size)
        assertTrue(window.start > 0)
        val earlier = ParagraphLayout(TranscriptChannel.INPUT, text.take(window.start), listOf(19))
        val layout = parseParagraphLayouts("""{"input":[2]}""", listOf(window), listOf(earlier)).single()
        assertEquals(listOf(19, window.ends[1]), layout.breakOffsets)
    }
}
