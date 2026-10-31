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
        val inputId = windows.single { it.channel == TranscriptChannel.INPUT }.existingBreakIds().single()
        val layouts = parseParagraphLayouts("""{"input":[$inputId],"output":[$breakId],"alignment":[{"input":$inputId,"output":$breakId}]}""", windows, emptyList())
        val updated = r.copy(paragraphLayouts = layouts)
        val displayed = liveCaptionText(updated, TranscriptChannel.OUTPUT)
        assertTrue("知道，\n\n我可能會做第二部分" in displayed)
        assertEquals(translated.filterNot(Char::isWhitespace), displayed.filterNot(Char::isWhitespace))
        assertTrue("知道，\n\n我可能會做第二部分" in SubtitleExporter.text(updated))
        assertEquals(r.fragments, updated.fragments)
        assertEquals("原文：已分段；譯文：依原文分段", paragraphProgress(updated))
    }
    private val record = TranscriptRecord("r", "x", 0, SessionSettings(), listOf(
        TranscriptFragment("in", TranscriptChannel.INPUT, 0, 1000, "Taiwan is 4.95 trillion. India is 4.92 trillion. How did Taiwan pull ahead?"),
        TranscriptFragment("out", TranscriptChannel.OUTPUT, 5, 1500, "台湾是4.95兆美元。印度是4.92兆美元。那么，台湾是如何领先的？")
    ))

    @Test fun idsAreTranslatedToValidatedOffsetsWithoutModelRewriting() {
        val windows = paragraphWindows(record)
        val layouts = parseParagraphLayouts("""{"input":[2],"output":[2],"alignment":[{"input":2,"output":2}]}""", windows, emptyList())
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
        val completed = record.copy(paragraphLayouts = parseParagraphLayouts("""{"input":[],"output":[],"alignment":[]}""", windows, emptyList()))
        assertEquals("原文：AI 判斷維持單段；譯文：依原文維持單段", paragraphProgress(completed))
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

    @Test fun screenshotTranslationFollowsOriginalInsteadOfKeepingFourProviderParagraphs() {
        val source = "Today's discussion is about the Indian accent and If you follow a few simple steps and a few simple rules you'll be able to master the Indian accent you know, really easily Let's start off with the T sound.\n\nSo the T sounds something like this T"
        val output = "今天要討論的是印度口音。\n\n如果你遵循幾個簡單的步驟和規則，你會很容易就能學會印度口音。\n\n先從T的發音開始。\n\nT的發音大概是這樣 T，不是T。也就是"
        val r = record.copy(fragments = listOf(
            TranscriptFragment("in", TranscriptChannel.INPUT, 0, 20000, source),
            TranscriptFragment("out", TranscriptChannel.OUTPUT, 0, 20000, output)))
        val windows = paragraphWindows(r)
        val inputId = windows.first { it.channel == TranscriptChannel.INPUT }.existingBreakIds().single()
        val outputId = windows.first { it.channel == TranscriptChannel.OUTPUT }.ends.indexOf(output.indexOf("先從T的發音開始。") + "先從T的發音開始。".length) + 1
        val layouts = parseParagraphLayouts("""{"input":[$inputId],"output":[$outputId],"alignment":[{"input":$inputId,"output":$outputId}]}""", windows, emptyList())
        val updated = r.copy(paragraphLayouts = layouts)
        assertEquals(2, liveCaptionText(updated, TranscriptChannel.INPUT).split("\n\n").size)
        val translated = liveCaptionText(updated, TranscriptChannel.OUTPUT)
        assertEquals(2, translated.split("\n\n").size)
        assertTrue(translated.contains("先從T的發音開始。\n\nT的發音"))
        assertEquals(output.filterNot(Char::isWhitespace), translated.filterNot(Char::isWhitespace))
        assertTrue(translated in SubtitleExporter.text(updated))
        val restored = WireJson.decodeFromString(TranscriptRecord.serializer(), WireJson.encodeToString(TranscriptRecord.serializer(), updated))
        assertEquals(translated, liveCaptionText(restored, TranscriptChannel.OUTPUT))
        assertEquals(r.fragments, restored.fragments)
    }

    @Test fun singleUnpunctuatedOriginalStillConstrainsTranslationToOneParagraph() {
        val r = record.copy(fragments = listOf(
            TranscriptFragment("in", TranscriptChannel.INPUT, 0, 1000, "Today we discuss how to learn the Indian accent"),
            TranscriptFragment("out", TranscriptChannel.OUTPUT, 0, 1000, "今天我們要討論。\n\n如何學會。\n\n印度口音。")))
        val windows = paragraphWindows(r)
        assertEquals(2, windows.size)
        assertTrue(windows.first { it.channel == TranscriptChannel.INPUT }.ends.isEmpty())
        val updated = r.copy(paragraphLayouts = parseParagraphLayouts("""{"input":[],"output":[],"alignment":[]}""", windows, emptyList()))
        assertFalse('\n' in liveCaptionText(updated, TranscriptChannel.OUTPUT))
        assertEquals("原文：AI 判斷維持單段；譯文：依原文維持單段", paragraphProgress(updated))
    }

    @Test fun translationBreaksRequireUniqueOrderedSelectedSourceMappings() {
        val windows = paragraphWindows(record)
        listOf(
            """{"input":[1],"output":[1]}""",
            """{"input":[],"output":[1],"alignment":[{"input":1,"output":1}]}""",
            """{"input":[1],"output":[1,2],"alignment":[{"input":1,"output":1},{"input":1,"output":2}]}""",
            """{"input":[1,2],"output":[1,2],"alignment":[{"input":2,"output":1},{"input":1,"output":2}]}""",
            """{"input":[1],"output":[1,2],"alignment":[{"input":1,"output":1}]}""",
            """{"input":[1],"output":[1],"alignment":[{"input":"1","output":1}]}""",
        ).forEach { assertFails { parseParagraphLayouts(it, windows, emptyList()) } }
    }

    @Test fun oversizedOriginalIsBoundedContextInsteadOfBeingTreatedAsMissing() {
        val source = "a".repeat(20_000) + "."
        val r = record.copy(fragments = listOf(
            TranscriptFragment("in", TranscriptChannel.INPUT, 0, 1000, source),
            TranscriptFragment("out", TranscriptChannel.OUTPUT, 0, 1000, "第一句。\n\n第二句。\n\n第三句。")))
        val windows = paragraphWindows(r)
        val input = windows.single { it.channel == TranscriptChannel.INPUT }
        assertTrue(input.ends.isEmpty())
        assertEquals(1000, input.tail.length)
        assertTrue(paragraphRequest("gpt-6-luna", windows).length < 6500)
        val updated = r.copy(paragraphLayouts = parseParagraphLayouts("""{"input":[],"output":[],"alignment":[]}""", windows, emptyList()))
        assertEquals(source, currentParagraphLayout(updated, windows.single { it.channel == TranscriptChannel.OUTPUT })?.originalReference)
    }

    @Test fun missingCounterpartWaitsAndChangedOriginalInvalidatesTranslationAlignment() {
        val layouts = parseParagraphLayouts("""{"input":[1],"output":[],"alignment":[]}""", paragraphWindows(record), emptyList())
        val waiting = record.copy(paragraphLayouts = layouts)
        assertEquals("原文：已分段；譯文：等待對應原文段落", paragraphProgress(waiting))
        val grown = waiting.copy(fragments = waiting.fragments + TranscriptFragment("more", TranscriptChannel.INPUT, 2000, 3000, " More context."))
        val out = paragraphWindows(grown).first { it.channel == TranscriptChannel.OUTPUT }
        assertNull(currentParagraphLayout(grown, out))
        assertTrue("譯文：待 AI 整理" in paragraphProgress(grown))
    }

    @Test fun rollingWindowKeepsEarlierApprovedBreaksWithoutRevivingProviderNewlines() {
        fun r(n: Int) = record.copy(fragments = listOf(TranscriptFragment("out", TranscriptChannel.OUTPUT, 0, 1000, (1..n).joinToString("\n\n") { "句子$it。" })))
        val first = r(45)
        val old = parseParagraphLayouts("""{"output":[2]}""", paragraphWindows(first), emptyList())
        val grown = r(70)
        val current = grown.copy(paragraphLayouts = parseParagraphLayouts("""{"output":[2]}""", paragraphWindows(grown), old))
        val text = liveCaptionText(current, TranscriptChannel.OUTPUT)
        assertTrue("句子1。\n\n句子2。" in text) // Outside the first window, retain original paragraphs.
        assertTrue("句子7。\n\n句子8。" in text) // Earlier approved AI break survives.
        assertTrue("句子9。 句子10。" in text) // Previously removed provider break does not reappear.
        assertTrue("句子32。\n\n句子33。" in text)
        assertEquals(captionSourceText(grown, TranscriptChannel.OUTPUT).filterNot(Char::isWhitespace), text.filterNot(Char::isWhitespace))
    }
}
