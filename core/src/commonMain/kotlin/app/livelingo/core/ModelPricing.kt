package app.livelingo.core

/** Public list-price references, never an estimate of this account's negotiated bill. */
data class ModelPrice(val label: String, val note: String, val sourceUrl: String)

const val PRICE_CHECKED_DATE = "2026-10-31"
const val OPENAI_PRICING_URL = "https://developers.openai.com/api/docs/pricing"
const val FOUNDRY_PRICING_URL = "https://azure.microsoft.com/en-us/pricing/details/azure-openai/"
private const val FOUNDRY_REALTIME_PRICING = "https://techcommunity.microsoft.com/blog/azure-ai-foundry-blog/a-new-chapter-for-realtime-ai-reasoning-translation-and-real-time-transcription/4517124"
private const val FOUNDRY_TRANSCRIPTION_PRICING = "https://techcommunity.microsoft.com/blog/azure-ai-foundry-blog/introducing-gpt-transcribe-and-gpt-live-transcribe-in-microsoft-foundry/4541740"

fun SpeechModel.price(provider: ModelProvider): ModelPrice = when (this) {
    SpeechModel.GPT_LIVE -> if (provider == ModelProvider.OPENAI)
        ModelPrice("US$0.05／會話分鐘", "會話時間包含靜音；後端模型、工具及 AI 分段另計。", OPENAI_PRICING_URL)
    else ModelPrice("Azure 費率待確認\nOpenAI 參考 US$0.05／會話分鐘", "此數字是 OpenAI 公開費率，不是 Azure 報價。Foundry 依實際部署計價；會話包含靜音，AI 分段另計。", OPENAI_PRICING_URL)
    SpeechModel.REALTIME_TRANSLATE -> if (provider == ModelProvider.OPENAI)
        ModelPrice("US$0.034／音訊分鐘", "若另開原文轉錄，GPT Realtime Whisper 為 US$0.017／音訊分鐘；AI 分段另計。", OPENAI_PRICING_URL)
    else ModelPrice("US$2.04／音訊小時", "Global Standard 公開參考價，約 US$0.034／分鐘。原文轉錄及 AI 分段另計。", FOUNDRY_REALTIME_PRICING)
    SpeechModel.LIVE_TRANSCRIBE -> if (provider == ModelProvider.OPENAI)
        ModelPrice("US$0.017／音訊分鐘", "只含語音轉錄，另加所選文字模型的翻譯與 AI 分段費用。", OPENAI_PRICING_URL)
    else ModelPrice("US$1.02／音訊小時", "Global Standard 公開參考價，約 US$0.017／分鐘。文字翻譯與 AI 分段另計。", FOUNDRY_TRANSCRIPTION_PRICING)
    SpeechModel.MAI_TRANSCRIBE -> ModelPrice("US$0.54／音訊小時（優惠價）", "Microsoft 公開優惠參考價，約 US$0.009／分鐘；文字翻譯與 AI 分段另計。優惠期間與實際費率以服務公告為準。", "https://microsoft.ai/models/mai-transcribe-2/")
}

fun TextModel.price(provider: ModelProvider): ModelPrice {
    if (this == TextModel.CUSTOM) return ModelPrice("依自訂模型／部署報價", "無法從自訂名稱判斷費率，請核對對應服務的計價。", if (provider == ModelProvider.OPENAI) OPENAI_PRICING_URL else FOUNDRY_PRICING_URL)
    val amount = when (this) {
        TextModel.GPT6_LUNA -> "輸入 US$0.10／輸出 US$0.50"
        TextModel.GPT54_MINI -> "輸入 US$0.75／輸出 US$4.50"
        TextModel.GPT61_SOL -> "輸入 US$2.00／輸出 US$10.00"
        TextModel.GPT41_MINI -> "輸入 US$0.40／輸出 US$1.60"
        TextModel.CUSTOM -> error("Handled above")
    } + "（每百萬 token）"
    if (provider == ModelProvider.OPENAI) return ModelPrice(amount,
        "OpenAI Standard 短上下文公開參考價；未套用快取、批次折扣或區域加價，推理 token 也會計費。", when (this) {
            TextModel.GPT54_MINI -> "https://developers.openai.com/api/docs/models/gpt-5.4-mini"
            TextModel.GPT41_MINI -> "https://developers.openai.com/api/docs/models/gpt-4.1-mini"
            else -> OPENAI_PRICING_URL
        })
    if (this == TextModel.GPT41_MINI) return ModelPrice("Azure 費率待確認\nOpenAI 參考：$amount",
        "此數字是 OpenAI 公開費率，不是 Azure 報價。請核對實際 Foundry 部署價格。", "https://developers.openai.com/api/docs/models/gpt-4.1-mini")
    return ModelPrice(amount, "Foundry Global Standard 短上下文公開參考價；區域、部署類型、快取與合約可能影響實際費用。", when (this) {
        TextModel.GPT6_LUNA -> "https://azure.microsoft.com/en-us/blog/gpt-6-astra-sol-and-luna-for-production-agents-in-microsoft-foundry/"
        TextModel.GPT54_MINI -> "https://techcommunity.microsoft.com/blog/azure-ai-foundry-blog/introducing-openai%E2%80%99s-gpt-5-4-mini-and-gpt-5-4-nano-for-low-latency-ai/4500569"
        TextModel.GPT61_SOL -> "https://devblogs.microsoft.com/foundry/whats-new-in-microsoft-foundry-september-2026/"
        else -> FOUNDRY_PRICING_URL
    })
}
