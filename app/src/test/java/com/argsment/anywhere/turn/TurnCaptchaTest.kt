package com.argsment.anywhere.turn

import com.argsment.anywhere.turn.TurnCaptchaEdgeTrigger.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnCaptchaTest {

    private fun settings(configure: TurnSettings.() -> Unit = {}): TurnSettings =
        TurnSettings(FakeSharedPreferences()).apply(configure)

    // MARK: - Gating (effective mode)

    @Test
    fun freshInstallWatchesInAutoMode() {
        assertTrue(settings().captchaWatchApplies())
    }

    @Test
    fun storedOffDoesNotWatch() {
        assertFalse(settings { turnMode = TurnMode.OFF }.captchaWatchApplies())
    }

    @Test
    fun forcedSessionWatchesEvenWhenStoredModeIsOff() {
        // The iOS app monitor checks the stored mode and misses this (spec §9.3).
        val s = settings { turnMode = TurnMode.OFF; turnForced = true }
        assertTrue(s.captchaWatchApplies())
    }

    @Test
    fun forcedSessionWatchesEvenWithMasterSwitchOff() {
        val s = settings { featureEnabled = false; turnMode = TurnMode.OFF; turnForced = true }
        assertTrue(s.captchaWatchApplies())
    }

    @Test
    fun masterSwitchOffWithoutForceDoesNotWatch() {
        val s = settings { featureEnabled = false; turnMode = TurnMode.ON }
        assertFalse(s.captchaWatchApplies())
    }

    // MARK: - Notifier edge trigger

    @Test
    fun postsOnlyOnRisingEdgeInBackground() {
        val t = TurnCaptchaEdgeTrigger()
        assertEquals(Action.NONE, t.step(applies = true, reachable = false, appInForeground = false))
        assertEquals(Action.POST, t.step(applies = true, reachable = true, appInForeground = false))
        // Still up: no repost.
        assertEquals(Action.NONE, t.step(applies = true, reachable = true, appInForeground = false))
        // Solved: cleared once.
        assertEquals(Action.CLEAR, t.step(applies = true, reachable = false, appInForeground = false))
        assertEquals(Action.NONE, t.step(applies = true, reachable = false, appInForeground = false))
    }

    @Test
    fun foregroundAppGetsNoNotificationAndNoLateOne() {
        val t = TurnCaptchaEdgeTrigger()
        assertEquals(Action.NONE, t.step(applies = true, reachable = true, appInForeground = true))
        // Going to the background later does not post for the same captcha (edge-triggered).
        assertEquals(Action.NONE, t.step(applies = true, reachable = true, appInForeground = false))
        // But the falling edge still clears.
        assertEquals(Action.CLEAR, t.step(applies = true, reachable = false, appInForeground = false))
    }

    @Test
    fun newCaptchaAfterSolvedOneNotifiesAgain() {
        val t = TurnCaptchaEdgeTrigger()
        t.step(true, true, false)
        t.step(true, false, false)
        assertEquals(Action.POST, t.step(applies = true, reachable = true, appInForeground = false))
    }

    @Test
    fun turnGoingInactiveClearsAHangingNotification() {
        val t = TurnCaptchaEdgeTrigger()
        assertEquals(Action.POST, t.step(true, true, false))
        assertEquals(Action.CLEAR, t.step(applies = false, reachable = false, appInForeground = false))
        assertFalse(t.wasReachable)
        assertEquals(Action.NONE, t.step(applies = false, reachable = false, appInForeground = false))
        // Back active with the server still up: that is a new rising edge.
        assertEquals(Action.POST, t.step(true, true, false))
    }

    // MARK: - In-app sheet state

    @Test
    fun sheetOpensWhenCaptchaAppears() {
        val s = TurnCaptchaSheetState().onTick(applies = true, reachable = true)
        assertTrue(s.showCaptcha)
        assertTrue(s.captchaWaiting)
    }

    @Test
    fun dismissedSheetStaysClosedUntilServerCycles() {
        var s = TurnCaptchaSheetState().onTick(true, true).userDismissed()
        assertFalse(s.showCaptcha)
        s = s.onTick(true, true)
        assertFalse(s.showCaptcha)
        assertTrue("a dismissed captcha is still waiting", s.captchaWaiting)
        // Server gone, then a fresh captcha: opens again.
        s = s.onTick(true, false)
        assertEquals(TurnCaptchaSheetState(), s)
        s = s.onTick(true, true)
        assertTrue(s.showCaptcha)
    }

    @Test
    fun requestShowReopensADismissedSheet() {
        val s = TurnCaptchaSheetState().onTick(true, true).userDismissed().requestShow()
        assertTrue(s.showCaptcha)
        assertTrue(s.onTick(true, true).showCaptcha)
    }

    @Test
    fun requestShowWithoutServerClosesOnNextTick() {
        val s = TurnCaptchaSheetState().requestShow()
        assertTrue(s.showCaptcha)
        assertFalse(s.onTick(true, false).showCaptcha)
    }

    @Test
    fun inactiveTurnHidesTheSheetButKeepsTheRest() {
        val s = TurnCaptchaSheetState().onTick(true, true).onTick(applies = false, reachable = false)
        assertFalse(s.showCaptcha)
        assertTrue(s.captchaWaiting)
    }
}
