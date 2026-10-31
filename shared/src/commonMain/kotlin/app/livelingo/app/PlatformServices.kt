package app.livelingo.app

import io.ktor.client.HttpClient
import app.livelingo.core.AudioSource

interface LocalStorage {
    fun read(key: String): String?
    fun write(key: String, value: String)
    fun delete(key: String)
    fun readSecret(key: String): String?
    fun writeSecret(key: String, value: String)
}

interface Microphone {
    fun reportsAudioLevel(): Boolean = false
    fun availableSources(): List<AudioSource> = listOf(AudioSource.MICROPHONE)
    fun unavailableReason(source: AudioSource): String? =
        if (source in availableSources()) null else "此平台目前僅支援麥克風，請重新選擇音源。"
    /** Compatibility bridge for platforms that only implement microphone capture. */
    suspend fun prepareSource(source: AudioSource, onStatus: (AudioInputStatus) -> Unit, onError: (String) -> Unit) {
        unavailableReason(source)?.let { throw AudioCaptureFailure(it) }
        prepare()
    }
    /** Called from a visible user action before network setup; platforms without FGS may no-op. */
    suspend fun prepare() {}
    suspend fun start(onAudio: (ByteArray) -> Unit, onError: (String) -> Unit)
    fun stop()
    /** Called after the final transcript has been flushed and persisted. */
    fun releaseSession() {}
}

/** Only deliberately authored platform messages are safe to show without exposing raw exceptions. */
class AudioCaptureFailure(message: String) : Exception(message)

interface TextExporter {
    fun export(fileName: String, mimeType: String, text: String)
}

interface PlatformServices {
    val storage: LocalStorage
    val microphone: Microphone
    val exporter: TextExporter
    fun currentTimeMillis(): Long
    fun uniqueId(): String
    fun createHttpClient(): HttpClient
}
