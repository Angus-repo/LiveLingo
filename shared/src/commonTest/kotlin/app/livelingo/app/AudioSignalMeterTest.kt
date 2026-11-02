package app.livelingo.app

import app.livelingo.core.AudioSource
import kotlin.test.*

class AudioSignalMeterTest {
    @Test fun quietPlaybackGetsWarningAfterFifteenSecondsAndRecoversOnSignal() {
        val meter = AudioSignalMeter(AudioSource.DEVICE_PLAYBACK)
        repeat(59) { assertNull(meter.accept(ByteArray(12_000))!!.warning) }
        val silent = meter.accept(ByteArray(12_000))!!
        assertFalse(silent.signalPresent)
        assertTrue("來源 App" in silent.warning.orEmpty())
        val tone = ByteArray(12_000) { if (it % 2 == 0) 0 else 64 }
        val signal = meter.accept(tone)!!
        assertTrue(signal.signalPresent)
        assertTrue(signal.level in 0.7f..0.72f)
        assertNull(signal.warning)
        repeat(59) { assertNull(meter.accept(ByteArray(12_000))!!.warning) }
        assertNotNull(meter.accept(ByteArray(12_000))!!.warning)
    }

    @Test fun littleEndianNegativeFullScaleAndNoiseFloorAreHandled() {
        val meter = AudioSignalMeter(AudioSource.DEVICE_PLAYBACK)
        val negative = ByteArray(12_000) { if (it % 2 == 0) 0 else 0x80.toByte() }
        assertEquals(1f, meter.accept(negative)!!.level)
        val tiny = ByteArray(12_000) { if (it % 2 == 0) 16 else 0 }
        assertFalse(meter.accept(tiny)!!.signalPresent)
    }

    @Test fun microphoneSilenceDoesNotClaimThatAnAppBlockedPlayback() {
        val meter = AudioSignalMeter(AudioSource.MICROPHONE)
        repeat(59) { assertNull(meter.accept(ByteArray(12_000))!!.warning) }
        val status = meter.accept(ByteArray(12_000))!!
        assertTrue("麥克風" in status.warning.orEmpty())
        assertFalse("已將" in status.warning.orEmpty())
        assertFalse(status.silencedBySystem)
    }

    @Test fun confirmedSystemSilencingOverridesSignalAndRecoversWithoutStaleWarning() {
        for (source in listOf(AudioSource.MICROPHONE, AudioSource.DEVICE_PLAYBACK)) {
            val measured = AudioInputStatus(level = 0.8f, signalPresent = true)
            val blocked = measured.withCapturePolicy(source, silenced = true, communicationActive = false)
            assertTrue(blocked.silencedBySystem)
            assertFalse(blocked.signalPresent)
            assertEquals(0f, blocked.level)
            assertTrue("Android 已將" in blocked.warning.orEmpty())
            val restored = measured.withCapturePolicy(source, silenced = false, communicationActive = true)
            assertTrue(restored.signalPresent)
            assertFalse(restored.silencedBySystem)
            assertNull(restored.warning)
        }
    }

    @Test fun communicationModeExplainsQuietAudioWithoutClaimingTeamsWasDetected() {
        for (source in listOf(AudioSource.MICROPHONE, AudioSource.DEVICE_PLAYBACK)) {
            val meter = AudioSignalMeter(source)
            repeat(59) { meter.accept(ByteArray(12_000)) }
            val quiet = meter.accept(ByteArray(12_000))!!
            val status = quiet.withCapturePolicy(source, silenced = false, communicationActive = true)
            assertTrue("通話模式" in status.warning.orEmpty())
            assertTrue("另一台裝置" in status.warning.orEmpty())
            assertFalse(status.silencedBySystem)
            assertFalse("已將" in status.warning.orEmpty())
            assertEquals(quiet.warning, quiet.withCapturePolicy(source, false, false).warning)
        }
    }

    @Test fun partialBuffersAreThrottledAndUnusedBytesAreIgnored() {
        val meter = AudioSignalMeter(AudioSource.DEVICE_PLAYBACK)
        val buffer = ByteArray(12_000) { 0x7f }.also { it.fill(0, 0, 6_000) }
        assertNull(meter.accept(buffer, 6_000))
        assertEquals(0f, meter.accept(buffer, 6_000)!!.level)
        assertFailsWith<IllegalArgumentException> { meter.accept(byteArrayOf(1)) }
    }
}
