package app.livelingo.app

import app.livelingo.core.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

private val literal = Regex("""(?:https?://|www\.)[^\s，。！？]+|[A-Za-z0-9_.+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}|\b[A-Za-z]+[0-9]+[A-Za-z0-9_-]*\b""")
private val latinPhrase = Regex("""[A-Za-z]+(?:['’][A-Za-z]+)?(?:[ \t-]+[A-Za-z]+(?:['’][A-Za-z]+)?)*""")
private val wordsToTranslate = setOf("a", "an", "the", "this", "that", "these", "those", "i", "we", "you", "it",
    "is", "are", "was", "have", "has", "will", "would", "can", "could", "and", "or", "but", "to",
    "of", "for", "in", "at", "do", "does", "yes", "yeah", "no", "ok", "okay", "hmm", "hello",
    "hi", "thanks", "right", "please", "ready", "stop", "wait", "help", "sure", "sorry", "goodbye", "local", "remote")

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
        // Acronyms such as IT in a Chinese sentence can also spell English function words.
        if (words.size == 1 && (han > 0 || kana || hangul) && words[0] in setOf("IT", "IS", "OR", "AT", "DO"))
            return@any false
        words.any { it.lowercase() in wordsToTranslate } ||
            words.size >= 2 && words.any { word -> word.all { it in 'a'..'z' } }
    }
}

internal val TranslationFailure.targetLanguageMismatch: Boolean
    get() = diagnostic?.code == "TARGET_LANGUAGE_MISMATCH"

/** Defer only a rejected language result. Transport, storage and provider failures keep their own policies. */
internal suspend fun TranscriptDelta.translateOrDefer(translate: suspend (String) -> String): TranscriptDelta =
    try {
        copy(channel = TranscriptChannel.OUTPUT, text = translate(text))
    } catch (failure: TranslationFailure) {
        if (!failure.targetLanguageMismatch) throw failure
        copy(channel = TranscriptChannel.INPUT, translationPending = true)
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
        emit(if (needsTargetTranslation(span.text, target)) span.translateOrDefer(repair) else span)
        pending = null
    }
    while (true) {
        val next: kotlinx.coroutines.channels.ChannelResult<TranscriptDelta>? = if (pending == null) input.receiveCatching() else select {
            input.onReceiveCatching { it }
            onTimeout(1_500) { null }
        }
        if (next == null) { flush(); continue }
        val delta = next.getOrNull() ?: break
        if (pending?.voiceOrigin != delta.voiceOrigin) flush()
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

private enum class SpeechScript { LATIN, HAN, KANA, HANGUL }

private fun Char.speechScript(): SpeechScript? = when {
    han() -> SpeechScript.HAN
    kana() -> SpeechScript.KANA
    hangul() -> SpeechScript.HANGUL
    latin() -> SpeechScript.LATIN
    else -> null
}

private fun SpeechScript.usedBy(target: Language) = when (target) {
    Language.ENGLISH, Language.INDIAN_ENGLISH -> this == SpeechScript.LATIN
    Language.ZH_TW -> this == SpeechScript.HAN
    Language.JAPANESE -> this == SpeechScript.HAN || this == SpeechScript.KANA
    Language.KOREAN -> this == SpeechScript.HANGUL
    Language.AUTO -> false
}

/**
 * Native input and output clocks are independent. When source speech switches scripts or is
 * already in the target script, build one ordered output from the source for this audio window.
 * Keep native output live until the first source-based result is ready. A window with no source
 * events (including a disabled transcription model) continues to use the native output.
 */
internal suspend fun completeVoiceCaptions(
    target: Language,
    repair: suspend (String) -> String,
    emit: suspend (TranscriptDelta) -> Unit,
    collect: suspend (suspend (TranscriptDelta) -> Unit, suspend () -> Unit) -> Unit,
) = coroutineScope {
    val native = Channel<TranscriptDelta>(32)
    val sources = Channel<TranscriptDelta>(48)
    val backlog = mutableListOf<TranscriptDelta>()
    val scripts = mutableSetOf<SpeechScript>()
    var sourceLed = false
    var sawNative = false
    var blocked = false
    launch { deliverVoiceOutput(native, target, repair, emit) }
    val sourceJob = launch { deliverSourceSentences(sources, target, repair, emit) }
    suspend fun useSource() {
        sourceLed = true
        for (delta in backlog.sortedBy { it.startMs }) sources.send(delta)
        backlog.clear()
    }
    collect({ delta ->
        if (delta.channel == TranscriptChannel.INPUT) {
            emit(delta)
            if (!blocked) {
                if (sourceLed) sources.send(delta)
                else {
                    backlog += delta
                    delta.text.mapNotNullTo(scripts) { it.speechScript() }
                    if (scripts.size > 1 || scripts.any { it.usedBy(target) }) useSource()
                }
            }
        } else if (!sourceLed || blocked) {
            if (delta.text.isNotBlank()) sawNative = true
            native.send(delta.copy(voiceOrigin = if (blocked) VoiceOrigin.STANDARD else VoiceOrigin.NATIVE_PREVIEW))
        }
    }, {
        blocked = true
        backlog.clear()
        sourceJob.cancelAndJoin()
    })
    if (!blocked && !sourceLed && !sawNative) useSource()
    sources.close()
    native.close()
}

// Do not send clearly Chinese speech back through a text model. Mixed or ambiguous text is
// translated normally. Preserve punctuation, numbers and the user's Taiwan script preference.
internal fun localChinese(text: String, target: Language): String? =
    if (target == Language.ZH_TW && text.any { it.han() } && text.none { it.isLetter() && !it.han() })
        TaiwanText.convert(text) else null

private val sentenceEnd = Regex("""[。！？!?\n]+|\.(?=\s|$)""")

/** Split a single provider delta containing multiple sentences without inventing word timestamps. */
internal fun sentencePieces(text: String): List<String> = buildList {
    var from = 0
    for (mark in sentenceEnd.findAll(text)) {
        val end = mark.range.last + 1
        add(text.substring(from, end))
        from = end
    }
    if (from < text.length) add(text.substring(from))
}

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun deliverSourceSentences(input: ReceiveChannel<TranscriptDelta>, target: Language,
    translate: suspend (String) -> String, emit: suspend (TranscriptDelta) -> Unit) {
    var pending: TranscriptDelta? = null
    var previousOutput = ""
    suspend fun flush() {
        val source = pending ?: return
        pending = null
        if (source.text.isBlank()) return
        val result = source.translateOrDefer { localChinese(it, target) ?: translate(it) }
        val separated = if (result.translationPending) result else
            result.copy(text = separatedTranscript(previousOutput, result.text)).also { previousOutput = it.text.takeLast(1) }
        emit(separated.copy(voiceOrigin = VoiceOrigin.FROM_SOURCE))
    }
    while (true) {
        val next = if (pending == null) input.receiveCatching() else select<kotlinx.coroutines.channels.ChannelResult<TranscriptDelta>?> {
            input.onReceiveCatching { it }
            onTimeout(1_500) { null }
        }
        if (next == null) { flush(); continue }
        val delta = next.getOrNull() ?: break
        if (pending?.let { delta.startMs - it.endMs >= 1_500 } == true) flush()
        for (piece in sentencePieces(delta.text)) {
            val old = pending
            pending = if (old == null) delta.copy(text = piece) else old.copy(
                text = old.text + piece, endMs = maxOf(old.endMs, delta.endMs))
            if (sentenceEnd.find(piece)?.range?.last == piece.lastIndex || pending!!.text.length >= 1800) flush()
        }
    }
    flush()
}
