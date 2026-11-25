package app.livelingo.app

import app.livelingo.core.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.*

class TargetLanguageOutputTest {
    @Test fun clausesAreCheckedIndependentlyOfNearbyTargetText() {
        for (target in listOf(Language.ZH_TW, Language.JAPANESE, Language.KOREAN))
            assertTrue(needsTargetTranslation("API. Can we call it?", target))
        assertTrue(needsTargetTranslation("好。Please try again. 已完成。", Language.ZH_TW))
        assertTrue(needsTargetTranslation("沒問題，Yeah。", Language.ZH_TW))
        assertFalse(needsTargetTranslation("使用 Spring Boot 呼叫 POST API，編號 D50。", Language.ZH_TW))
        assertFalse(needsTargetTranslation("請查 https://example.org/the/issue", Language.ZH_TW))
        for (target in listOf(Language.ENGLISH, Language.INDIAN_ENGLISH)) {
            assertTrue(needsTargetTranslation("Ready. 請稍候。", target))
            assertFalse(needsTargetTranslation("Can we call the API?", target))
        }
        assertFalse(needsTargetTranslation("API を呼び出します。", Language.JAPANESE))
        assertFalse(needsTargetTranslation("API를 호출합니다.", Language.KOREAN))
    }

    @Test fun sourceOnlyForeignSpeechIsTranslatedOnceAndNativeOnlyRemainsAvailable() = runTest {
        for (mode in listOf("missing", "native", "output only", "filtered")) {
            val events = mutableListOf<TranscriptDelta>()
            var calls = 0
            completeVoiceCaptions(Language.ZH_TW, { assertEquals("Call again.", it); calls++; "再打一次。" }, { events += it }) { accept, filtered ->
                if (mode != "output only") {
                    accept(TranscriptDelta(TranscriptChannel.INPUT, "Call ", 0, 500))
                    accept(TranscriptDelta(TranscriptChannel.INPUT, "again.", 500, 900))
                }
                if (mode == "native" || mode == "output only") accept(TranscriptDelta(TranscriptChannel.OUTPUT, "再打一次。", 6000, 6600))
                if (mode == "filtered") filtered()
            }
            assertEquals(if (mode == "missing") 1 else 0, calls)
            assertEquals(if (mode == "filtered") emptyList() else listOf("再打一次。"), output(events))
        }
    }

    private fun output(events: List<TranscriptDelta>) = events.visibleVoiceEvents().map { it.value }
        .filter { it.channel == TranscriptChannel.OUTPUT }.map { it.text }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun englishThenChineseThenEnglishIsCompleteLiveAndAfterLateNativeOutput() = runTest {
        val events = mutableListOf<TranscriptDelta>()
        val requested = mutableListOf<String>()
        completeVoiceCaptions(Language.ZH_TW, { text ->
            requested += text
            when (text.trim()) { "First." -> "首先。"; "Last." -> "最後。"; else -> error(text) }
        }, { events += it }) { accept, _ ->
            accept(TranscriptDelta(TranscriptChannel.INPUT, "First.", 0, 600))
            accept(TranscriptDelta(TranscriptChannel.OUTPUT, "首先。", 8000, 8600))
            runCurrent()
            assertEquals(listOf("首先。"), output(events))
            accept(TranscriptDelta(TranscriptChannel.INPUT, "请检查", 700, 1200))
            accept(TranscriptDelta(TranscriptChannel.INPUT, "网络", 1200, 1500))
            advanceTimeBy(1501); runCurrent()
            assertEquals(listOf("首先。", "請檢查網路"), output(events))
            accept(TranscriptDelta(TranscriptChannel.OUTPUT, "請檢查網路。", 9000, 9500))
            accept(TranscriptDelta(TranscriptChannel.INPUT, "Last.", 2000, 2600))
        }
        assertEquals(listOf("首先。", "請檢查網路", "最後。"), output(events))
        assertEquals(listOf("First.", "Last."), requested)
        assertEquals(listOf(0L, 700L, 2000L), events.filter { it.voiceOrigin == VoiceOrigin.FROM_SOURCE }.map { it.startMs })
        assertEquals(4, events.visibleVoiceEvents().count { it.value.channel == TranscriptChannel.INPUT })
    }

    @Test fun targetEnglishReceivesBothLanguagesAndHandlesOneMixedSourceEvent() = runTest {
        val events = mutableListOf<TranscriptDelta>()
        completeVoiceCaptions(Language.ENGLISH, { text ->
            when (text.trim()) { "現在開始。" -> "Starting now."; "Next point." -> "Next point."; else -> error(text) }
        }, { events += it }) { accept, _ ->
            accept(TranscriptDelta(TranscriptChannel.INPUT, "現在開始。 Next point.", 0, 1000))
            accept(TranscriptDelta(TranscriptChannel.OUTPUT, "Starting now.", 0, 1200))
        }
        assertEquals("Starting now. Next point.", output(events).joinToString(""))
    }

    @Test fun repeatedChineseIsKeptForEachUtteranceAndMixedSentenceUsesTranslator() = runTest {
        val events = mutableListOf<TranscriptDelta>()
        completeVoiceCaptions(Language.ZH_TW, { text -> assertEquals("請 check status。", text); "請檢查狀態。" }, { events += it }) { accept, _ ->
            accept(TranscriptDelta(TranscriptChannel.INPUT, "收到。收到。請 check status。", 0, 2000))
            accept(TranscriptDelta(TranscriptChannel.OUTPUT, "收到。", 3000, 3800))
        }
        assertEquals(listOf("收到。", "收到。", "請檢查狀態。"), output(events))
    }

    @Test fun switchWithoutPunctuationAndDecimalSplitAcrossPacketsKeepTheirContents() = runTest {
        val events = mutableListOf<TranscriptDelta>()
        completeVoiceCaptions(Language.ZH_TW, { text ->
            assertEquals("Amount is 3.14", text)
            "金額為 3.14。"
        }, { events += it }) { accept, _ ->
            accept(TranscriptDelta(TranscriptChannel.INPUT, "Amount is 3.", 0, 300))
            accept(TranscriptDelta(TranscriptChannel.INPUT, "14", 300, 600))
            accept(TranscriptDelta(TranscriptChannel.INPUT, "接着说中文", 600, 1000))
        }
        assertEquals("金額為 3.14。接著說中文", output(events).joinToString(""))
    }

    @Test fun voiceRepairCanDeferAnUntranslatedSpanAndDeliverLaterSpeech() = runTest {
        val input = Channel<TranscriptDelta>(2)
        input.send(TranscriptDelta(TranscriptChannel.OUTPUT, "Can we proceed?", 0, 800))
        input.send(TranscriptDelta(TranscriptChannel.OUTPUT, "下一句。", 800, 1600))
        input.close()
        val emitted = mutableListOf<TranscriptDelta>()
        deliverVoiceOutput(input, Language.ZH_TW, {
            throw TranslationFailure("wrong language", FoundryDiagnostic("文字翻譯", "TARGET_LANGUAGE_MISMATCH"))
        }, { emitted += it })
        assertEquals(listOf("下一句。"), emitted.filter { it.channel == TranscriptChannel.OUTPUT }.map { it.text })
        assertEquals("Can we proceed?", emitted.single { it.translationPending }.text)
        assertFalse(needsTargetTranslation("請交給 IT 部門。", Language.ZH_TW))
    }

    @Test fun aSplitWordIsRepairedOnceBeforeDisplayAndUnpunctuatedTailDrains() = runTest {
        val input = Channel<TranscriptDelta>(Channel.UNLIMITED)
        listOf("先確認。", "Ca", "n we call the API", "?", "最後看 API").forEachIndexed { i, text ->
            input.send(TranscriptDelta(TranscriptChannel.OUTPUT, text, i * 100L, (i + 1) * 100L))
        }
        input.close()
        val repaired = mutableListOf<String>()
        val emitted = mutableListOf<TranscriptDelta>()
        deliverVoiceOutput(input, Language.ZH_TW, { repaired += it; "可以呼叫 API 嗎？" }, { emitted += it })
        assertEquals(listOf("Can we call the API?"), repaired)
        assertEquals("先確認。可以呼叫 API 嗎？最後看 API", emitted.joinToString("") { it.text })
        assertEquals(100L, emitted[1].startMs)
        assertEquals(400L, emitted[1].endMs)
    }
}
