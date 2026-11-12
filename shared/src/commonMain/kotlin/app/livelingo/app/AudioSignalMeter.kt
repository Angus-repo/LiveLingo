package app.livelingo.app

import app.livelingo.core.AudioSource
import kotlin.math.abs
import kotlin.math.sqrt

data class AudioInputStatus(
    val level: Float = 0f,
    val signalPresent: Boolean = false,
    val warning: String? = null,
    val silencedBySystem: Boolean = false,
)

/** System policy is evidence of silencing; silence alone is not evidence of another app. */
internal fun AudioInputStatus.withCapturePolicy(source: AudioSource, silenced: Boolean, communicationActive: Boolean): AudioInputStatus {
    val policyWarning = when {
        silenced -> "Android 已將此收音工作階段靜音，可能是通話或其他 App 優先使用音源。請停止翻譯；若在同一支手機開 Teams，可改由電腦或另一台裝置開會並外放，再用本機麥克風收音。"
        warning != null && communicationActive && source.isPlayback ->
            "已約 15 秒沒有可擷取的聲音，且裝置處於通話模式。Android 的裝置播放擷取不涵蓋通話音訊；同機 Teams 改選麥克風也可能被靜音。可改由另一台裝置開會並外放，再用本機麥克風收音。"
        warning != null && communicationActive ->
            "已約 15 秒沒有收到聲音，且裝置處於通話模式；麥克風可能由 Teams 等通話 App 優先使用。可先離開通話測試麥克風，或改由另一台裝置開會並外放。"
        else -> warning
    }
    return copy(level = if (silenced) 0f else level, signalPresent = signalPresent && !silenced,
        warning = policyWarning, silencedBySystem = silenced)
}

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
            warning = when {
                quietSamples < 360_000 -> null
                source.isPlayback -> "已約 15 秒未偵測到可擷取的聲音。請確認正在播放且媒體音量不為零；來源 App 可能禁止擷取，通話音訊也不在擷取範圍。若要用麥克風收音，建議由另一台裝置開會並外放。"
                else -> "已約 15 秒未偵測到聲音。請確認麥克風權限、系統麥克風開關與輸入裝置；同機通話也可能使本 App 收到靜音。"
            },
        )
        windowSamples = 0
        windowPeak = 0
        return status
    }
}
