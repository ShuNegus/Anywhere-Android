package com.argsment.anywhere.vpn.turn

import com.argsment.anywhere.turn.TurnDefaults
import com.argsment.anywhere.turn.TurnLimits
import com.argsment.anywhere.turn.TurnServerInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builds the `clientcore.Config` payload handed to `Anywhere.newDialer` (spec §2.3).
 * Mirrors iOS `TurnDialerConfig`.
 *
 * No `streams_per_cred` is ever sent, whatever the subscription suggests: the core fills
 * one VK credential set until the relay refuses it and only then fetches another (and
 * possibly another captcha). `defaults.num_streams` is ignored too — the session count
 * is the user's Peers setting (possibly trimmed for a new pool by [TurnMemory]).
 */
object TurnDialerConfig {

    fun sessionCount(peers: Int): Int = TurnLimits.clampPeers(peers)

    /** @param vkLink already trimmed and validated by the caller. */
    fun make(
        server: TurnServerInfo,
        vkLink: String,
        defaults: TurnDefaults?,
        peers: Int,
        manualCaptcha: Boolean,
    ): JsonObject = buildJsonObject {
        put("peer_addr", server.peerAddr)
        put("vk_link", vkLink)
        put("vless_mode", true)
        put("num_streams", sessionCount(peers))
        put("manual_captcha", manualCaptcha)
        val wrapMode = defaults?.wrapMode ?: server.wrapKeyHex.isNotEmpty()
        if (wrapMode) {
            put("wrap_mode", true)
            put("wrap_key_hex", server.wrapKeyHex)
        }
        val solver = defaults?.captchaSolver
        if (!solver.isNullOrEmpty()) put("captcha_solver", solver)
    }
}

/**
 * The identity of a live TURN pool: change any of these and the pools are rebuilt.
 *
 * Only what the *user* configured goes in. The memory-derived session cap must never
 * enter it: it moves with the traffic the pools carry, and on iOS a pool rebuilt
 * mid-transfer dropped every live stream ("speedtest dies after 3-4 s", spec §14 #2).
 */
object TurnPoolFingerprint {
    /** @param peers the user's setting, not the memory-adjusted session count. */
    fun make(vkLink: String, peers: Int, manualCaptcha: Boolean): String = "$vkLink|$peers|$manualCaptcha"
}
