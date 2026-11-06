package com.argsment.anywhere.turn

import androidx.annotation.StringRes
import com.argsment.anywhere.R

/*
 * The "backup route" (TURN) status shown next to a subscription's name and in the
 * connection graph. Pure functions only; the view model feeds them.
 */

/** How the last `<subscription>/turn` request ended. Persisted by raw value. */
enum class TurnFetchOutcome(val rawValue: String) {
    /** 2xx with a `turn` and/or `push` block: our server answered. */
    OK("ok"),
    /** 404: the server does not know the subscription (deleted, expired, wrong link). */
    NOT_FOUND("not_found"),
    /** Network error, timeout, any other HTTP status. */
    FAILED("failed");

    companion object {
        fun fromRaw(raw: String?): TurnFetchOutcome? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * Why the backup route cannot be used. Every value but [SERVER_NOT_COVERED] is shown in
 * red. The VK link being rejected at runtime is not listed: the core does not tell it
 * apart from other handshake failures, so it ends up as [RUNTIME_FAILED].
 */
enum class BypassReason(
    @StringRes val collapsedRes: Int,
    @StringRes val nodeCaptionRes: Int,
) {
    LOAD_FAILED(R.string.bypass_reason_load_failed, R.string.bypass_node_load_failed),
    SUBSCRIPTION_NOT_FOUND(R.string.bypass_reason_not_found, R.string.bypass_node_not_found),
    NO_VK_LINK(R.string.bypass_reason_no_vk_link, R.string.bypass_node_no_vk_link),
    CAPTCHA_FAILED(R.string.bypass_reason_captcha_failed, R.string.bypass_node_captcha_failed),
    RUNTIME_FAILED(R.string.bypass_reason_runtime_failed, R.string.bypass_node_runtime_failed),
    DISABLED_IN_SETTINGS(R.string.bypass_reason_disabled, R.string.bypass_node_disabled),
    /** Not red: the subscription has a backup route, just not for the selected server. */
    SERVER_NOT_COVERED(R.string.bypass_reason_server_not_covered, R.string.bypass_node_server_not_covered);

    val isError: Boolean get() = this != SERVER_NOT_COVERED

    /** Found while connecting rather than from the subscription. */
    val isRuntime: Boolean get() = this == CAPTCHA_FAILED || this == RUNTIME_FAILED
}

/** The icon next to a subscription's name. */
sealed class SubscriptionBypassState {
    /** The subscription has a backup route. */
    data object Available : SubscriptionBypassState()
    /** Our server said: this subscription has no backup route. */
    data object None : SubscriptionBypassState()
    /** Nothing is known (another provider, or not refreshed since the app update): no icon. */
    data object Unknown : SubscriptionBypassState()
    data class Error(val reason: BypassReason) : SubscriptionBypassState()
}

object BypassStatus {

    /**
     * The icon state of one subscription.
     *
     * @param metadata the stored `turn` block (kept across a failed refresh).
     * @param outcome how the last `/turn` request ended; `null` before the first refresh
     *   that recorded it.
     * @param knownOurs the subscription has answered `/turn` before (or has a revision URL),
     *   so a 404/failure is ours to report; for anyone else's subscription nothing is shown.
     * @param manualVkLink the user's own VK link, which replaces a missing one.
     */
    fun subscriptionState(
        metadata: TurnMetadata?,
        outcome: TurnFetchOutcome?,
        knownOurs: Boolean,
        manualVkLink: String,
    ): SubscriptionBypassState {
        if (metadata != null && metadata.servers.isNotEmpty()) {
            val linkMissing = metadata.vkLinkRequired != false &&
                metadata.vkLink.isNullOrBlank() && manualVkLink.isBlank()
            return if (linkMissing) SubscriptionBypassState.Error(BypassReason.NO_VK_LINK)
            else SubscriptionBypassState.Available
        }
        return when (outcome) {
            TurnFetchOutcome.OK -> SubscriptionBypassState.None
            TurnFetchOutcome.NOT_FOUND ->
                if (knownOurs) SubscriptionBypassState.Error(BypassReason.SUBSCRIPTION_NOT_FOUND)
                else SubscriptionBypassState.Unknown
            TurnFetchOutcome.FAILED ->
                if (knownOurs) SubscriptionBypassState.Error(BypassReason.LOAD_FAILED)
                else SubscriptionBypassState.Unknown
            null -> if (knownOurs) SubscriptionBypassState.None else SubscriptionBypassState.Unknown
        }
    }

    /**
     * Why the backup route cannot carry the selected server, when it is needed; `null`
     * when it can.
     *
     * @param disabledInSettings the user turned the backup route off (mode "Off", not forced).
     * @param subscriptionState the selected server's subscription; `null` for a standalone server.
     * @param outcome that subscription's last `/turn` outcome, used when nothing is stored.
     * @param hasUsableRelay a usable relay is known for the server's host.
     * @param hasVkLink a VK link is known (shipped or the user's own).
     */
    fun issue(
        disabledInSettings: Boolean,
        subscriptionState: SubscriptionBypassState?,
        outcome: TurnFetchOutcome?,
        hasUsableRelay: Boolean,
        hasVkLink: Boolean,
    ): BypassReason? {
        if (disabledInSettings) return BypassReason.DISABLED_IN_SETTINGS
        if (subscriptionState is SubscriptionBypassState.Error) return subscriptionState.reason
        if (!hasUsableRelay) {
            return when (outcome) {
                TurnFetchOutcome.FAILED -> BypassReason.LOAD_FAILED
                TurnFetchOutcome.NOT_FOUND -> BypassReason.SUBSCRIPTION_NOT_FOUND
                else -> BypassReason.SERVER_NOT_COVERED
            }
        }
        if (!hasVkLink) return BypassReason.NO_VK_LINK
        return null
    }
}
