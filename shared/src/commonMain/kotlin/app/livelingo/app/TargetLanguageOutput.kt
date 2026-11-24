package app.livelingo.app

import app.livelingo.core.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

private val literal = Regex("""(?:https?://|www\.)[^\s，。！？]+|[A-Za-z0-9_.+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}|\b[A-Za-z]+[0-9]+[A-Za-z0-9_-]*\b""")
private val latinPhrase = Regex("""[A-Za-z]+(?:['’][A-Za-z]+)?(?:[ \t-]+[A-Za-z]+(?:['’][A-Za-z]+)?)*""")
private val wordsToTranslate = setOf("a", "an", "the", "this", "that", "these", "those", "i", "we", "you", "it",
    "is", "are", "was", "have", "has", "will", "would", "can", "could", "and", "or", "but", "to",
    "of", "for", "in", "at", "do", "does", "yes", "yeah", "no", "ok", "okay", "hmm", "hello",
    "hi", "thanks", "right", "please", "ready", "stop", "wait", "help", "sure", "sorry", "goodbye")

private fun Char.han() = code in 0x3400..0x9fff || code in 0xf900..0xfaff
private fun Char.kana() = code in 0x3040..0x30ff || code in 0xff66..0xff9d
private fun Char.hangul() = code in 0xac00..0xd7af || code in 0x1100..0x11ff || code in 0x3130..0x318f
private fun Char.latin() = this in 'a'..'z' || this in 'A'..'Z'

/** Look for untranslated clauses, leaving room for names, acronyms, URLs and code identifiers. */
internal fun needsTargetTranslation(text: String, target: Language): Boolean {
    val prose = text.replace(literal, " ")
    val han = prose.count { it.han() }
    val kana = prose.any { it.kana() }
    val hangul = prose.any { it.hangul() }
    when (target) {
        Language.AUTO -> return false
        Language.ENGLISH, Language.INDIAN_ENGLISH -> return han > 0 || kana || hangul
        Language.ZH_TW -> if (kana || hangul) return true
        Language.JAPANESE -> if (hangul || (han >= 8 && !kana)) return true
        Language.KOREAN -> if (kana || (han >= 4 && !hangul)) return true
    }
    return latinPhrase.findAll(prose).any { match ->
        val words = match.value.split(' ', '\t', '-').filter(String::isNotEmpty)
        words.any { it.lowercase() in wordsToTranslate } ||
            words.size >= 2 && words.any { word -> word.all { it in 'a'..'z' } }
    }
}

/** Native speech models send word pieces, so evaluate foreign runs together before publishing them. */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun deliverVoiceOutput(
    input: ReceiveChannel<TranscriptDelta>,
    target: Language,
    repair: suspend (String) -> String,
    emit: suspend (TranscriptDelta) -> Unit,
) {
    var pending: TranscriptDelta? = null
    suspend fun flush() {
        val span = pending ?: return
        val text = if (needsTargetTranslation(span.text, target)) repair(span.text) else span.text
        emit(span.copy(text = text))
        pending = null
    }
    while (true) {
        val next: kotlinx.coroutines.channels.ChannelResult<TranscriptDelta>? = if (pending == null) input.receiveCatching() else select {
            input.onReceiveCatching { it }
            onTimeout(1_500) { null }
        }
        if (next == null) { flush(); continue }
        val delta = next.getOrNull() ?: break
        val old = pending
        val span = old?.copy(text = old.text + delta.text, startMs = minOf(old.startMs, delta.startMs),
            endMs = maxOf(old.endMs, delta.endMs)) ?: delta
        pending = span
        val text = span.text
        val native = when (target) {
            Language.ENGLISH, Language.INDIAN_ENGLISH -> !text.any { it.han() || it.kana() || it.hangul() }
            Language.ZH_TW -> !text.any { it.latin() || it.kana() || it.hangul() }
            Language.JAPANESE -> text.any { it.kana() } && !text.any { it.latin() || it.hangul() }
            Language.KOREAN -> text.any { it.hangul() } && !text.any { it.latin() || it.kana() }
            Language.AUTO -> true
        }
        if (native || text.lastOrNull() in listOf('。','！','？','!','?','\n') ||
            text.endsWith(". ") || text.length >= 512) flush()
    }
    flush()
}
