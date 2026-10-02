package com.argsment.anywhere.ui.components

import com.argsment.anywhere.ui.components.ForceTurnHold.Release
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The power button's tap-vs-hold decisions (spec §8, iOS `didLongPress`). */
class ForceTurnHoldTest {

    @Test
    fun holdIsTwoSeconds() {
        assertEquals(2_000L, ForceTurnHold.HOLD_MILLIS)
    }

    @Test
    fun shortTapToggles() {
        val hold = ForceTurnHold()
        hold.press()
        assertTrue(hold.isHolding)
        assertEquals(Release.TAP, hold.release(cancelled = false, enabled = true))
        assertFalse(hold.isHolding)
    }

    @Test
    fun completedHoldFiresOnceAndSwallowsTheReleaseTap() {
        val hold = ForceTurnHold()
        hold.press()
        assertTrue(hold.holdElapsed(enabled = true))
        assertFalse("the ring stops filling once the hold fired", hold.isHolding)
        assertFalse("a second timer tick does not fire again", hold.holdElapsed(enabled = true))
        assertEquals(Release.NONE, hold.release(cancelled = false, enabled = true))
    }

    @Test
    fun nextGestureAfterAHoldIsATapAgain() {
        val hold = ForceTurnHold()
        hold.press(); hold.holdElapsed(true); hold.release(false, true)
        hold.press()
        assertEquals(Release.TAP, hold.release(cancelled = false, enabled = true))
    }

    @Test
    fun disabledButtonDoesNothingButStillSwallowsTheTap() {
        val hold = ForceTurnHold()
        hold.press()
        assertFalse(hold.holdElapsed(enabled = false))
        assertEquals(Release.NONE, hold.release(cancelled = false, enabled = true))

        hold.press()
        assertEquals(Release.NONE, hold.release(cancelled = false, enabled = false))
    }

    @Test
    fun cancelledGestureIsNotATap() {
        val hold = ForceTurnHold()
        hold.press()
        assertEquals(Release.NONE, hold.release(cancelled = true, enabled = true))
    }

    @Test
    fun releaseOrTimerWithoutPressDoNothing() {
        val hold = ForceTurnHold()
        assertFalse(hold.holdElapsed(enabled = true))
        assertEquals(Release.NONE, hold.release(cancelled = false, enabled = true))
    }
}
