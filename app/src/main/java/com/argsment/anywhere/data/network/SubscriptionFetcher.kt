package com.argsment.anywhere.data.network

import android.util.Base64
import com.argsment.anywhere.data.model.ProxyConfiguration
import com.argsment.anywhere.turn.TurnEnvironment
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
         * TURN relays advertised by the same endpoint, fetched separately. `null` when the
         * subscription serves no sing-box document with a `turn` block.
         */
        val turn: TurnMetadata? = null
    )

    sealed class FetchError(message: String) : Exception(message) {
        class InvalidUrl : FetchError("Invalid subscription URL.")
        class NoConfigurations : FetchError("No valid configurations found in subscription.")
        class NetworkError(message: String) : FetchError("Network error: $message")
    }

    /**
     * Remnawave renders a sing-box document — the only representation that carries the
     * `turn` block — when the client identifies itself as sing-box. Same string as iOS
     * (`SubscriptionFetcher.singBoxUserAgent`); the server matches on `sing-box`.
     */
    const val SING_BOX_USER_AGENT = "SFI/1.0 (iOS; sing-box 1.14.0)"

    private const val TURN_METADATA_TIMEOUT_MS = 15_000

    /**
     * @param includeTurnMetadata whether to make the second, sing-box-flavoured request for
     *   the `turn` block. Defaults to the `turnFeatureEnabled` master switch.
     */
    suspend fun fetch(
        urlString: String,
        allowInsecure: Boolean = CertificatePolicy.allowInsecure,
        remnawaveHWID: String? = null,
        includeTurnMetadata: Boolean = TurnEnvironment.settings?.featureEnabled ?: true
    ): Result {
        val result = fetchConfigurations(urlString, allowInsecure, remnawaveHWID)
        if (!includeTurnMetadata) return result
        // Second, optional request to the same URL. Never fatal — see fetchTurnMetadata.
        val turn = fetchTurnMetadata(urlString, allowInsecure, remnawaveHWID)
        return result.copy(turn = turn)
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
     * Asks the same endpoint for its sing-box rendering and keeps only the `turn` block
     * (spec §4.3). Entirely best-effort: a network error, an HTTP error, a non-JSON body or
     * a missing/empty `turn` block all yield `null`, and the subscription is added exactly
     * as before.
     */
    private suspend fun fetchTurnMetadata(
        urlString: String,
        allowInsecure: Boolean,
        remnawaveHWID: String?
    ): TurnMetadata? = withContext(Dispatchers.IO) {
        try {
            val url = URL(urlString)
            val connection = openConnection(
                url, SING_BOX_USER_AGENT, remnawaveHWID, allowInsecure, TURN_METADATA_TIMEOUT_MS
            )
            try {
                if (connection.responseCode !in 200..299) return@withContext null
                val body = connection.inputStream.readBytes()
                val metadata = TurnMetadata.extractFromSingBoxJson(body) ?: return@withContext null
                logger.debug("TURN metadata: ${metadata.servers.size} server(s)")
                metadata
            } finally {
                connection.disconnect()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.debug("TURN metadata probe failed: ${e.message}")
            null
        }
    }

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
