package app.livelingo.app

import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import app.livelingo.core.AudioSource
import kotlin.test.*

class AudioCapturePolicyTest {
    @Test fun teamsIsAlwaysUidFilteredAndCannotCaptureVoiceCommunication() {
        val plan = playbackCapturePlan(AudioSource.TEAMS, 10234)
        assertEquals(10234, plan.matchingUid)
        assertEquals(setOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN), plan.usages.toSet())
        assertFalse(AudioAttributes.USAGE_VOICE_COMMUNICATION in plan.usages)
        assertFailsWith<AudioCaptureFailure> { playbackCapturePlan(AudioSource.TEAMS, null) }
        assertFailsWith<AudioCaptureFailure> { playbackCapturePlan(AudioSource.TEAMS, -1) }
    }

    @Test fun devicePlaybackDoesNotAccidentallyUseTeamsUidOrMicrophone() {
        assertNull(playbackCapturePlan(AudioSource.DEVICE_PLAYBACK, 10234).matchingUid)
        assertFailsWith<IllegalArgumentException> { playbackCapturePlan(AudioSource.MICROPHONE, null) }
    }

    @Test fun foregroundTypeIsSelectedIndividuallyAndRespectsAndroidVersions() {
        listOf(AudioSource.TEAMS, AudioSource.DEVICE_PLAYBACK).forEach {
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION, audioForegroundServiceType(it, 36))
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION, audioForegroundServiceType(it, 29))
            assertFailsWith<IllegalArgumentException> { audioForegroundServiceType(it, 28) }
        }
        assertEquals(0, audioForegroundServiceType(AudioSource.MICROPHONE, 29))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, audioForegroundServiceType(AudioSource.MICROPHONE, 36))
    }
}
