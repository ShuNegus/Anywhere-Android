package com.argsment.anywhere.data.sync

import com.argsment.anywhere.data.repository.writeTextAsync
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
)

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

    fun record(subscriptionId: UUID): SubscriptionSyncRecord? =
        synchronized(lock) { records[subscriptionId.toString()] }

    /** Called after every successful fetch, with the `push` block it carried (or none). */
    fun recordRefresh(subscriptionId: UUID, push: SubscriptionPush?) {
        val record = SubscriptionSyncRecord(revUrl = push?.revUrl, rev = push?.rev ?: 0)
        mutate { it[subscriptionId.toString()] = record }
    }

    fun remove(subscriptionId: UUID) = mutate { it.remove(subscriptionId.toString()) }

    private fun mutate(block: (MutableMap<String, SubscriptionSyncRecord>) -> Unit) {
        val snapshot = synchronized(lock) {
            val next = LinkedHashMap(records)
            block(next)
            records = next
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
