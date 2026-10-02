package com.argsment.anywhere.ui.settings

/**
 * What the UI shows with Pro Mode on or off (spec §10, iOS `SettingsView` + `MainTabView`).
 * Off (the default) is the end-customer UI: only what cannot hurt the connection.
 *
 * iOS → Android mapping:
 * - Proxies tab (iOS also holds chains there) → the Proxies **and** Chains tabs;
 * - iCloud Sync, the "VPN" section (Always On) → nothing on Android (Always-on is a system
 *   setting; there is no sync);
 * - the whole Security section → Allow Insecure + Trusted Certificates;
 * - Utilities: Purify/Reflection/MITM do not exist on Android, so the section holds only
 *   TURN, shown whenever the TURN feature is on, Pro Mode or not;
 * - the "Advanced Settings" link → the Advanced Settings row.
 *
 * Always visible: Routing, Diagnostics (Logs), About, the Pro Mode toggle itself, server
 * selection and subscription add/delete on the home screen.
 */
data class ProModeVisibility(
    val proxiesTab: Boolean,
    val chainsTab: Boolean,
    val securitySection: Boolean,
    val utilitiesSection: Boolean,
    val turnItem: Boolean,
    val advancedSettings: Boolean,
    /** A subscription deep link opens the Proxies tab (upstream) rather than the add sheet over Home. */
    val deepLinkOpensProxiesTab: Boolean,
) {
    companion object {
        fun of(proModeEnabled: Boolean, turnFeatureEnabled: Boolean) = ProModeVisibility(
            proxiesTab = proModeEnabled,
            chainsTab = proModeEnabled,
            securitySection = proModeEnabled,
            utilitiesSection = turnFeatureEnabled,
            turnItem = turnFeatureEnabled,
            advancedSettings = proModeEnabled,
            deepLinkOpensProxiesTab = proModeEnabled,
        )
    }
}
