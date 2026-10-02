package com.argsment.anywhere.vpn.turn

import com.argsment.anywhere.turn.bool
import com.argsment.anywhere.turn.int
import com.argsment.anywhere.turn.string
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Legacy single-step phase of a Go dialer (`Dialer.phase()`, `Anywhere.Phase*`).
 * The numbering is the core's and must not change.
 */
enum class TurnPhase(val rawValue: Int) {
    INACTIVE(0),
    VK_ACCESS(1),
    CAPTCHA_AUTO(2),
    CAPTCHA_WAIT(3),
    TUNNEL_SETUP(4),
    READY(5);

    companion object {
        fun fromRaw(raw: Int?): TurnPhase? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** What a pool's credential fetch is doing. Mirrors iOS `TurnCoreStatus.FetchState`. */
enum class TurnFetchState(val rawValue: String, internal val urgency: Int) {
    IDLE("idle", 0),
    /** VK login in progress. */
    FETCHING("fetching", 3),
    /** A captcha is being solved automatically. */
    CAPTCHA_AUTO("captcha_auto", 4),
    /** A captcha waits for the user. */
    CAPTCHA_WAIT("captcha_wait", 5),
    /** Sessions wait for room and the next fetch is held back after a failure. */
    BACKOFF("backoff", 2),
    /** A state this build does not know (a newer core). */
    UNKNOWN("unknown", 1);

    val isCaptcha: Boolean get() = this == CAPTCHA_AUTO || this == CAPTCHA_WAIT

    companion object {
        fun fromRaw(raw: String): TurnFetchState =
            entries.firstOrNull { it.rawValue == raw && it != UNKNOWN } ?: UNKNOWN
    }
}

/**
 * Snapshot of one Go dialer, decoded from `clientcore.Status` (`Dialer.statusJSON()`),
 * format version 1 (spec §2.4). Mirrors iOS `TurnCoreStatus`.
 */
data class TurnCoreStatus(
    val version: Int,
    val usable: Boolean,
    val sessions: Int,
    val target: Int,
    /** Legacy phase ([TurnPhase] raw value). */
    val phase: Int,
    val fetch: Fetch,
    val setsObtained: Int,
    val setsRetired: Int,
    val captchas: Int,
    val sets: List<CredentialSet>,
) {
    data class Fetch(
        val state: TurnFetchState,
        /** Credential set being fetched, -1 when none is. */
        val setId: Int,
        val backoffMs: Int?,
        /** Sessions waiting for a slot. */
        val waiting: Int,
    )

    data class CredentialSet(
        val id: Int,
        /** `filling`, `full` or `retired`. */
        val state: String,
        val active: Int,
        val pending: Int,
        /** Learned capacity once the relay said the set is full; 0 before. */
        val capacity: Int,
    )

    companion object {
        /**
         * The format version this build understands. A different one is not trusted:
         * fields are only ever added within a version, a bump means one changed meaning.
         */
        const val SUPPORTED_VERSION = 1

        private val json = Json

        /**
         * Decodes the core's JSON. `null` for an empty payload (closed dialer), an
         * unknown version, or a payload missing a required field — as strict as the
         * iOS `Codable` decoder, where only `backoff_ms` is optional.
         */
        fun decode(coreJson: String?): TurnCoreStatus? {
            if (coreJson.isNullOrEmpty()) return null
            val root = runCatching { json.parseToJsonElement(coreJson) }.getOrNull() as? JsonObject
                ?: return null
            return runCatching {
                val version = root.int("version")!!
                if (version != SUPPORTED_VERSION) return null
                val fetch = root["fetch"] as JsonObject
                val sets = (root["sets"] as JsonArray).map { element ->
                    val set = element as JsonObject
                    CredentialSet(
                        id = set.int("id")!!,
                        state = set.string("state")!!,
                        active = set.int("active")!!,
                        pending = set.int("pending")!!,
                        capacity = set.int("capacity")!!,
                    )
                }
                TurnCoreStatus(
                    version = version,
                    usable = root.bool("usable")!!,
                    sessions = root.int("sessions")!!,
                    target = root.int("target")!!,
                    phase = root.int("phase")!!,
                    fetch = Fetch(
                        state = TurnFetchState.fromRaw(fetch.string("state")!!),
                        setId = fetch.int("set_id")!!,
                        backoffMs = fetch.int("backoff_ms"),
                        waiting = fetch.int("waiting")!!,
                    ),
                    setsObtained = root.int("sets_obtained")!!,
                    setsRetired = root.int("sets_retired")!!,
                    captchas = root.int("captchas")!!,
                    sets = sets,
                )
            }.getOrNull()
        }
    }
}

/**
 * All relays' pools folded into what the UI shows. Mirrors iOS `TurnPoolSummary`.
 * Normally there is one pool (the selected server).
 */
data class TurnPoolSummary(
    /** At least one pool carries traffic. */
    val usable: Boolean,
    val sessions: Int,
    val target: Int,
    /** The most urgent fetch state across the pools. */
    val fetch: TurnFetchState,
    /** Fetches that hit a captcha, over the pools' lifetime. */
    val captchas: Int,
    /** Credential sets obtained over the pools' lifetime; a rise means a login finished. */
    val setsObtained: Int,
) {
    /** The tunnel works and the pool is still being topped up. */
    val isAddingPeers: Boolean get() = usable && sessions < target && fetch != TurnFetchState.IDLE

    /** The tunnel works, but more peers need the user to solve a captcha. */
    val needsCaptchaForMorePeers: Boolean get() = usable && fetch == TurnFetchState.CAPTCHA_WAIT

    companion object {
        /** `null` when no pool reports a status. */
        fun combining(statuses: List<TurnCoreStatus>): TurnPoolSummary? {
            if (statuses.isEmpty()) return null
            return TurnPoolSummary(
                usable = statuses.any { it.usable },
                sessions = statuses.sumOf { it.sessions },
                target = statuses.sumOf { it.target },
                fetch = statuses.map { it.fetch.state }.maxByOrNull { it.urgency } ?: TurnFetchState.IDLE,
                captchas = statuses.sumOf { it.captchas },
                setsObtained = statuses.sumOf { it.setsObtained },
            )
        }
    }
}

/** Per-relay live counts (iOS `TurnHostStatistics`). */
data class TurnHostStatistics(
    val host: String,
    val sessions: Int,
    /** Streams this app has open over the pool. */
    val streams: Int,
    /** Raw [TurnPhase] value. */
    val phase: Int?,
    val credentialSets: Int?,
    val credentialSetsPassed: Int?,
    val captchaHits: Int?,
    val core: TurnCoreStatus? = null,
)

/**
 * What iOS sends over IPC as `TurnStatsResponse`, plus the values `VPNViewModel`
 * derives from it each second (spec §6.1/§6.2). On Android it is published in-process
 * by [TurnStatusMonitor].
 */
data class TurnStatsSnapshot(
    val hosts: List<TurnHostStatistics> = emptyList(),
    /** Auto-pilot decision raw value, only when the effective mode is auto. */
    val autoDecision: String? = null,
) {
    /**
     * The least advanced phase among the pools, ignoring inactive ones (the graph shows
     * the step that is lagging); `READY` if none reports a phase but sessions exist.
     * Never latched — it may go backwards while a pool reconnects.
     */
    val phase: TurnPhase?
        get() {
            val active = hosts.mapNotNull { TurnPhase.fromRaw(it.phase) }
                .filter { it != TurnPhase.INACTIVE }
            active.minByOrNull { it.rawValue }?.let { return it }
            return if (hosts.sumOf { it.sessions } > 0) TurnPhase.READY else null
        }

    val pool: TurnPoolSummary?
        get() = TurnPoolSummary.combining(hosts.mapNotNull { it.core })

    val totalSessions: Int get() = hosts.sumOf { it.sessions }

    val captchaHits: Int get() = hosts.sumOf { it.captchaHits ?: 0 }
}
