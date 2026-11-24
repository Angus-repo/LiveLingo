package app.livelingo.app

import app.livelingo.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TeamsCaptionsTest {
    @Test fun combinesGrowingCorrectedAndScrollingWindows() {
        val window = CaptionWindow()
        assertNull(window.update("Today we me"))
        assertNull(window.update("Today we meet"))
        assertNull(window.update("Today we met"))
        assertNull(window.update("we met at noon"))
        assertEquals("Today we met at noon", window.flush())
        assertNull(window.update("we met at noon"))
        assertNull(window.flush())
        assertNull(window.update("at noon today"))
        assertEquals("today", window.update(""))
        assertNull(window.update("today"))
        assertEquals("today", window.flush())
    }

    @Test fun clearsWindowOnBlankAndKeepsDifferentSentences() {
        val window = CaptionWindow()
        window.update("會議開始")
        assertNull(window.update("開始討論"))
        assertEquals("會議開始討論", window.update("請表決"))
        assertEquals("請表決", window.update(""))
        assertNull(window.flush())
    }

    private fun input(id: String) = TranscriptFragment(id, TranscriptChannel.INPUT, 0, 10, id)
    private fun output(line: TranscriptFragment) = line.copy(id = "translation-${line.id}", channel = TranscriptChannel.OUTPUT)

    @Test fun retryPassSkipsRepeatedMismatchAndTranslatesOtherPendingLinesOnce() = runTest {
        val first = input("first")
        val second = input("second")
        val waiting = mutableListOf(first, second)
        val sent = mutableListOf<String>()
        processTeamsCaptions(null, CompletableDeferred(), {}, { waiting.toList() }, { line ->
            sent += line.id
            if (line == first) throw TranslationFailure("wrong language", FoundryDiagnostic("文字翻譯", "TARGET_LANGUAGE_MISMATCH"))
            "完成。"
        }, { line, _ -> waiting.remove(line) }, {})
        assertEquals(listOf("first", "second"), sent)
        assertEquals(listOf(first), waiting)
    }

    @Test fun intakeContinuesOfflineThenDrainsInOrderWithoutDuplicateOutput() = runTest {
        val incoming = Channel<CapturedCaption>(Channel.UNLIMITED)
        val stop = CompletableDeferred<Unit>()
        val saved = mutableListOf<TranscriptFragment>()
        val translated = mutableListOf<String>()
        var online = false
        val job = launch {
            processTeamsCaptions(incoming, stop, { saved += input(it.text) },
                { saved.filter { it.id !in translated } },
                { if (!online) throw IllegalStateException("offline"); it.text },
                { line, _ -> translated += line.id }, {})
        }
        incoming.send(CapturedCaption("one", 1))
        runCurrent()
        incoming.send(CapturedCaption("two", 2))
        runCurrent()
        assertEquals(listOf("one", "two"), saved.map { it.id })
        assertTrue(translated.isEmpty())
        online = true
        advanceTimeBy(2000)
        runCurrent()
        assertEquals(listOf("one", "two"), translated)
        incoming.close()
        job.join()
    }

    @Test fun stopOfflineKeepsFinalCaptionAndRelaunchOnlyTranslatesMissingItems() = runTest {
        val incoming = Channel<CapturedCaption>(Channel.UNLIMITED)
        val stop = CompletableDeferred<Unit>()
        var record = TranscriptRecord("record", "", 0, SessionSettings(audioSource = AudioSource.TEAMS_CAPTIONS),
            fragments = listOf(input("old"), output(input("old"))))
        val job = launch {
            processTeamsCaptions(incoming, stop, { record = record.copy(fragments = record.fragments + input(it.text)) },
                { record.pendingCaptions() }, { error("offline") }, { _, _ -> fail() }, {})
        }
        incoming.send(CapturedCaption("first", 1))
        runCurrent()
        incoming.send(CapturedCaption("final", 2))
        incoming.close()
        stop.complete(Unit)
        job.join()
        val disk = WireJson.encodeToString(TranscriptRecord.serializer(), record)
        record = WireJson.decodeFromString(TranscriptRecord.serializer(), disk)
        val requests = mutableListOf<String>()
        processTeamsCaptions(null, CompletableDeferred(), {}, { record.pendingCaptions() },
            { requests += it.id; it.text }, { line, _ -> record = record.copy(fragments = record.fragments + output(line)) }, {})
        assertEquals(listOf("first", "final"), requests)
        assertTrue(record.pendingCaptions().isEmpty())
    }
}
