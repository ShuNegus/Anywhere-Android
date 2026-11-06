package com.argsment.anywhere.data.sync

import com.argsment.anywhere.data.repository.writeTextAsync
import com.argsment.anywhere.turn.TurnFetchOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** What the last successful refresh of one subscription learned from the server. */
@Serializable
data class SubscriptionSyncRecord(
    /** Where to poll the revision; `null` when the server offers no `push` block. */
    val revUrl: String? = null,
    /** Revision the configurations on disk correspond to. */
    val rev: Long = 0,
    /** [TurnFetchOutcome] raw value of the last `/turn` request; `null` before it was recorded. */
    val turnOutcome: String? = null,
    /** `/turn` has answered with our blocks at least once: failures are ours to report. */
    val turnSeen: Boolean = false,
) {
    val outcome: TurnFetchOutcome? get() = TurnFetchOutcome.fromRaw(turnOutcome)

    /** The subscription is served by our backend (it answered `/turn` or has a revision URL). */
    val knownOurs: Boolean get() = turnSeen || revUrl != null

    companion object {
        /**
         * The record after a refresh. A failed `/turn` keeps what was known (revision URL,
         * revision): the configurations were refreshed, the bypass data was not.
         */
        fun next(previous: SubscriptionSyncRecord?, push: SubscriptionPush?, outcome: TurnFetchOutcome?): SubscriptionSyncRecord {
            val seen = (previous?.turnSeen ?: false) || outcome == TurnFetchOutcome.OK
            if (outcome == TurnFetchOutcome.FAILED && previous != null) {
                return previous.copy(turnOutcome = outcome.rawValue, turnSeen = seen)
            }
            return SubscriptionSyncRecord(
                revUrl = push?.revUrl ?: if (outcome == TurnFetchOutcome.NOT_FOUND) previous?.revUrl else null,
                rev = push?.rev ?: 0,
                turnOutcome = outcome?.rawValue,
                turnSeen = seen,
            )
        }
    }
}

/**
 * Per-subscription revision bookkeeping for forced refreshes, keyed by `Subscription.id`,
 * persisted as `{ "<uuid>": {revUrl, rev} }` in [file]. Single process: the in-memory map
 * is the source of truth, the file is loaded once.
 *
 * @param file `null` keeps everything in memory (tests).
 */
class SubscriptionSyncStore(private val file: File?) {

    private val lock = Any()
    private var records: Map<String, SubscriptionSyncRecord> = load()
    private val _flow = MutableStateFlow(records)

    /** Observable snapshot keyed by subscription id string (the home screen's bypass icons). */
    val flow: StateFlow<Map<String, SubscriptionSyncRecord>> = _flow.asStateFlow()

    fun record(subscriptionId: UUID): SubscriptionSyncRecord? =
        synchronized(lock) { records[subscriptionId.toString()] }

    /**
     * Called after every successful fetch, with the `push` block it carried (or none) and
     * how its `/turn` request ended.
     */
    fun recordRefresh(subscriptionId: UUID, push: SubscriptionPush?, outcome: TurnFetchOutcome? = null) {
        mutate { it[subscriptionId.toString()] = SubscriptionSyncRecord.next(it[subscriptionId.toString()], push, outcome) }
    }

    fun remove(subscriptionId: UUID) = mutate { it.remove(subscriptionId.toString()) }

    private fun mutate(block: (MutableMap<String, SubscriptionSyncRecord>) -> Unit) {
        val snapshot = synchronized(lock) {
            val next = LinkedHashMap(records)
            block(next)
            records = next
            _flow.value = next
            next
        }
        val target = file ?: return
        target.writeTextAsync(json.encodeToString(serializer, snapshot)) {
            println("Failed to save subscription sync state: $it")
        }
    }

    private fun load(): Map<String, SubscriptionSyncRecord> {
        val source = file ?: return emptyMap()
        if (!source.exists()) return emptyMap()
        return runCatching { json.decodeFromString(serializer, source.readText()) }.getOrElse {
            println("Failed to load subscription sync state: $it")
            emptyMap()
        }
    }

    companion object {
        const val FILE_NAME = "subscription-sync.json"
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = MapSerializer(String.serializer(), SubscriptionSyncRecord.serializer())
    }
}
