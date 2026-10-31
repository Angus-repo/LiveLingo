package app.livelingo.app

import app.livelingo.core.*
import kotlinx.serialization.json.*
import kotlin.test.*

class PunctuationFormatterTest {
    private val source = "那個標籤然後我真的認為你只要有說的很準很多這是噪音就社群媒體都在focus一件事情你有四萬個人四千個人反對一千個印度人進來到台灣所以他們怕的是不是關於那個政策現在我聽到的噪音都是會有文化影響 會在社群有影響"
    private val record = TranscriptRecord("screenshot", "x", 0, SessionSettings(), listOf(TranscriptFragment("in", TranscriptChannel.INPUT, 0, 15000, source)))

    private fun response(window: ParagraphWindow, marks: List<Pair<Int, String>>, breaks: List<Int> = emptyList()): String = buildJsonObject {
        putJsonArray(window.key) { breaks.forEach { add(window.ends.indexOf(it) + 1) } }
        putJsonObject("punctuation") { putJsonArray(window.key) { marks.forEach { (offset, mark) ->
            add(buildJsonObject { put("after", window.ends.indexOf(offset) + 1); put("mark", mark) })
        } } }
    }.toString()

    @Test fun screenshotWithoutAnyPunctuationIsSentAndSupplementSharesFormattedOriginal() {
        val window = paragraphWindows(record, final = true).single()
        assertTrue(window.restorePunctuation)
        val clause = source.indexOf("就社群")
        val paragraph = source.indexOf("所以")
        val question = source.indexOf("現在")
        val layouts = parseParagraphLayouts(response(window, listOf(clause to "。", paragraph to "。", question to "？", source.length to "。"), listOf(paragraph)), listOf(window), emptyList())
        val updated = record.copy(paragraphLayouts = layouts)
        val original = liveCaptionText(updated, TranscriptChannel.INPUT)
        assertTrue("這是噪音。就社群" in original)
        assertTrue("台灣。\n\n所以" in original)
        assertTrue("那個政策？現在" in original)
        assertEquals(source.filter { it.isLetterOrDigit() }, original.filter { it.isLetterOrDigit() })
        assertEquals("〔原文補充〕\n" + TaiwanText.convert(original), liveCaptionText(updated, TranscriptChannel.OUTPUT))
        assertEquals(record.fragments, updated.fragments)
        assertTrue("已補標點與分段" in paragraphProgress(updated))
        assertTrue("沿用原文標點與段落" in paragraphProgress(updated))
    }

    @Test fun requestMarksSafeIdsAndFinalityButContainsNoRewrittenTranscript() {
        val window = paragraphWindows(record).single()
        val request = WireJson.parseToJsonElement(paragraphRequest("gpt-6-luna", listOf(window))).jsonObject
        val data = WireJson.parseToJsonElement(request["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonPrimitive.content).jsonObject
        assertTrue(data["punctuation_tracks"]!!.jsonObject["input"]!!.jsonPrimitive.boolean)
        assertFalse(data["finalized_tracks"]!!.jsonObject["input"]!!.jsonPrimitive.boolean)
        val units = data["input"]!!.jsonArray
        assertEquals(source, units.joinToString("") { it.jsonObject["text"]!!.jsonPrimitive.content })
        for (offset in listOf(source.indexOf("萬") + 1, source.indexOf("千") + 1, source.length)) {
            val unit = units[window.ends.indexOf(offset)].jsonObject
            assertFalse(unit["can_insert_punctuation"]!!.jsonPrimitive.boolean)
        }
    }

    @Test fun stopCanFinishShortUtteranceWithoutPunctuatingItsLiveTail() {
        val r = record.copy(fragments = listOf(record.fragments.single().copy(text = "確認收款")))
        assertTrue(paragraphWindows(r).isEmpty())
        val window = paragraphWindows(r, final = true).single()
        val layouts = parseParagraphLayouts(response(window, listOf(window.source.length to "。")), listOf(window), emptyList())
        assertEquals("確認收款。", liveCaptionText(r.copy(paragraphLayouts = layouts), TranscriptChannel.INPUT))
    }

    @Test fun rejectsWordReplacementUnsafeNumberPunctuationEmptyRestorationAndBareTokenBreaks() {
        val window = paragraphWindows(record).single()
        val bad = listOf(
            response(window, listOf(source.indexOf("萬") + 1 to "，")),
            response(window, listOf(4 to "刪除原文")),
            response(window, listOf(4 to "，", 4 to "。")),
            response(window, listOf(source.length to "。")),
            response(window, emptyList()),
            response(window, listOf(4 to "，"), listOf(8)),
            """{"input":[],"punctuation":{"input":[]},"text":"改寫後的文字"}""",
        )
        bad.forEach { assertFails { parseParagraphLayouts(it, listOf(window), emptyList()) } }
    }

    @Test fun rollingUnpunctuatedWindowIsBoundedAndRetainsApprovedPrefixMarks() {
        val long = "今天討論付款流程明天檢查部署結果".repeat(80)
        val r = record.copy(fragments = listOf(record.fragments.single().copy(text = long)))
        val window = paragraphWindows(r).single()
        assertTrue(window.start > 0)
        assertTrue(window.ends.size <= 320)
        assertTrue(long.length - window.start <= 1800)
        val first = window.ends.first { canInsertPunctuation(long, it) }
        val old = ParagraphLayout(TranscriptChannel.INPUT, long.take(window.start), listOf(8), punctuation = listOf(PunctuationInsertion(8, "。")))
        val layout = parseParagraphLayouts(response(window, listOf(first to "，")), listOf(window), listOf(old)).single()
        assertTrue(PunctuationInsertion(8, "。") in layout.punctuation)
        assertTrue(8 in layout.breakOffsets)
    }
}
