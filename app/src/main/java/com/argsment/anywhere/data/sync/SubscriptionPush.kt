package com.argsment.anywhere.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The `push` block of the `<subscription>/turn` response:
 * `{"push": {"rev": 3, "rev_url": "https://…/<id>/rev", "apns_register_url": "…"}}`.
 *
 * [rev] is the server's subscription revision; an admin bumps it to make clients refresh.
 * [revUrl] answers `{"rev": N}` and is cheap to poll. `apns_register_url` is iOS-only.
 */
data class SubscriptionPush(val rev: Long, val revUrl: String?) {

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** `null` unless the body is a JSON object with a `push` block carrying `rev`. */
        fun parse(body: String): SubscriptionPush? {
            val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
                ?: return null
            val push = root["push"] as? JsonObject ?: return null
            val rev = (push["rev"] as? JsonPrimitive)?.longOrNull ?: return null
            val revUrl = (push["rev_url"] as? JsonPrimitive)
                ?.takeIf { it.isString }?.content
                ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            return SubscriptionPush(rev, revUrl)
        }

        fun parse(body: ByteArray): SubscriptionPush? = parse(String(body, Charsets.UTF_8))

        /** Body of `GET rev_url`: `{"rev": N}`. */
        fun parseRev(body: String): Long? {
            val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
                ?: return null
            return (root["rev"] as? JsonPrimitive)?.longOrNull
        }
    }
}
