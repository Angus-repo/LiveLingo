package app.livelingo.app

import app.livelingo.core.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ParagraphFormatterTest {
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
