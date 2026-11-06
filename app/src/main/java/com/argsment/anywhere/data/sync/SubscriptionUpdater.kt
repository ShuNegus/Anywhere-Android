package com.argsment.anywhere.data.sync

import com.argsment.anywhere.data.model.ProxyConfiguration
import com.argsment.anywhere.data.model.Subscription
import com.argsment.anywhere.data.network.SubscriptionFetcher
import com.argsment.anywhere.data.repository.ConfigRepository
import com.argsment.anywhere.data.repository.SubscriptionRepository
import com.argsment.anywhere.turn.TurnMetadataStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Refreshes one subscription in place. Shared by the UI (Update buttons) and the
 * background auto-refresh, so both go through the same code and never interleave:
 * refreshes are serialised by [mutex].
 *
 * The repositories are the process-wide instances ([com.argsment.anywhere.data.repository.AppRepositories]),
 * so the UI sees a background refresh through their StateFlows; [updated] additionally
 * tells the view model to re-check the selected configuration.
 */
class SubscriptionUpdater(
    private val configRepository: ConfigRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val turnMetadataStore: () -> TurnMetadataStore?,
    private val syncStore: SubscriptionSyncStore,
    private val fetch: suspend (String) -> SubscriptionFetcher.Result = { SubscriptionFetcher.fetch(it) },
) {
    private val mutex = Mutex()
    private val _updated = MutableSharedFlow<UUID>(extraBufferCapacity = 16)

    /** Ids of subscriptions refreshed successfully, from any caller. */
    val updated: SharedFlow<UUID> = _updated.asSharedFlow()

    /**
     * Throws whatever the fetch throws; nothing is changed then. A subscription deleted
     * while its fetch was in flight is left deleted.
     */
    suspend fun update(subscription: Subscription) = mutex.withLock {
        val result = fetch(subscription.url)
        val current = subscriptionRepository.get(subscription.id) ?: return@withLock

        // Match new configurations against old ones by name to preserve IDs (and routing rules).
        // When multiple configs share the same name, they are matched positionally within that group.
        val oldConfigs = configRepository.getAll().filter { it.subscriptionId == current.id }
        val oldByName = mutableMapOf<String, MutableList<ProxyConfiguration>>()
        for (old in oldConfigs) {
            oldByName.getOrPut(old.name) { mutableListOf() }.add(old)
        }
        val oldNameCursor = mutableMapOf<String, Int>()

        val newConfigs = result.configurations.map { newConfig ->
            val name = newConfig.name
            val cursor = oldNameCursor.getOrDefault(name, 0)
            val group = oldByName[name]
            val id = if (group != null && cursor < group.size) {
                oldNameCursor[name] = cursor + 1
                group[cursor].id
            } else {
                newConfig.id
            }
            newConfig.copy(id = id, subscriptionId = current.id)
        }

        // Atomically replace old configurations with new ones (single StateFlow emission)
        configRepository.replaceBySubscription(current.id, newConfigs)

        // Preserve old metadata when new values are null and respect user-customized names.
        // Built from the stored subscription, not the caller's copy: a rename or collapse
        // made while the fetch was in flight must survive.
        subscriptionRepository.update(
            current.copy(
                lastUpdate = System.currentTimeMillis(),
                name = if (current.isNameCustomized) current.name else (result.name ?: current.name),
                upload = result.upload ?: current.upload,
                download = result.download ?: current.download,
                total = result.total ?: current.total,
                expire = result.expire ?: current.expire
            )
        )

        // Always, including null: a `turn` block withdrawn on the server must stop being used.
        turnMetadataStore()?.setMetadata(result.turn, current.id)
        syncStore.recordRefresh(current.id, result.push)

        _updated.tryEmit(current.id)
    }
}
