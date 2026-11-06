package com.argsment.anywhere.data.network

import android.util.Base64
import com.argsment.anywhere.data.model.ProxyConfiguration
import com.argsment.anywhere.data.sync.SubscriptionPush
import com.argsment.anywhere.turn.TurnEnvironment
import com.argsment.anywhere.turn.TurnFetchOutcome
import com.argsment.anywhere.turn.TurnMetadata
import com.argsment.anywhere.vpn.util.AnywhereLogger
import com.argsment.anywhere.vpn.protocol.tls.CertificatePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

private val logger = AnywhereLogger("SubscriptionFetcher")

object SubscriptionFetcher {

    data class Result(
        val configurations: List<ProxyConfiguration>,
        val name: String?,
        val upload: Long?,
        val download: Long?,
        val total: Long?,
        val expire: Long?,
        /**
         * TURN relays advertised next to the subscription, fetched separately (see
         * [fetchTurnMetadata]). `null` when the server offers no `turn` block.
         */
        val turn: TurnMetadata? = null,
        /** The `push` block of the same response: revision + where to poll it. */
        val push: SubscriptionPush? = null,
        /**
         * How the `/turn` request ended; `null` when it was not made (no usable URL) or
         * the server answered 2xx with neither block (not ours).
         */
        val turnOutcome: TurnFetchOutcome? = null,
    )

    sealed class FetchError(message: String) : Exception(message) {
        class InvalidUrl : FetchError("Invalid subscription URL.")
        class NoConfigurations : FetchError("No valid configurations found in subscription.")
        class NetworkError(message: String) : FetchError("Network error: $message")
    }

    private const val TURN_METADATA_TIMEOUT_MS = 15_000

    /**
     * @param includeTurnMetadata whether to make the second request for the `turn` block.
     *   Defaults to the `turnFeatureEnabled` master switch.
     */
    suspend fun fetch(
        urlString: String,
        allowInsecure: Boolean = CertificatePolicy.allowInsecure,
        remnawaveHWID: String? = null,
        includeTurnMetadata: Boolean = TurnEnvironment.settings?.featureEnabled ?: true
    ): Result {
        val result = fetchConfigurations(urlString, allowInsecure, remnawaveHWID)
        // Second, optional request for the `turn` and `push` blocks. Never fatal — see
        // fetchTurnMetadata. Made even with TURN off: the revision drives auto-refresh.
        val endpoint = fetchTurnMetadata(urlString, allowInsecure, remnawaveHWID)
        return result.copy(
            turn = if (includeTurnMetadata) endpoint?.turn else null,
            push = endpoint?.push,
            turnOutcome = endpoint?.outcome,
        )
    }

    private suspend fun fetchConfigurations(
        urlString: String,
        allowInsecure: Boolean,
        remnawaveHWID: String?
    ): Result = withContext(Dispatchers.IO) {
        val url = runCatching { URL(urlString) }.getOrNull()
            ?: throw FetchError.InvalidUrl()

        val connection = openConnection(url, "Anywhere", remnawaveHWID, allowInsecure, 30_000)
        try {
            val responseCode = try {
                connection.responseCode
            } catch (e: Exception) {
                throw FetchError.NetworkError(e.message ?: "Unknown error")
            }

            if (responseCode !in 200..299) {
                throw FetchError.NetworkError("HTTP $responseCode")
            }

            val data = connection.inputStream.readBytes()

            val profileTitle = parseProfileTitle(connection)
            val userInfo = parseSubscriptionUserInfo(connection)

            // Decode body: try base64 first, fall back to raw UTF-8
            val bodyString: String
            val decoded = runCatching {
                Base64.decode(data, Base64.DEFAULT)
            }.getOrNull()
            val decodedString = decoded?.let { String(it, Charsets.UTF_8) }

            val parsablePrefixes = ProxyConfiguration.parsableUrlPrefixes

            bodyString = if (decodedString != null && parsablePrefixes.any { decodedString.contains(it) }) {
                decodedString
            } else {
                String(data, Charsets.UTF_8)
            }

            if (bodyString.contains("proxies:")) {
                val result = ClashProxyParser.parse(bodyString)
                if (result.configurations.isEmpty()) throw FetchError.NoConfigurations()
                return@withContext Result(
                    configurations = result.configurations,
                    name = profileTitle,
                    upload = userInfo.upload,
                    download = userInfo.download,
                    total = userInfo.total,
                    expire = userInfo.expire
                )
            }

            val configurations = bodyString
                .lines()
                .map { it.trim() }
                .filter { line -> parsablePrefixes.any { line.startsWith(it) } }
                .mapNotNull { runCatching { ProxyConfiguration.fromUrl(it) }.getOrNull() }

            if (configurations.isEmpty()) throw FetchError.NoConfigurations()

            Result(
                configurations = configurations,
                name = profileTitle,
                upload = userInfo.upload,
                download = userInfo.download,
                total = userInfo.total,
                expire = userInfo.expire
            )
        } finally {
            connection.disconnect()
        }
    }

    // MARK: - TURN metadata

    /**
     * Fetches the `turn` and `push` blocks for a subscription. Entirely best-effort: a
     * network error, an HTTP error or a non-JSON body yield `null`, a missing/empty block
     * yields `null` for that block, and the subscription is added exactly as before.
     *
     * Asks the dedicated `<subscription>/turn` endpoint, served next to the subscription:
     * it returns `{"turn": {...}, "push": {...}}` with only the user's own relays (`turn`
     * is absent when the user has none). There is deliberately
     * no fallback to the sing-box rendering of the subscription — that one lists every
     * relay, not just the user's, and sing-box subscriptions are being retired.
     */
    private suspend fun fetchTurnMetadata(
        urlString: String,
        allowInsecure: Boolean,
        remnawaveHWID: String?
    ): TurnEndpointResponse? {
        val endpoint = turnEndpointUrl(urlString) ?: return null
        return requestTurnMetadata(endpoint, allowInsecure, remnawaveHWID)
    }

    /**
     * `https://host/<id>` → `https://host/<id>/turn`; query kept, fragment dropped.
     * `null` for anything that is not an http(s) URL with a non-empty path.
     */
    internal fun turnEndpointUrl(urlString: String): String? {
        val uri = try {
            java.net.URI(urlString.trim())
        } catch (e: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase()
        if ((scheme != "http" && scheme != "https") || uri.rawAuthority.isNullOrEmpty()) return null
        val path = uri.rawPath.orEmpty().trimEnd('/')
        if (path.isEmpty()) return null
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return "$scheme://${uri.rawAuthority}$path/turn$query"
    }

    private suspend fun requestTurnMetadata(
        urlString: String,
        allowInsecure: Boolean,
        remnawaveHWID: String?
    ): TurnEndpointResponse? = withContext(Dispatchers.IO) {
        try {
            val url = URL(urlString)
            val connection = openConnection(
                url, "Anywhere", remnawaveHWID, allowInsecure, TURN_METADATA_TIMEOUT_MS
            )
            try {
                val code = connection.responseCode
                if (code == 404) return@withContext TurnEndpointResponse(null, null, TurnFetchOutcome.NOT_FOUND)
                if (code !in 200..299) return@withContext TurnEndpointResponse(null, null, TurnFetchOutcome.FAILED)
                val body = connection.inputStream.readBytes()
                val metadata = TurnMetadata.extractFromSingBoxJson(body)
                val push = SubscriptionPush.parse(body)
                if (metadata == null && push == null) return@withContext null
                logger.debug("TURN metadata: ${metadata?.servers?.size ?: 0} server(s), rev ${push?.rev}")
                TurnEndpointResponse(metadata, push, TurnFetchOutcome.OK)
            } finally {
                connection.disconnect()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.debug("TURN metadata probe failed: ${e.message}")
            TurnEndpointResponse(null, null, TurnFetchOutcome.FAILED)
        }
    }

    /** What `<subscription>/turn` returned, and how the request ended. */
    internal data class TurnEndpointResponse(
        val turn: TurnMetadata?,
        val push: SubscriptionPush?,
        val outcome: TurnFetchOutcome,
    )

    private fun openConnection(
        url: URL,
        userAgent: String,
        remnawaveHWID: String?,
        allowInsecure: Boolean,
        timeoutMs: Int
    ): HttpURLConnection {
        val connection = url.openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", userAgent)
        if (remnawaveHWID != null) {
            connection.setRequestProperty("x-hwid", remnawaveHWID)
        }
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs

        // Accept self-signed certificates only when the caller has indicated allowInsecure.
        if (connection is HttpsURLConnection && allowInsecure) {
            val trustAllManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
            connection.sslSocketFactory = sslContext.socketFactory
            connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        }
        return connection
    }

    private fun parseProfileTitle(connection: HttpURLConnection): String? {
        val value = connection.getHeaderField("profile-title") ?: return null
        val decoded = if (value.startsWith("base64:")) {
            val encoded = value.removePrefix("base64:")
            runCatching {
                String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull()
        } else {
            value
        }
        // Servers commonly send an empty `Profile-Title: base64:` for unnamed
        // subscriptions. Treat blank results as missing so the call site falls
        // back to the URL host instead of an empty string.
        return decoded?.takeIf { it.isNotBlank() }
    }

    data class UserInfo(
        val upload: Long? = null,
        val download: Long? = null,
        val total: Long? = null,
        val expire: Long? = null
    )

    private fun parseSubscriptionUserInfo(connection: HttpURLConnection): UserInfo {
        val value = connection.getHeaderField("subscription-userinfo") ?: return UserInfo()

        var upload: Long? = null
        var download: Long? = null
        var total: Long? = null
        var expire: Long? = null

        for (part in value.split(";")) {
            val trimmed = part.trim()
            val kv = trimmed.split("=", limit = 2)
            if (kv.size != 2) continue
            val key = kv[0].trim()
            val v = kv[1].trim()

            when (key) {
                "upload" -> upload = v.toLongOrNull()
                "download" -> download = v.toLongOrNull()
                "total" -> total = v.toLongOrNull()
                "expire" -> expire = v.toLongOrNull()?.let { it * 1000 }
            }
        }

        return UserInfo(upload, download, total, expire)
    }
}
