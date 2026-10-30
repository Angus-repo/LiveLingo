package app.livelingo.app

import app.livelingo.core.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject

@Serializable
data class AppPreferences(
    val foundryEndpoint: String = "",
    val deployment: String = "gpt-live-1",
    val source: Language = Language.AUTO,
    val target: Language = Language.ZH_TW,
    val knowledgeId: String? = null,
    val pace: TranslationPace = TranslationPace.BALANCED,
    val quality: TranslationQuality = TranslationQuality.FAITHFUL,
)

@Serializable
private data class StoredFoundryKey(val endpoint: String, val apiKey: String)

/** Private app files contain transcripts and reference text, never credentials. */
class AppRepository(private val storage: LocalStorage) {
    fun preferences(): AppPreferences = decode("preferences", AppPreferences())
    fun savePreferences(value: AppPreferences) = storage.write("preferences", WireJson.encodeToString(value))
    fun knowledge(): List<BackgroundKnowledge> = decode("knowledge", defaults)
    fun saveKnowledge(value: List<BackgroundKnowledge>) = storage.write("knowledge", WireJson.encodeToString(value))
    /** Credentials are atomically bound to their canonical destination in encrypted storage. */
    fun apiKey(canonicalEndpoint: String): String {
        val payload = storage.readSecret("foundry_api_key") ?: return ""
        val saved = WireJson.decodeFromString<StoredFoundryKey>(payload)
        return saved.apiKey.takeIf { saved.endpoint == canonicalEndpoint }.orEmpty()
    }
    fun saveApiKey(canonicalEndpoint: String, value: String) = storage.writeSecret(
        "foundry_api_key", if (value.isEmpty()) "" else WireJson.encodeToString(StoredFoundryKey(canonicalEndpoint, value)))
    fun clearApiKey() = storage.writeSecret("foundry_api_key", "")

    /** Run only after all existing user data has loaded successfully. */
    fun migrateLegacyConnection(preferences: AppPreferences) {
        val raw = storage.read("preferences")
        if (raw != null && "relayUrl" in WireJson.parseToJsonElement(raw).jsonObject) savePreferences(preferences)
        // Never decrypt or reinterpret an obsolete relay credential as a provider key.
        storage.writeSecret("relay_token", "")
    }

    fun history(): List<TranscriptRecord> = ids().mapNotNull { id ->
        storage.read("transcript_$id")?.let { WireJson.decodeFromString<TranscriptRecord>(it) }
    }.sortedByDescending { it.createdAtMs }

    fun saveTranscript(record: TranscriptRecord) {
        // Write content before publishing the ID: interruption never points to a half-written record.
        storage.write("transcript_${record.id}", WireJson.encodeToString(record))
        val current = ids()
        if (record.id !in current) storage.write("transcript_index", WireJson.encodeToString(current + record.id))
    }

    fun deleteTranscript(id: String) {
        storage.write("transcript_index", WireJson.encodeToString(ids().filterNot { it == id }))
        storage.delete("transcript_$id")
    }

    private fun ids(): List<String> = decode("transcript_index", emptyList())
    private inline fun <reified T> decode(key: String, fallback: T): T =
        storage.read(key)?.let { WireJson.decodeFromString<T>(it) } ?: fallback

    companion object {
        val defaults = listOf(
            BackgroundKnowledge("banking", "銀行業務會議", "場景：銀行內部業務會議。KYC = 認識你的客戶；AML = 防制洗錢；credit limit = 信用額度；chargeback = 爭議款；settlement = 清算。保留金額、幣別、帳號末碼與產品名稱，不補寫未說出的資料。"),
            BackgroundKnowledge("engineering", "軟體開發討論", "場景：軟體開發與維護會議。Spring Boot、Kotlin、Java、API、SQL、GitHub、CI/CD 保留英文。deployment = 部署；rollback = 回復前一版本；pull request = 合併請求；latency = 延遲；incident = 異常事件。保留程式識別字與版本號。"),
        )
    }
}
