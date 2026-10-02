package com.argsment.anywhere.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec §10: what the end-customer UI (Pro Mode off) hides. */
class ProModeVisibilityTest {

    @Test
    fun offHidesTheProSurfaces() {
        val v = ProModeVisibility.of(proModeEnabled = false, turnFeatureEnabled = true)
        assertFalse(v.proxiesTab)
        assertFalse(v.chainsTab)
        assertFalse(v.securitySection)
        assertFalse(v.advancedSettings)
        assertFalse("an import link opens the add sheet over Home", v.deepLinkOpensProxiesTab)
    }

    @Test
    fun onShowsEverything() {
        val v = ProModeVisibility.of(proModeEnabled = true, turnFeatureEnabled = true)
        assertTrue(v.proxiesTab)
        assertTrue(v.chainsTab)
        assertTrue(v.securitySection)
        assertTrue(v.advancedSettings)
        assertTrue(v.deepLinkOpensProxiesTab)
        assertTrue(v.utilitiesSection)
        assertTrue(v.turnItem)
    }

    @Test
    fun turnItemFollowsTheFeatureNotProMode() {
        assertTrue(ProModeVisibility.of(proModeEnabled = false, turnFeatureEnabled = true).turnItem)
        assertTrue(ProModeVisibility.of(proModeEnabled = false, turnFeatureEnabled = true).utilitiesSection)
        // Android has no Purify/Reflection/MITM, so without TURN the section is empty — hidden.
        assertFalse(ProModeVisibility.of(proModeEnabled = true, turnFeatureEnabled = false).turnItem)
        assertFalse(ProModeVisibility.of(proModeEnabled = true, turnFeatureEnabled = false).utilitiesSection)
    }
}
