package com.argsment.anywhere.ui.home

import com.argsment.anywhere.data.model.ProxyChain
import com.argsment.anywhere.data.model.ProxyConfiguration
import com.argsment.anywhere.data.model.Subscription
import com.argsment.anywhere.data.network.LatencyResult
import java.util.UUID

/** A row of the home-screen server list: a configuration or a chain. */
data class ServerListItem(val id: UUID, val name: String)

/**
 * A card of rows. [subscriptionId] is set only for subscription sections — only those get
 * a header with a delete affordance (spec §11.5).
 */
data class ServerListSectionModel(
    val id: UUID,
    val header: String?,
    val subscriptionId: UUID?,
    val items: List<ServerListItem>,
)

/**
 * The home-screen server list. Port of iOS `HomeView.pickerSections` and the stores'
 * picker items (`standalonePickerItems`, `ChainStore.pickerItems`,
 * `SubscriptionStore.pickerSections`), plus `ServerListSection.splitFlag`.
 */
object ServerListModel {

    /** Stable ids, so the list does not treat the sections as new on every update. */
    val STANDALONE_SECTION_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val CHAINS_SECTION_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")

    /**
     * Standalone configurations (no header), then chains under [chainsHeader], then one
     * section per subscription that has configurations, in the stores' order.
     */
    fun sections(
        configurations: List<ProxyConfiguration>,
        chains: List<ProxyChain>,
        subscriptions: List<Subscription>,
        chainsHeader: String,
    ): List<ServerListSectionModel> {
        val result = mutableListOf<ServerListSectionModel>()

        val standalone = configurations.filter { it.subscriptionId == null }
        if (standalone.isNotEmpty()) {
            result += ServerListSectionModel(
                id = STANDALONE_SECTION_ID,
                header = null,
                subscriptionId = null,
                items = standalone.map { ServerListItem(it.id, it.name) },
            )
        }

        // A chain is listed only when every hop still exists and it has at least two
        // (the same rule as `resolveChain`).
        val known = configurations.mapTo(HashSet()) { it.id }
        val usableChains = chains.filter { chain ->
            chain.proxyIds.size >= 2 && chain.proxyIds.all { it in known }
        }
        if (usableChains.isNotEmpty()) {
            result += ServerListSectionModel(
                id = CHAINS_SECTION_ID,
                header = chainsHeader,
                subscriptionId = null,
                items = usableChains.map { ServerListItem(it.id, it.name) },
            )
        }

        for (subscription in subscriptions) {
            val configs = configurations.filter { it.subscriptionId == subscription.id }
            if (configs.isEmpty()) continue
            result += ServerListSectionModel(
                id = subscription.id,
                header = subscription.name,
                subscriptionId = subscription.id,
                items = configs.map { ServerListItem(it.id, it.name) },
            )
        }
        return result
    }

    /** A selected chain resolves into a configuration with a new id; the row matches the chain's id. */
    fun selectedRowId(selectedChainId: UUID?, selectedConfigId: UUID?): UUID? =
        selectedChainId ?: selectedConfigId

    /** Chain latencies live in their own map; the list keys everything by row id. Configurations win. */
    fun mergedLatencies(
        configurations: Map<UUID, LatencyResult>,
        chains: Map<UUID, LatencyResult>,
    ): Map<UUID, LatencyResult> = chains + configurations

    fun isMeasuring(
        configurations: Map<UUID, LatencyResult>,
        chains: Map<UUID, LatencyResult>,
    ): Boolean = configurations.values.any { it == LatencyResult.Testing } ||
        chains.values.any { it == LatencyResult.Testing }

    /**
     * Separates a leading emoji flag (a pair of regional indicators, U+1F1E6…U+1F1FF) from
     * the name: `"🇺🇸 Los Angeles"` → `("🇺🇸", "Los Angeles")`. No flag → `("", name)`.
     */
    fun splitFlag(name: String): Pair<String, String> {
        if (name.isEmpty()) return "" to name
        val first = name.codePointAt(0)
        if (!isRegionalIndicator(first)) return "" to name
        val firstEnd = Character.charCount(first)
        if (firstEnd >= name.length) return "" to name
        val second = name.codePointAt(firstEnd)
        if (!isRegionalIndicator(second)) return "" to name
        val flagEnd = firstEnd + Character.charCount(second)
        return name.substring(0, flagEnd) to name.substring(flagEnd).trim()
    }

    private fun isRegionalIndicator(codePoint: Int) = codePoint in 0x1F1E6..0x1F1FF
}

/**
 * Swipe-left-to-delete on a subscription header (iOS `ServerListSection`, spec §11.5).
 * Offsets are in dp, negative to the left.
 */
object SubscriptionSwipe {
    /** How far the row slides to reveal the delete button. */
    const val REVEAL_WIDTH = 72f
    /** Dragging past this deletes without a second tap. */
    const val COMMIT_WIDTH = 160f

    enum class Outcome { DELETE, OPEN, CLOSE }

    /** The row's offset: the open/closed base plus the live drag, clamped to `[-COMMIT, 0]`. */
    fun offset(isOpen: Boolean, dragTranslation: Float): Float {
        val base = if (isOpen) -REVEAL_WIDTH else 0f
        return (base + dragTranslation).coerceIn(-COMMIT_WIDTH, 0f)
    }

    fun settle(offset: Float): Outcome = when {
        offset <= -COMMIT_WIDTH -> Outcome.DELETE
        offset <= -REVEAL_WIDTH / 2 -> Outcome.OPEN
        else -> Outcome.CLOSE
    }
}
