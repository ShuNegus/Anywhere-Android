package com.argsment.anywhere.turn

import android.content.Context
import android.content.SharedPreferences

/**
 * User-facing TURN routing choice. Mirrors iOS `TurnMode` (`Shared/Models/TurnMode.swift`);
 * the raw values are what is persisted.
 */
enum class TurnMode(val rawValue: String) {
    AUTO("auto"),
    ON("on"),
    OFF("off");

    companion object {
        fun fromRaw(raw: String?): TurnMode? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** Mirrors iOS `TurnLimits` (`Shared/AWCore.swift`). */
object TurnLimits {
    const val DEFAULT_PEERS = 10
    const val MIN_PEERS = 1
    const val MAX_PEERS = 50
    /** For UI copy only; the core learns the relay's real capacity by itself. */
    const val RELAY_PEERS_PER_ACCOUNT = 20

    /** `<= 0` means "unset" and falls back to the default. */
    fun clampPeers(value: Int): Int =
        if (value <= 0) DEFAULT_PEERS else value.coerceIn(MIN_PEERS, MAX_PEERS)
}

/**
 * The TURN preferences, the Android counterpart of the TURN keys in iOS `AWCore`.
 *
 * iOS keeps them in the app-group `UserDefaults` shared by the app and its Network
 * Extension. Android runs the VPN service in the app process, so they live in the same
 * `anywhere_settings` SharedPreferences as everything else. None of these keys is read
 * by `LwipStack.handleSettingsChanged`, so writing them never restarts the stack.
 *
 * Defaults match iOS `register(defaults:)`: SharedPreferences has no registration
 * domain, so they are applied at read time — which also means `contains()` is the
 * exact equivalent of iOS's "present in the persistent domain".
 *
 * Routing code must read [turnActiveForSession] and [effectiveTurnMode], never
 * [turnMode] directly: a forced session overrides the stored mode.
 */
class TurnSettings(
    private val prefs: SharedPreferences,
    private val now: () -> Long = System::currentTimeMillis,
) {

    object Keys {
        const val TURN_FEATURE_ENABLED = "turnFeatureEnabled"
        const val TURN_MODE = "turnMode"
        /** Legacy boolean, read only to migrate into [TURN_MODE]. */
        const val TURN_ENABLED = "turnEnabled"
        const val TURN_FORCED = "turnForced"
        const val TURN_VK_LINK = "turnVKLink"
        const val TURN_PEERS = "turnPeers"
        const val TURN_CAPTCHA_MANUAL = "turnCaptchaManual"
        const val PRO_MODE_ENABLED = "proModeEnabled"
        const val APP_IS_FOREGROUND = "appIsForeground"
        const val PREFLIGHT_VERDICT = "preflightVerdict"
        const val PREFLIGHT_VERDICT_AT = "preflightVerdictAt"
        const val CONNECTION_GRAPH_EXPANDED = "connectionGraphExpanded"
    }

    /**
     * Master switch for the whole feature. There is no UI for it (code/defaults only).
     * When false: no second subscription request, no TURN routing — except a forced
     * session.
     */
    var featureEnabled: Boolean
        get() = prefs.getBoolean(Keys.TURN_FEATURE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(Keys.TURN_FEATURE_ENABLED, value).apply()

    /**
     * The stored mode. Migrates the legacy `turnEnabled` boolean on first read
     * (`true → on`, `false → off`) and writes the result back; a fresh install has
     * neither key and lands on [TurnMode.AUTO] *without* persisting it.
     */
    var turnMode: TurnMode
        get() {
            TurnMode.fromRaw(prefs.getString(Keys.TURN_MODE, null))?.let { return it }
            if (prefs.contains(Keys.TURN_ENABLED)) {
                val legacy = runCatching { prefs.getBoolean(Keys.TURN_ENABLED, false) }.getOrNull()
                if (legacy != null) {
                    val mode = if (legacy) TurnMode.ON else TurnMode.OFF
                    prefs.edit().putString(Keys.TURN_MODE, mode.rawValue).apply()
                    return mode
                }
            }
            return TurnMode.AUTO
        }
        set(value) = prefs.edit().putString(Keys.TURN_MODE, value.rawValue).apply()

    /**
     * Set by holding the power button: this tunnel session goes through TURN whatever
     * the mode or the master switch say. Cleared by an ordinary connect/disconnect,
     * kept by a reconnect.
     */
    var turnForced: Boolean
        get() = prefs.getBoolean(Keys.TURN_FORCED, false)
        set(value) = prefs.edit().putBoolean(Keys.TURN_FORCED, value).apply()

    /** Whether any TURN behaviour applies at all this session. */
    val turnActiveForSession: Boolean
        get() = featureEnabled || turnForced

    /** The mode routing decisions follow; the settings screen shows [turnMode]. */
    val effectiveTurnMode: TurnMode
        get() = if (turnForced) TurnMode.ON else turnMode

    /** Manual VK Calls join link; overrides the one the subscription ships. Trimmed on write. */
    var vkLink: String
        get() = prefs.getString(Keys.TURN_VK_LINK, "") ?: ""
        set(value) = prefs.edit().putString(Keys.TURN_VK_LINK, value.trim()).apply()

    /** TURN sessions ("peers") a new pool runs. Clamped on read and write. */
    var peers: Int
        get() = TurnLimits.clampPeers(
            runCatching { prefs.getInt(Keys.TURN_PEERS, TurnLimits.DEFAULT_PEERS) }
                .getOrDefault(TurnLimits.DEFAULT_PEERS)
        )
        set(value) = prefs.edit().putInt(Keys.TURN_PEERS, TurnLimits.clampPeers(value)).apply()

    /** Solve VK captchas by hand in a web view instead of the automatic solver. */
    var captchaManual: Boolean
        get() = prefs.getBoolean(Keys.TURN_CAPTCHA_MANUAL, false)
        set(value) = prefs.edit().putBoolean(Keys.TURN_CAPTCHA_MANUAL, value).apply()

    /** Pro Mode (Phase B, spec §10). */
    var proModeEnabled: Boolean
        get() = prefs.getBoolean(Keys.PRO_MODE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(Keys.PRO_MODE_ENABLED, value).apply()

    /** False until the app has written it at least once, like iOS. */
    var appIsForeground: Boolean
        get() = prefs.getBoolean(Keys.APP_IS_FOREGROUND, false)
        set(value) = prefs.edit().putBoolean(Keys.APP_IS_FOREGROUND, value).apply()

    /**
     * A new process has no activity on screen yet. [appIsForeground] lives in prefs, so a
     * process that died while the app was in front (crash, low-memory kill — no `onStop`)
     * would leave `true` behind, and an always-on VPN restarted in the next process would
     * then never post the captcha notification. Called once per process, before
     * `MainActivity.onStart` writes the real value.
     */
    fun noteProcessStarted() {
        appIsForeground = false
    }

    var connectionGraphExpanded: Boolean
        get() = prefs.getBoolean(Keys.CONNECTION_GRAPH_EXPANDED, false)
        set(value) = prefs.edit().putBoolean(Keys.CONNECTION_GRAPH_EXPANDED, value).apply()

    /**
     * Records the pre-flight probe verdict (`offline`/`blocked`/`open`) with the time it
     * was taken, so the auto-pilot can cross-check its own first probe (spec §7.5).
     */
    fun setPreflightVerdictRaw(raw: String) {
        prefs.edit()
            .putString(Keys.PREFLIGHT_VERDICT, raw)
            .putLong(Keys.PREFLIGHT_VERDICT_AT, now())
            .apply()
    }

    /** The pre-flight verdict, or null if there was none or it is older than [maxAgeMs]. */
    fun recentPreflightVerdictRaw(maxAgeMs: Long): String? {
        val raw = prefs.getString(Keys.PREFLIGHT_VERDICT, null) ?: return null
        val recordedAt = runCatching { prefs.getLong(Keys.PREFLIGHT_VERDICT_AT, 0L) }.getOrDefault(0L)
        val age = now() - recordedAt
        if (recordedAt <= 0 || age < 0 || age > maxAgeMs) return null
        return raw
    }

    companion object {
        const val PREFS_NAME = "anywhere_settings"

        fun from(context: Context): TurnSettings =
            TurnSettings(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
}
