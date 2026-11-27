package app.livelingo.app

import app.livelingo.core.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
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

/** Hold an unfinished Latin word when the target uses another script. Validation still runs on the final result. */
internal fun targetPreview(text: String, target: Language): String {
    val draft = when (target) {
        Language.ZH_TW, Language.JAPANESE, Language.KOREAN -> text.trimEnd { it.latin() || it in " '-’" }
        else -> text
    }
    return if (needsTargetTranslation(draft, target)) "" else draft
}

internal val TranslationFailure.targetLanguageMismatch: Boolean
    get() = diagnostic?.code == "TARGET_LANGUAGE_MISMATCH"

/** Defer only a rejected language result. Transport, storage and provider failures keep their own policies. */
internal suspend fun TranscriptDelta.translateOrDefer(translate: suspend (String) -> String): TranscriptDelta =
    try {
        copy(channel = TranscriptChannel.OUTPUT, text = translate(text))
    } catch (failure: TranslationFailure) {
        if (!failure.targetLanguageMismatch && failure.diagnostic?.code != "TEXT_MODEL_REQUIRED") throw failure
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
        if (pending != null && pending?.voiceOrigin != delta.voiceOrigin) flush()
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
        val resumedTarget = old != null && delta.text.lastOrNull { it.isLetter() }?.speechScript()?.usedBy(target) == true &&
            !needsTargetTranslation(text, target)
        if (native || resumedTarget || text.lastOrNull() in listOf('。','！','？','!','?','\n') ||
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
    sourceLanguage: Language = Language.AUTO,
    stream: StreamingText = { text, _ -> repair(text) },
    collect: suspend (suspend (TranscriptDelta) -> Unit, suspend () -> Unit) -> Unit,
) = coroutineScope {
    val native = Channel<TranscriptDelta>(32)
    val sources = Channel<TranscriptDelta>(48)
    val backlog = mutableListOf<TranscriptDelta>()
    val scripts = mutableSetOf<SpeechScript>()
    var sourceLed = false
    var sawNative = false
    var blocked = false
    val nativePrefix = StringBuilder()
    launch {
        deliverVoiceOutput(native, target, repair) { event ->
            if (event.voiceOrigin == VoiceOrigin.STANDARD || !sourceLed) {
                if (event.channel == TranscriptChannel.OUTPUT) nativePrefix.append(event.text)
                emit(event)
            }
        }
    }
    val sourceJob = launch { deliverSourceSentences(sources, target, sourceLanguage, stream, emit, live = true) }
    var prefixJob: Job? = null
    suspend fun switchToSource() {
        sourceLed = true
        if (backlog.isEmpty()) return
        val earlier = backlog.sortedBy { it.startMs }
        backlog.clear()
        if (nativePrefix.isNotBlank()) {
            // Native and source clocks can differ by seconds. Anchor this temporary prefix
            // before the new speech until all earlier source sentences have been reconciled.
            emit(TranscriptDelta(TranscriptChannel.OUTPUT, nativePrefix.toString(), earlier.first().startMs,
                earlier.maxOf { it.endMs }, voiceOrigin = VoiceOrigin.PREFIX_PREVIEW))
        }
        prefixJob = launch {
            val history = Channel<TranscriptDelta>(earlier.size)
            earlier.forEach { history.send(it) }
            history.close()
            val replacements = mutableListOf<TranscriptDelta>()
            deliverSourceSentences(history, target, sourceLanguage, { text, _ -> repair(text) }, { replacements += it }, live = false)
            replacements.forEachIndexed { index, event ->
                emit(event.copy(voiceOrigin = if (index == replacements.lastIndex) VoiceOrigin.PREFIX_END else VoiceOrigin.PREFIX_PART))
            }
        }
    }
    collect({ delta ->
        if (delta.channel == TranscriptChannel.INPUT) {
            emit(delta)
            if (!blocked) {
                if (!sourceLed) {
                    delta.text.mapNotNullTo(scripts) { it.speechScript() }
                    if (scripts.size > 1 || scripts.any { it.usedBy(target) }) switchToSource()
                }
                if (sourceLed) sources.send(delta) else backlog += delta
            }
        } else if (!sourceLed || blocked) {
            if (delta.text.isNotBlank()) sawNative = true
            native.send(delta.copy(voiceOrigin = if (blocked) VoiceOrigin.STANDARD else VoiceOrigin.NATIVE_PREVIEW))
        }
    }, {
        blocked = true
        backlog.clear()
        sourceJob.cancelAndJoin()
        prefixJob?.cancelAndJoin()
    })
    if (!blocked && !sourceLed && !sawNative) for (delta in backlog.sortedBy { it.startMs }) sources.send(delta)
    sources.close()
    native.close()
}

// Do not send clearly Chinese speech back through a text model. Mixed or ambiguous text is
// translated normally. Preserve punctuation, numbers and the user's Taiwan script preference.
internal fun localChinese(text: String, target: Language, source: Language): String? =
    if (target == Language.ZH_TW && source != Language.JAPANESE && source != Language.KOREAN && text.any { it.han() } && text.none { it.isLetter() && !it.han() })
        TaiwanText.convert(text) else null

private val sentenceEnd = Regex("""[。！？!?\n]+|\.(?=\s|[\u3400-\u9fff]|$)""")

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

internal typealias StreamingText = suspend (String, suspend (String) -> Unit) -> String

private fun withWordSpacing(previous: String, current: String): String =
    if (previous in listOf(".", "?", "!") && current.firstOrNull()?.latin() == true) " $current"
    else separatedTranscript(previous, current)

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun deliverSourceSentences(input: ReceiveChannel<TranscriptDelta>, target: Language, sourceLanguage: Language,
    translate: StreamingText, emit: suspend (TranscriptDelta) -> Unit, live: Boolean) = coroutineScope {
    val ready = Channel<TranscriptDelta>(48)
    var slot = 0
    var pending: TranscriptDelta? = null
    suspend fun queue() {
        val span = pending ?: return
        pending = null
        if (span.text.isBlank()) return
        ready.send(span.copy(voiceSlot = if (live) slot++ else -1))
    }
    // The reader can still show newer local captions while an earlier HTTP request is in flight.
    // Only this worker commits results, in source order, after language checks and final drain.
    launch {
        var previous = ""
        for (span in ready) {
            val result = span.translateOrDefer { text ->
                if (text.none(Char::isLetter)) text else localChinese(text, target, sourceLanguage) ?: translate(text) { draft ->
                    if (live) emit(span.copy(channel = TranscriptChannel.OUTPUT, text = withWordSpacing(previous, draft), previewOnly = true))
                }
            }
            val final = if (result.translationPending) result else result.copy(text = withWordSpacing(previous, result.text))
            if (!final.translationPending) previous = final.text.takeLast(1)
            emit(final.copy(voiceOrigin = VoiceOrigin.FROM_SOURCE))
        }
    }
    while (true) {
        val next = if (pending == null) input.receiveCatching() else select<kotlinx.coroutines.channels.ChannelResult<TranscriptDelta>?> {
            input.onReceiveCatching { it }
            onTimeout(450) { null }
        }
        if (next == null) { queue(); continue }
        val delta = next.getOrNull() ?: break
        if (pending?.let { delta.startMs - it.endMs >= 1_500 } == true) queue()
        val tail = pending?.text
        if (tail != null && delta.text.isNotBlank()) {
            val changesToChinese = target == Language.ZH_TW && tail.any { it.latin() } && !tail.any { it.han() } &&
                localChinese(delta.text, target, sourceLanguage) != null
            val decimalContinues = tail.endsWith('.') && tail.getOrNull(tail.lastIndex - 1)?.isDigit() == true && delta.text.first().isDigit()
            if (changesToChinese || (tail.endsWith('.') && !decimalContinues)) queue()
        }
        for (piece in sentencePieces(delta.text)) {
            val old = pending
            val span = if (old == null) delta.copy(text = piece) else old.copy(
                text = old.text + piece, endMs = maxOf(old.endMs, delta.endMs))
            pending = span
            val local = localChinese(span.text, target, sourceLanguage)
            if (live && local != null) emit(span.copy(channel = TranscriptChannel.OUTPUT, text = local, voiceSlot = slot, previewOnly = true))
            val possibleDecimal = piece.endsWith('.') && piece.getOrNull(piece.lastIndex - 1)?.isDigit() == true
            if ((!possibleDecimal && sentenceEnd.containsMatchIn(piece)) || span.text.length >= 1800) queue()
            else if (live && local == null && span.endMs - span.startMs >= 1_800) {
                // Continuous speech may have no sentence breaks. Send completed words and
                // leave the trailing token intact for the next source packet.
                val cut = span.text.indexOfLast { it.isWhitespace() || it in "，、,；;" } + 1
                if (cut > 0 && span.text.take(cut).isNotBlank()) {
                    pending = span.copy(text = span.text.take(cut))
                    queue()
                    pending = span.text.drop(cut).takeIf { it.isNotEmpty() }?.let { delta.copy(text = it) }
                }
            }
        }
    }
    queue()
    ready.close()
}
