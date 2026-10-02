package com.argsment.anywhere.turn

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * One vk-turn relay endpoint, as advertised by the subscription. Mirrors iOS
 * `TurnServerInfo` (`Shared/Models/TurnMetadata.swift`).
 *
 * [host] is not a field in the wire format — it is the key of the `servers` object,
 * folded into the value while decoding, and written out in our persisted copy.
 */
data class TurnServerInfo(
    val host: String,
    val supported: Boolean,
    val peerAddr: String,
    val wrapKeyHex: String,
) {
    /** Usable only when the relay is marked supported and actually addressable. */
    val isUsable: Boolean
        get() = supported && peerAddr.isNotEmpty() && host.isNotEmpty()

    fun withHost(host: String): TurnServerInfo = copy(host = host)

    fun toJson(): JsonObject = buildJsonObject {
        put("host", host)
        put("supported", supported)
        put("peer_addr", peerAddr)
        put("wrap_key_hex", wrapKeyHex)
    }

    companion object {
        /** Every field is optional; a wrong type counts as absent. */
        fun fromJson(obj: JsonObject): TurnServerInfo = TurnServerInfo(
            host = obj.string("host") ?: "",
            supported = obj.bool("supported") ?: false,
            peerAddr = obj.string("peer_addr") ?: "",
            wrapKeyHex = obj.string("wrap_key_hex") ?: "",
        )
    }
}

/**
 * Dialer knobs the server suggests. Every field is optional. Only [wrapMode],
 * [readyTimeout] and [captchaSolver] are actually used: `num_streams` is ignored (the
 * user's Peers setting wins) and `streams_per_cred` is never forwarded to the core.
 */
data class TurnDefaults(
    val wrapMode: Boolean? = null,
    val numStreams: Int? = null,
    val readyTimeout: Int? = null,
    val captchaSolver: String? = null,
    val streamsPerCred: Int? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        wrapMode?.let { put("wrap_mode", it) }
        numStreams?.let { put("num_streams", it) }
        readyTimeout?.let { put("ready_timeout", it) }
        captchaSolver?.let { put("captcha_solver", it) }
        streamsPerCred?.let { put("streams_per_cred", it) }
    }

    companion object {
        fun fromJson(obj: JsonObject): TurnDefaults = TurnDefaults(
            wrapMode = obj.bool("wrap_mode"),
            numStreams = obj.int("num_streams"),
            readyTimeout = obj.int("ready_timeout"),
            captchaSolver = obj.string("captcha_solver"),
            streamsPerCred = obj.int("streams_per_cred"),
        )
    }
}

/**
 * The `turn` block of a sing-box-flavoured subscription response (spec §4.1/§4.2).
 *
 * Decoding is tolerant exactly like the iOS `Codable` implementation: a broken sub-field
 * is dropped rather than failing the whole block, `version` defaults to 1.
 */
data class TurnMetadata(
    val version: Int = 1,
    val vkLink: String? = null,
    val vkLinkRequired: Boolean? = null,
    val defaults: TurnDefaults? = null,
    /** Keyed by the proxy host (= `ProxyConfiguration.serverAddress`); insertion-ordered. */
    val servers: Map<String, TurnServerInfo> = emptyMap(),
) {
    /** Servers sorted by host, for stable display. */
    val sortedServers: List<TurnServerInfo>
        get() = servers.values.sortedBy { it.host }

    fun server(host: String): TurnServerInfo? = servers[host.lowercase()] ?: servers[host]

    fun toJson(): JsonObject = buildJsonObject {
        put("version", version)
        vkLink?.let { put("vk_link", it) }
        vkLinkRequired?.let { put("vk_link_required", it) }
        defaults?.let { put("defaults", it.toJson()) }
        put("servers", JsonObject(servers.mapValues { it.value.toJson() }))
    }

    companion object {
        private val json = Json

        /** Decodes one `turn` object (wire format or our persisted copy). */
        fun fromJson(obj: JsonObject): TurnMetadata {
            val rawServers = obj["servers"] as? JsonObject
            val servers = LinkedHashMap<String, TurnServerInfo>()
            rawServers?.forEach { (key, value) ->
                // A non-object entry is dropped; iOS fails the whole dictionary there,
                // which only matters for a malformed payload.
                (value as? JsonObject)?.let { servers[key] = TurnServerInfo.fromJson(it).withHost(key) }
            }
            return TurnMetadata(
                version = obj.int("version") ?: 1,
                vkLink = obj.string("vk_link"),
                vkLinkRequired = obj.bool("vk_link_required"),
                defaults = (obj["defaults"] as? JsonObject)?.let { TurnDefaults.fromJson(it) },
                servers = servers,
            )
        }

        /**
         * Pulls the `turn` block out of a full sing-box subscription document. `null` for
         * anything that is not a JSON object carrying a `turn` object with servers.
         */
        fun extractFromSingBoxJson(body: String): TurnMetadata? {
            val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
                ?: return null
            val turn = root["turn"] as? JsonObject ?: return null
            val metadata = fromJson(turn)
            return metadata.takeIf { it.servers.isNotEmpty() }
        }

        fun extractFromSingBoxJson(body: ByteArray): TurnMetadata? =
            extractFromSingBoxJson(String(body, Charsets.UTF_8))
    }
}

// Tolerant accessors: a value of the wrong JSON type reads as absent.

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

internal fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

internal fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
