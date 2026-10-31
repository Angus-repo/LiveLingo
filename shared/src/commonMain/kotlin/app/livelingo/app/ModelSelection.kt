package app.livelingo.app

import app.livelingo.core.*
import kotlinx.serialization.Serializable

@Serializable
enum class ModelRole { SPEECH, TEXT, SOURCE_TRANSCRIPT }

/** A menu selection always carries its protocol/parameters and API name together. */
@Serializable
data class ModelSelection(val kind: String, val modelId: String) {
    val key: String get() = "$kind:$modelId"
}

/** No credentials; Foundry choices belong to one resource, not every Azure account. */
@Serializable
data class SavedModelChoice(
    val provider: ModelProvider,
    val endpoint: String,
    val role: ModelRole,
    val selection: ModelSelection,
)

internal fun FoundryConnection.selection(role: ModelRole): ModelSelection = when (role) {
    ModelRole.SPEECH -> ModelSelection(speechModel.name, deployment)
    ModelRole.TEXT -> ModelSelection(textModelKind.name, textDeployment)
    ModelRole.SOURCE_TRANSCRIPT -> ModelSelection("", sourceTranscriptionModel)
}

private fun FoundryConnection.modelScope(): String? = runCatching { credentialEndpoint() }.getOrNull()

private fun FoundryConnection.savedChoices(): List<SavedModelChoice> {
    val scope = modelScope() ?: return emptyList()
    return ModelRole.entries.mapNotNull { role ->
        val selected = selection(role)
        if (selected.modelId.isBlank() || selected.validationError(role, provider) != null) null
        else SavedModelChoice(provider, scope, role, selected)
    }
}

internal fun AppPreferences.rememberModelChoices(next: FoundryConnection): List<SavedModelChoice> =
    (savedModelChoices + connection().savedChoices() + next.savedChoices()).distinct()

internal fun modelSelections(preferences: AppPreferences, connection: FoundryConnection, role: ModelRole): List<ModelSelection> {
    val defaults = when (role) {
        ModelRole.SPEECH -> SpeechModel.entries.filter { it.supportedBy(connection.provider) }.map { ModelSelection(it.name, it.modelId) }
        ModelRole.TEXT -> TextModel.entries.filter { it != TextModel.CUSTOM }.map { ModelSelection(it.name, it.modelId) }
        ModelRole.SOURCE_TRANSCRIPT -> listOf(ModelSelection("", ""), ModelSelection("", "gpt-realtime-whisper"))
    }
    val scope = connection.modelScope()
    val saved = (preferences.savedModelChoices + preferences.connection().savedChoices())
        .filter { it.provider == connection.provider && it.endpoint == scope && it.role == role }
        .map { it.selection }
    return (defaults + saved + connection.selection(role))
        .filter { it.validationError(role, connection.provider) == null }.distinct()
}

internal fun ModelSelection.validationError(role: ModelRole, provider: ModelProvider): String? {
    if (role == ModelRole.SOURCE_TRANSCRIPT && modelId.isEmpty()) return null
    if (!Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}").matches(modelId)) return "名稱須為 1–128 個英數字、點、底線或連字號。"
    when (role) {
        ModelRole.SPEECH -> {
            val selected = SpeechModel.entries.find { it.name == kind && it.supportedBy(provider) }
                ?: return "請選擇此服務支援的語音模型。"
            val named = SpeechModel.entries.find { modelId == it.modelId || modelId.startsWith(it.modelId + "-") }
            if (provider == ModelProvider.OPENAI && named != null && named != selected) return "模型 ID 與所選語音模型類型不符。"
        }
        ModelRole.TEXT -> {
            val selected = TextModel.entries.find { it.name == kind } ?: return "請選擇文字模型類型。"
            val named = TextModel.infer(modelId)
            if (provider == ModelProvider.OPENAI && named != TextModel.CUSTOM && selected != TextModel.CUSTOM && named != selected)
                return "模型 ID 與所選文字模型類型不符。"
        }
        ModelRole.SOURCE_TRANSCRIPT -> Unit
    }
    return null
}

internal fun ModelSelection.menuLabel(role: ModelRole, provider: ModelProvider): String {
    if (modelId.isBlank() && role != ModelRole.SOURCE_TRANSCRIPT) return "請選擇模型"
    val (name, defaultId) = when (role) {
        ModelRole.SPEECH -> SpeechModel.entries.find { it.name == kind }?.let { it.label to it.modelId } ?: ("自訂語音模型" to "")
        ModelRole.TEXT -> TextModel.entries.find { it.name == kind }?.let { it.label to it.modelId } ?: ("自訂文字模型" to "")
        ModelRole.SOURCE_TRANSCRIPT -> if (modelId.isEmpty()) return "不顯示原文字幕" else "GPT Realtime Whisper（原文字幕）" to "gpt-realtime-whisper"
    }
    return if (provider == ModelProvider.FOUNDRY) "$name · 部署：$modelId"
    else if (modelId == defaultId) name else "$name · $modelId"
}
