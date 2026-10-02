package com.argsment.anywhere.turn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnSettingsTest {

    private val prefs = FakeSharedPreferences()
    private var clock = 1_000_000L
    private val settings = TurnSettings(prefs) { clock }

    @Test
    fun freshInstallDefaults() {
        assertTrue(settings.featureEnabled)
        assertEquals(TurnMode.AUTO, settings.turnMode)
        assertFalse("auto must not be persisted on read", prefs.contains(TurnSettings.Keys.TURN_MODE))
        assertFalse(settings.turnForced)
        assertEquals("", settings.vkLink)
        assertEquals(10, settings.peers)
        assertFalse(settings.captchaManual)
        assertFalse(settings.proModeEnabled)
        assertFalse(settings.appIsForeground)
    }

    @Test
    fun legacyTrueMigratesToOnAndIsWrittenBack() {
        prefs.values[TurnSettings.Keys.TURN_ENABLED] = true
        assertEquals(TurnMode.ON, settings.turnMode)
        assertEquals("on", prefs.values[TurnSettings.Keys.TURN_MODE])
    }

    @Test
    fun legacyFalseMigratesToOff() {
        prefs.values[TurnSettings.Keys.TURN_ENABLED] = false
        assertEquals(TurnMode.OFF, settings.turnMode)
        assertEquals("off", prefs.values[TurnSettings.Keys.TURN_MODE])
    }

    @Test
    fun storedModeWinsOverLegacy() {
        prefs.values[TurnSettings.Keys.TURN_ENABLED] = true
        prefs.values[TurnSettings.Keys.TURN_MODE] = "off"
        assertEquals(TurnMode.OFF, settings.turnMode)
    }

    @Test
    fun garbageModeFallsBackToMigrationOrAuto() {
        prefs.values[TurnSettings.Keys.TURN_MODE] = "sideways"
        assertEquals(TurnMode.AUTO, settings.turnMode)
    }

    @Test
    fun peersAreClamped() {
        for ((stored, expected) in listOf(0 to 10, -3 to 10, 1 to 1, 4 to 4, 50 to 50, 51 to 50, 500 to 50)) {
            prefs.values[TurnSettings.Keys.TURN_PEERS] = stored
            assertEquals("stored $stored", expected, settings.peers)
        }
        settings.peers = 99
        assertEquals(50, prefs.values[TurnSettings.Keys.TURN_PEERS])
    }

    @Test
    fun vkLinkIsTrimmedOnWrite() {
        settings.vkLink = "  https://vk.com/call/join/abc \n"
        assertEquals("https://vk.com/call/join/abc", settings.vkLink)
    }

    /** Port of iOS TurnForceTests.forcedSessionIsOnWhateverTheModeSays. */
    @Test
    fun forcedSessionIsOnWhateverTheModeSays() {
        settings.featureEnabled = false
        for (mode in TurnMode.entries) {
            settings.turnMode = mode
            settings.turnForced = true
            assertEquals(TurnMode.ON, settings.effectiveTurnMode)
            assertTrue(settings.turnActiveForSession)
            assertEquals("the stored setting must not change", mode, settings.turnMode)

            settings.turnForced = false
            assertEquals(mode, settings.effectiveTurnMode)
            assertFalse(settings.turnActiveForSession)
        }
    }

    @Test
    fun preflightVerdictExpires() {
        assertNull(settings.recentPreflightVerdictRaw(30_000))
        settings.setPreflightVerdictRaw("open")
        assertEquals("open", settings.recentPreflightVerdictRaw(30_000))
        clock += 30_001
        assertNull(settings.recentPreflightVerdictRaw(30_000))
        clock -= 60_000 // recorded in the future
        assertNull(settings.recentPreflightVerdictRaw(30_000))
    }

    /** A foreground flag left behind by a dead process must not mute captcha notifications. */
    @Test
    fun processStartClearsStaleForegroundFlag() {
        settings.appIsForeground = true
        TurnSettings(prefs) { clock }.noteProcessStarted()
        assertFalse(settings.appIsForeground)
    }

    /** Mid-session mode changes restart/stop the VM's phase watch off these. */
    @Test
    fun phaseWatchFollowsModeMasterSwitchAndForce() {
        settings.turnMode = TurnMode.OFF
        assertFalse(settings.wantsTurnPhaseWatch)
        settings.turnMode = TurnMode.AUTO
        assertTrue(settings.wantsTurnPhaseWatch)
        settings.turnMode = TurnMode.ON
        assertTrue(settings.wantsTurnPhaseWatch)
        settings.featureEnabled = false
        assertFalse(settings.wantsTurnPhaseWatch)
        settings.turnForced = true
        assertTrue("a forced session watches whatever the switches say", settings.wantsTurnPhaseWatch)
        settings.turnMode = TurnMode.OFF
        assertTrue(settings.wantsTurnPhaseWatch)

        val inputs = TurnSettings.Keys.PHASE_WATCH_INPUTS
        listOf(
            TurnSettings.Keys.TURN_FEATURE_ENABLED,
            TurnSettings.Keys.TURN_MODE,
            TurnSettings.Keys.TURN_ENABLED,
            TurnSettings.Keys.TURN_FORCED,
        ).forEach { assertTrue(it, it in inputs) }
        assertFalse(TurnSettings.Keys.TURN_VK_LINK in inputs)
    }
}
