package com.argsment.anywhere.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.argsment.anywhere.turn.TurnEnvironment
import com.argsment.anywhere.vpn.util.AnywhereLogger
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Hourly background pass of [SubscriptionAutoRefresh]. Its requests leave outside the
 * tunnel: the app excludes itself from its own VPN.
 */
class SubscriptionRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        TurnEnvironment.install(applicationContext)
        try {
            SubscriptionAutoRefresh.runPass(applicationContext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warning("Auto-refresh pass failed: ${e.message}")
        }
        // Never retry/fail: the next periodic run is the retry.
        return Result.success()
    }

    companion object {
        private val logger = AnywhereLogger("SubscriptionRefreshWorker")
        const val UNIQUE_NAME = "subscription-auto-refresh"

        /** Idempotent: an already scheduled job is kept (and keeps its timing). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SubscriptionRefreshWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }
}
