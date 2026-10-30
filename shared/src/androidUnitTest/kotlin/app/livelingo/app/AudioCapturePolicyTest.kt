package app.livelingo.app

import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import app.livelingo.core.AudioSource
import kotlin.test.*

class AudioCapturePolicyTest {
    @Test fun playbackCapturesPermittedMediaUsagesAndNeverVoiceCalls() {
        val usages = playbackCaptureUsages(AudioSource.DEVICE_PLAYBACK)
        assertEquals(setOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN), usages.toSet())
        assertFalse(AudioAttributes.USAGE_VOICE_COMMUNICATION in usages)
    }

    @Test fun removedTeamsSourceAndMicrophoneCannotBecomeUnfilteredPlayback() {
        assertFailsWith<IllegalArgumentException> { playbackCaptureUsages(AudioSource.TEAMS) }
        assertFailsWith<IllegalArgumentException> { playbackCaptureUsages(AudioSource.MICROPHONE) }
    }

    @Test fun foregroundTypeIsSelectedIndividuallyAndRespectsAndroidVersions() {
        listOf(AudioSource.DEVICE_PLAYBACK).forEach {
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION, audioForegroundServiceType(it, 36))
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION, audioForegroundServiceType(it, 29))
            assertFailsWith<IllegalArgumentException> { audioForegroundServiceType(it, 28) }
        }
        assertEquals(0, audioForegroundServiceType(AudioSource.MICROPHONE, 29))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, audioForegroundServiceType(AudioSource.MICROPHONE, 36))
    }
}
