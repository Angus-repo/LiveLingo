package app.livelingo.app

import io.ktor.client.HttpClient

interface LocalStorage {
    fun read(key: String): String?
    fun write(key: String, value: String)
    fun delete(key: String)
    fun readSecret(key: String): String?
    fun writeSecret(key: String, value: String)
}

interface Microphone {
    suspend fun start(onAudio: (ByteArray) -> Unit, onError: (String) -> Unit)
    fun stop()
}

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
