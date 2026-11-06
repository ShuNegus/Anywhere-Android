package com.argsment.anywhere.data.repository

import android.content.Context
import com.argsment.anywhere.data.sync.SubscriptionSyncStore
import com.argsment.anywhere.data.sync.SubscriptionUpdater
import com.argsment.anywhere.turn.TurnEnvironment
import java.io.File

/**
 * Process-wide instances of the stores a background job and the UI both write to.
 *
 * Each repository keeps its state in memory and writes the whole file back on change, so
 * two instances in one process would silently overwrite each other's edits. The view
 * model and the auto-refresh worker therefore take theirs from here.
 */
object AppRepositories {

    @Volatile private var config: ConfigRepository? = null
    @Volatile private var subscriptions: SubscriptionRepository? = null
    @Volatile private var sync: SubscriptionSyncStore? = null
    @Volatile private var updater: SubscriptionUpdater? = null

    @Synchronized
    fun config(context: Context): ConfigRepository =
        config ?: ConfigRepository(context.applicationContext).also { config = it }

    @Synchronized
    fun subscriptions(context: Context): SubscriptionRepository =
        subscriptions ?: SubscriptionRepository(context.applicationContext).also { subscriptions = it }

    @Synchronized
    fun sync(context: Context): SubscriptionSyncStore =
        sync ?: SubscriptionSyncStore(
            File(context.applicationContext.filesDir, SubscriptionSyncStore.FILE_NAME)
        ).also { sync = it }

    @Synchronized
    fun updater(context: Context): SubscriptionUpdater {
        updater?.let { return it }
        TurnEnvironment.install(context)
        return SubscriptionUpdater(
            configRepository = config(context),
            subscriptionRepository = subscriptions(context),
            turnMetadataStore = { TurnEnvironment.metadataStore },
            syncStore = sync(context),
        ).also { updater = it }
    }
}
