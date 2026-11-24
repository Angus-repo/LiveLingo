package app.livelingo.app

import app.livelingo.core.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
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
