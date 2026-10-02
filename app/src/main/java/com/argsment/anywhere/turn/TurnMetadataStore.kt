package com.argsment.anywhere.turn

import com.argsment.anywhere.data.repository.writeTextAsync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.UUID

/**
 * TURN metadata delivered alongside each subscription, keyed by `Subscription.id`.
 * Mirrors iOS `TurnMetadataStore` (`Shared/DataStore/TurnMetadataStore.swift`).
 *
 * Persisted as `{ "<uuid>": <turn block> }` in [file] (iOS: `turn.bin` in the app
 * group). It is refetched on every subscription refresh and tied to one account, so it
 * is not part of any backup/sync. iOS re-reads the file by mtime because the app and the
 * extension are two processes; here the service shares the app process, so the
 * in-memory map is the source of truth and the file is only loaded once. Should the
 * service ever move to its own `android:process`, the mtime trick has to come back.
 *
 * @param file `null` keeps everything in memory (tests).
 */
class TurnMetadataStore(private val file: File?) {

    private val lock = Any()
    private val _bySubscription = MutableStateFlow<Map<UUID, TurnMetadata>>(load())

    /** Observable snapshot for the UI (the TURN screen's server list, Phase B). */
    val bySubscription: StateFlow<Map<UUID, TurnMetadata>> = _bySubscription.asStateFlow()

    // MARK: - Reading

    fun metadata(subscriptionId: UUID): TurnMetadata? = _bySubscription.value[subscriptionId]

    fun all(): Map<UUID, TurnMetadata> = _bySubscription.value

    /**
     * Looks a relay up by proxy host across every subscription. A usable entry wins over
     * an unusable one; otherwise the first match.
     */
    fun server(host: String): TurnServerInfo? {
        val needle = host.lowercase()
        val candidates = _bySubscription.value.values.mapNotNull { it.server(needle) }
        return candidates.firstOrNull { it.isUsable } ?: candidates.firstOrNull()
    }

    /** Dialer knobs for [host], from the (first) subscription that lists it. */
    fun defaults(host: String): TurnDefaults? {
        val needle = host.lowercase()
        return _bySubscription.value.values.firstOrNull { it.server(needle) != null }?.defaults
    }

    /** The first non-blank `vk_link` any subscription shipped. */
    fun subscriptionVKLink(): String? =
        _bySubscription.value.values.mapNotNull { it.vkLink }.firstOrNull { it.isNotBlank() }

    /** Every known relay, deduplicated by host (usable wins), sorted by host. */
    fun allServers(): List<TurnServerInfo> {
        val byHost = LinkedHashMap<String, TurnServerInfo>()
        for (metadata in _bySubscription.value.values) {
            for ((host, server) in metadata.servers) {
                val existing = byHost[host]
                if (existing == null || !existing.isUsable) byHost[host] = server
            }
        }
        return byHost.values.sortedBy { it.host }
    }

    // MARK: - Writing

    /**
     * Records (or, with `null`, clears) the metadata for one subscription. A refresh
     * always calls this with whatever it fetched, so a relay withdrawn on the server
     * stops being used.
     */
    fun setMetadata(metadata: TurnMetadata?, subscriptionId: UUID) {
        val snapshot = synchronized(lock) {
            val next = LinkedHashMap(_bySubscription.value)
            if (metadata != null) next[subscriptionId] = metadata else next.remove(subscriptionId)
            _bySubscription.value = next
            next
        }
        persist(snapshot)
    }

    fun removeMetadata(subscriptionId: UUID) = setMetadata(null, subscriptionId)

    // MARK: - Persistence

    private fun persist(snapshot: Map<UUID, TurnMetadata>) {
        val target = file ?: return
        target.writeTextAsync(encode(snapshot)) {
            println("Failed to save TURN metadata: $it")
        }
    }

    private fun load(): Map<UUID, TurnMetadata> {
        val source = file ?: return emptyMap()
        if (!source.exists()) return emptyMap()
        return runCatching { decode(source.readText()) }.getOrElse {
            println("Failed to load TURN metadata: $it")
            emptyMap()
        }
    }

    companion object {
        const val FILE_NAME = "turn.json"

        private val json = Json { prettyPrint = false }

        fun encode(snapshot: Map<UUID, TurnMetadata>): String =
            json.encodeToString(
                JsonObject.serializer(),
                JsonObject(snapshot.entries.associate { it.key.toString() to it.value.toJson() })
            )

        fun decode(text: String): Map<UUID, TurnMetadata> {
            val root = json.parseToJsonElement(text) as? JsonObject ?: return emptyMap()
            val out = LinkedHashMap<UUID, TurnMetadata>()
            for ((key, value) in root) {
                val id = runCatching { UUID.fromString(key) }.getOrNull() ?: continue
                val obj = value as? JsonObject ?: continue
                out[id] = TurnMetadata.fromJson(obj)
            }
            return out
        }
    }
}
