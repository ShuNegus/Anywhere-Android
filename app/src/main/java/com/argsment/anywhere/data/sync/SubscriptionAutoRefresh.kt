package com.argsment.anywhere.data.sync

import android.content.Context
import com.argsment.anywhere.data.model.Subscription
import com.argsment.anywhere.data.repository.AppRepositories
import com.argsment.anywhere.vpn.util.AnywhereLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private val logger = AnywhereLogger("SubscriptionAutoRefresh")

/**
 * Background subscription refresh: every subscription at least once a day, and sooner
 * when the server bumps its revision (an admin forcing everyone onto new servers).
 * Driven by [SubscriptionRefreshWorker] (hourly) and by the app coming to the foreground.
 * Silent: failures only go to the log, the next pass retries.
 */
object SubscriptionAutoRefresh {

    const val MAX_AGE_MS = 24 * 60 * 60 * 1000L
    /** `lastUpdate` this far in the future means the clock was moved back: refresh. */
    private const val FUTURE_SKEW_MS = 60 * 60 * 1000L
    private const val REV_TIMEOUT_MS = 15_000

    /**
     * @param record what the last refresh learned; `null` = never refreshed since this
     *   feature shipped, so the rev URL is unknown — refresh once to learn it.
     * @param serverRev the revision `rev_url` reports now, `null` if unknown/unreachable.
     */
    fun shouldRefresh(lastUpdate: Long?, now: Long, record: SubscriptionSyncRecord?, serverRev: Long?): Boolean {
        if (record == null || lastUpdate == null) return true
        if (now - lastUpdate >= MAX_AGE_MS) return true
        if (lastUpdate - now > FUTURE_SKEW_MS) return true
        return serverRev != null && serverRev > record.rev
    }

    private val passMutex = Mutex()
    @Volatile private var lastPassAt = 0L

    /**
     * Checks every subscription once. [minIntervalMs] skips the pass when one ran that
     * recently (foreground triggers fire on every return to the app).
     * @return how many subscriptions were refreshed.
     */
    suspend fun runPass(context: Context, minIntervalMs: Long = 0): Int {
        if (passMutex.isLocked) return 0
        return passMutex.withLock {
            val started = System.currentTimeMillis()
            if (minIntervalMs > 0 && started - lastPassAt < minIntervalMs) return@withLock 0
            lastPassAt = started

            val subscriptions = AppRepositories.subscriptions(context).getAll()
            val sync = AppRepositories.sync(context)
            val updater = AppRepositories.updater(context)
            var refreshed = 0
            for (subscription in subscriptions) {
                val record = sync.record(subscription.id)
                val serverRev = record?.revUrl?.let { fetchRev(it) }
                if (!shouldRefresh(subscription.lastUpdate, System.currentTimeMillis(), record, serverRev)) continue
                if (refresh(updater, subscription, record, serverRev)) refreshed++
            }
            refreshed
        }
    }

    private suspend fun refresh(
        updater: SubscriptionUpdater,
        subscription: Subscription,
        record: SubscriptionSyncRecord?,
        serverRev: Long?,
    ): Boolean = try {
        updater.update(subscription)
        logger.info("Refreshed \"${subscription.name}\" (rev ${record?.rev} -> ${serverRev ?: "-"})")
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warning("Auto-refresh of \"${subscription.name}\" failed: ${e.message}")
        false
    }

    private suspend fun fetchRev(revUrl: String): Long? = withContext(Dispatchers.IO) {
        try {
            val connection = URL(revUrl).openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", "Anywhere")
            connection.connectTimeout = REV_TIMEOUT_MS
            connection.readTimeout = REV_TIMEOUT_MS
            try {
                if (connection.responseCode !in 200..299) return@withContext null
                SubscriptionPush.parseRev(connection.inputStream.readBytes().toString(Charsets.UTF_8))
            } finally {
                connection.disconnect()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.debug("Revision check failed: ${e.message}")
            null
        }
    }
}
