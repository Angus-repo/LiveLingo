package app.livelingo.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
enum class SpeechModel(val modelId: String, val label: String, val description: String) {
    GPT_LIVE("gpt-live-1", "GPT-Live 1", "直接語音翻譯，可使用背景知識與翻譯策略。"),
    REALTIME_TRANSLATE("gpt-realtime-translate", "GPT Realtime Translate（即時口譯）", "連續口譯；來源語言自動偵測，不支援背景知識或自訂翻譯策略。繁中由 App 校正字形與用語。"),
    LIVE_TRANSCRIBE("gpt-live-transcribe", "GPT Live Transcribe ＋文字翻譯", "先轉錄，再使用下方文字模型翻譯；支援背景知識，另計文字模型用量。"),
    MAI_TRANSCRIBE("MAI-Transcribe-2-Streaming", "MAI Transcribe 2 Streaming ＋文字翻譯（預覽）", "僅 Microsoft Foundry；需 services.ai.azure.com 資源端點及對應部署。先轉錄，再由文字模型翻譯。"),
    ;
    val transcribes: Boolean get() = this == LIVE_TRANSCRIBE || this == MAI_TRANSCRIBE
    fun supportedBy(provider: ModelProvider): Boolean = provider == ModelProvider.FOUNDRY || this != MAI_TRANSCRIBE
}

@Serializable
enum class TextModel(val modelId: String, val label: String, val effort: String?) {
    GPT6_LUNA("gpt-6-luna", "GPT-6 Luna（建議）", "none"),
    GPT54_MINI("gpt-5.4-mini", "GPT-5.4 Mini", "none"),
    GPT61_SOL("gpt-6.1-sol", "GPT-6.1 Sol", "low"),
    GPT41_MINI("gpt-4.1-mini", "GPT-4.1 Mini（原有模型）", null),
    CUSTOM("", "自訂相容文字模型", null),
    ;
    companion object {
        fun infer(model: String): TextModel = entries.firstOrNull { it != CUSTOM && (model == it.modelId || model.startsWith(it.modelId + "-")) } ?: CUSTOM
    }
}

fun JsonObjectBuilder.textModelParameters(model: TextModel, outputLimit: Int) {
    // GPT-6 Luna / 5.4 Mini support none; Sol requires at least low. No temperature is sent.
    model.effort?.let { put("reasoning_effort", it) }
    put("max_completion_tokens", if (model == TextModel.GPT61_SOL) maxOf(8192, outputLimit) else outputLimit)
}

fun Language.languageCode(): String? = when (this) {
    Language.AUTO -> null
    Language.ENGLISH, Language.INDIAN_ENGLISH -> "en"
    Language.ZH_TW -> "zh"
    Language.JAPANESE -> "ja"
    Language.KOREAN -> "ko"
}
