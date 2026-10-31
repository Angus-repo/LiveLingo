package app.livelingo.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.*

class TranslationSupplementTest {
    private fun record(input: String, output: String = "", settings: SessionSettings = SessionSettings()) =
        TranscriptRecord("r", "meeting", 0, settings, listOf(
            TranscriptFragment("source", TranscriptChannel.INPUT, 0, 5000, input),
            TranscriptFragment("translation", TranscriptChannel.OUTPUT, 9000, 15000, output),
        ))

    @Test fun screenshotEnglishToChineseSwitchRetainsChineseEvenWithinOneDelta() {
        val input = "I was a very young boy and I had 1400 employees in my factory manufacturing Walkman 我要去台南去鹽湖，\n\n我的秘書說OK,你就下去OK,很簡單嘛,我開"
        val record = record(input, "那時我還很年輕，在我工廠裡製造隨身聽，有1400名員工。")
        assertEquals("我要去臺南去鹽湖，\n\n我的祕書說OK，你就下去OK，很簡單嘛，我開", record.translationSupplementText())
        assertEquals(input, record.fragments.first().text)
        assertFalse(record.translationSupplementText().contains("Walkman"))
    }

    @Test fun absentAndWhitespaceOutputAlwaysHasReceivedSourceWithOriginalParagraphs() {
        for (output in listOf("", " \n\t", "…")) {
            val record = record("我们在台湾工作。\n\n明天见。", output)
            assertEquals("我們在台灣工作。\n\n明天見。", record.translationSupplementText())
        }
        val english = record("We will meet tomorrow.")
        assertEquals("We will meet tomorrow.", english.translationSupplementText())
        assertTrue(SubtitleExporter.text(english).contains("〔原文補充〕\nWe will meet tomorrow."))
    }

    @Test fun lateDeltasReplaceOnlyMatchingPrefixAndEventuallyRemoveSupplement() {
        val record = record("我要去臺南去鹽湖。我的祕書說OK。", "這裡是先前英文的譯文。")
        assertEquals("我要去臺南去鹽湖。我的祕書說OK。", record.translationSupplementText())
        val partial = record.copy(fragments = record.fragments +
            TranscriptFragment("late1", TranscriptChannel.OUTPUT, 16000, 17000, "我要去臺南"))
        assertEquals("去鹽湖。我的祕書說OK。", partial.translationSupplementText())
        val completed = partial.copy(fragments = partial.fragments +
            TranscriptFragment("late2", TranscriptChannel.OUTPUT, 17000, 18000, "去盐湖。我的秘书说 OK。"))
        assertTrue(completed.translationSupplements().isEmpty())
    }

    @Test fun splitSourceCharactersAndReverseDeliveryKeepChronologyAndNumericSeparators() {
        val fragments = listOf(
            TranscriptFragment("2", TranscriptChannel.INPUT, 100, 200, "1,"),
            TranscriptFragment("3", TranscriptChannel.INPUT, 200, 300, "234.56美元。\n\n"),
            TranscriptFragment("1", TranscriptChannel.INPUT, 0, 100, "金額是"),
            TranscriptFragment("4", TranscriptChannel.INPUT, 300, 400, "明天再見。"),
        )
        val record = TranscriptRecord("r", "x", 0, SessionSettings(), fragments)
        assertEquals("金額是1,234.56美元。\n\n明天再見。", record.translationSupplementText())
        assertEquals(0, record.translationSupplements().first().startMs)
        assertEquals(300, record.translationSupplements().first().endMs)
        assertEquals(fragments, record.fragments)
    }

    @Test fun repeatedSourceConsumesEachOutputOccurrenceOnlyOnce() {
        assertEquals("我要去臺南。", record("我要去臺南。我要去臺南。", "我要去臺南。").translationSupplementText())
        assertTrue(record("我要去臺南。我要去臺南。", "我要去臺南。我要去臺南。").translationSupplements().isEmpty())
        assertEquals("好。", record("好。好。", "好。").translationSupplementText())
        assertEquals("我", record("我", "我叫Vikram。").translationSupplementText())
    }

    @Test fun earlierEnglishTranslationCannotHideNewChineseRepetition() {
        val record = TranscriptRecord("r", "x", 0, SessionSettings(), listOf(
            TranscriptFragment("en", TranscriptChannel.INPUT, 0, 1000, "I am going to Tainan. "),
            TranscriptFragment("old", TranscriptChannel.OUTPUT, 0, 5000, "我要去臺南。"),
            TranscriptFragment("zh", TranscriptChannel.INPUT, 1000, 2000, "我要去台南。"),
        ))
        assertEquals("我要去臺南。", record.translationSupplementText())
        val late = record.copy(fragments = record.fragments +
            TranscriptFragment("late", TranscriptChannel.OUTPUT, 5000, 7000, "我要去臺南。"))
        assertTrue(late.translationSupplements().isEmpty())
    }

    @Test fun doesNotTreatDifferentNumbersOrNegationAsSameContent() {
        assertEquals("價格是12.34美元。", record("價格是12.34美元。", "價格是1234美元。").translationSupplementText())
        assertEquals("價格是-50美元。", record("價格是-50美元。", "價格是50美元。").translationSupplementText())
        assertEquals("我不會去臺南。", record("我不會去臺南。", "我會去臺南。").translationSupplementText())
    }

    @Test fun japaneseAndKoreanAreNotAssumedChineseAndOtherTargetsUseSourceWhenEmpty() {
        assertTrue(record("これは日本語です。", "這是日文。").translationSupplements().isEmpty())
        assertTrue(record("東京大學。", "東京大學。", SessionSettings(sourceLanguage = Language.JAPANESE)).translationSupplements().isEmpty())
        assertTrue(record("中文한국어。", "已有譯文。").translationSupplements().isEmpty())
        assertEquals("請翻譯這段。", record("請翻譯這段。", settings = SessionSettings(targetLanguage = Language.ENGLISH)).translationSupplementText())
    }

    @Test fun noAudioOrBlankSourceDoesNotInventText() {
        assertTrue(record(" \n\t").translationSupplements().isEmpty())
        assertTrue(TranscriptRecord("r", "x", 0, SessionSettings()).translationSupplements().isEmpty())
    }

    @Test fun savedRestoredAndAllExportsRetainSupplementWithoutRewritingProviderFragments() {
        val original = record("英文後說中文。\n\n我明天去臺南。", "這裡是英文翻譯。")
        val json = WireJson.encodeToString(original)
        val restored = WireJson.decodeFromString<TranscriptRecord>(json)
        assertEquals(original, restored)
        assertFalse(json.contains("source-supplement"))
        assertEquals(original.translationSupplementText(), restored.translationSupplementText())
        for (export in listOf(SubtitleExporter.text(restored), SubtitleExporter.srt(restored, TranscriptChannel.OUTPUT), SubtitleExporter.vtt(restored, TranscriptChannel.OUTPUT))) {
            assertTrue(export.contains("原文補充"))
            assertTrue(export.contains("我明天去臺南。"))
            assertTrue(export.contains("這裡是英文翻譯。"))
        }
        assertFalse(SubtitleExporter.srt(restored, TranscriptChannel.INPUT).contains("原文補充"))
    }
}
