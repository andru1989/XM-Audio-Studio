package io.github.andru1989.xmaudiostudio.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The timing FollowPlaybackService uses: one claim per playback start, after 2 s, never too often. */
class PlaybackClaimerTest {
    private var now = 0L
    private var follow = true
    private val clock = AutoSwitchClock()
    private var claims = 0
    private val claimer = PlaybackClaimer({ now }, { follow }, clock) { claims++ }

    @Test
    fun claimsOnceTwoSecondsAfterThePhoneStartsPlaying() {
        now = 10_000
        assertTrue("waits for the settle time", claimer.onPlayingSince(10_000))
        now = 11_000
        assertTrue(claimer.tick())
        assertEquals(0, claims)
        now = 12_000
        assertFalse("claimed: no more ticking", claimer.tick())
        assertEquals(1, claims)
        assertEquals(12_000L, clock.lastMs)
        now = 30_000
        assertFalse(claimer.tick())
        assertEquals("one claim per start", 1, claims)
    }

    @Test
    fun aNotificationSoundOrAStopBeforeTheSettleTimeNeverClaims() {
        now = 0
        claimer.onPlayingSince(0)
        now = 1_500
        assertFalse(claimer.onPlayingSince(null))
        now = 5_000
        assertFalse(claimer.tick())
        assertEquals(0, claims)
    }

    @Test
    fun aNewStartRightAfterAnAutomaticActionWaitsItsTurn() {
        clock.lastMs = 0
        now = 5_000
        claimer.onPlayingSince(3_000)
        assertEquals("within 15 s of the last automatic action", 0, claims)
        now = 15_000
        claimer.tick()
        assertEquals(1, claims)
    }

    @Test
    fun followingOffNeverClaims() {
        follow = false
        now = 10_000
        assertFalse(claimer.onPlayingSince(0))
        assertEquals(0, claims)
    }
}
