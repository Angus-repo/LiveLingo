package app.livelingo.app

import app.livelingo.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

internal data class CapturedCaption(val text: String, val atMs: Long)

internal interface TeamsCaptionInput {
    fun startCaptions(onHint: (String) -> Unit): ReceiveChannel<CapturedCaption>
    fun selectCaptionRegion()
}

internal fun TranscriptRecord.pendingCaptions(): List<TranscriptFragment> {
    if (settings.audioSource != AudioSource.TEAMS_CAPTIONS) return emptyList()
    val translated = fragments.filter { it.channel == TranscriptChannel.OUTPUT }.mapTo(mutableSetOf()) { it.id }
    return fragments.filter { it.channel == TranscriptChannel.INPUT && "translation-${it.id}" !in translated }
}

/** Reconcile a growing or scrolling OCR window before committing a stable portion. */
internal class CaptionWindow {
    private var previous = ""
    private var pending = ""
    private val spaces = Regex("\\s+")

    fun update(raw: String): String? {
        val next = raw.trim().replace(spaces, " ")
        if (next == previous) return null
        val old = previous
        previous = next
        if (next.isEmpty()) return flush()
        if (old.isEmpty() || next.startsWith(old)) {
            pending += next.drop(old.length)
            return null
        }
        val overlap = (minOf(old.length, next.length) downTo 1).firstOrNull { length ->
            val start = old.length - length
            (start == 0 || !old[start - 1].isAsciiWord() || !old[start].isAsciiWord()) &&
                (length == next.length || !next[length - 1].isAsciiWord() || !next[length].isAsciiWord()) &&
                old.regionMatches(start, next, 0, length)
        } ?: 0
        if (overlap > 0) {
            pending += next.drop(overlap)
            return null
        }
        val common = old.commonPrefixWith(next).length
        if (common >= 3 && old.length - common <= pending.length) {
            pending = pending.dropLast(old.length - common) + next.drop(common)
            return null
        }
        return flush().also { pending = next }
    }

    private fun Char.isAsciiWord() = code < 128 && isLetterOrDigit()
    fun flush(): String? = pending.trim().takeIf { it.isNotEmpty() }.also { pending = "" }
}

/** Intake persists independently while the network worker is awaiting a response or backing off. */
internal suspend fun processTeamsCaptions(
    incoming: ReceiveChannel<CapturedCaption>?,
    stop: CompletableDeferred<Unit>,
    saveInput: suspend (CapturedCaption) -> Unit,
    pending: () -> List<TranscriptFragment>,
    translate: suspend (TranscriptFragment) -> String,
    saveOutput: suspend (TranscriptFragment, String) -> Unit,
    onStatus: (String?) -> Unit,
) = coroutineScope {
    val changed = Channel<Unit>(Channel.CONFLATED)
    if (incoming != null) launch {
        try {
            for (caption in incoming) { saveInput(caption); changed.trySend(Unit) }
        } finally { stop.complete(Unit); changed.close() }
    }
    var backoff = 2_000L
    while (!stop.isCompleted) {
        val queue = pending()
        val first = queue.firstOrNull()
        if (first == null) {
            onStatus(null)
            if (incoming == null || changed.receiveCatching().isClosed) break
            continue
        }
        onStatus("等待補譯 ${queue.size} 段")
        val translated = try { translate(first) } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            val http = (failure as? TranslationFailure)?.diagnostic?.httpStatus
            val permanent = if (http == null) failure is TranslationFailure
                else http !in 500..599 && http !in setOf(408, 425, 429)
            if (permanent) {
                onStatus(failure.message ?: "文字服務拒絕請求。原文已保存，可停止後從逐字稿補譯。")
                if (incoming != null) stop.await()
                else throw failure
                break
            }
            onStatus("文字翻譯暫時無法連線，原文持續保存。${backoff / 1000} 秒後自動補譯。")
            withTimeoutOrNull(backoff) { stop.await() }
            backoff = (backoff * 2).coerceAtMost(30_000L)
            continue
        }
        saveOutput(first, translated)
        backoff = 2_000L
    }
    // coroutineScope waits for all input queued before Stop to be durably saved.
    Unit
}
