package app.livelingo.app

import app.livelingo.core.AudioSource
import kotlin.math.abs
import kotlin.math.sqrt

data class AudioInputStatus(val level: Float = 0f, val signalPresent: Boolean = false, val warning: String? = null)

/** PCM16 little-endian, mono, 24 kHz. Measures signal, not speech recognition or capture permission. */
internal class AudioSignalMeter(private val source: AudioSource) {
    private var quietSamples = 0L
    private var windowSamples = 0
    private var windowPeak = 0

    fun accept(pcm: ByteArray, size: Int = pcm.size): AudioInputStatus? {
        require(size in 0..pcm.size && size % 2 == 0)
        for (i in 0 until size step 2) {
            val sample = ((pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
            val magnitude = abs(sample)
            windowPeak = maxOf(windowPeak, magnitude)
            quietSamples = if (magnitude > 16) 0L else (quietSamples + 1).coerceAtMost(360_000)
            windowSamples++
        }
        if (windowSamples < 6_000) return null // At most four status callbacks per second.
        val status = AudioInputStatus(
            level = sqrt(windowPeak / 32768f),
            signalPresent = windowPeak > 16,
            warning = if (quietSamples >= 360_000 && source.isPlayback)
                "已約 15 秒未偵測到可擷取的聲音。請確認正在播放且媒體音量不為零；來源 App 也可能禁止錄製。可停止後改用麥克風收取外放聲音。"
            else null,
        )
        windowSamples = 0
        windowPeak = 0
        return status
    }
}
