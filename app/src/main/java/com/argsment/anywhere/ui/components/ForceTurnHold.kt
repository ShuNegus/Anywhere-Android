package com.argsment.anywhere.ui.components

/**
 * The power button's press logic: a short tap toggles the VPN, holding it for
 * [HOLD_MILLIS] connects through TURN whatever the mode says (spec §8, iOS
 * `PowerButton.forceTurnHold` + `didLongPress`).
 *
 * Compose's own long-press fires after ~500 ms, so the gesture is detected by hand; this
 * class holds the decisions so they can be tested without a pointer.
 *
 * Event order for one gesture: [press] → optionally [holdElapsed] → [release].
 */
class ForceTurnHold {

    enum class Release {
        /** A short tap: toggle the VPN. */
        TAP,
        /** Nothing to do: the hold already fired, the gesture was cancelled, or no press was seen. */
        NONE,
    }

    private var pressed = false
    private var didLongPress = false

    /** The finger is down (and the ring should be filling). */
    val isHolding: Boolean get() = pressed && !didLongPress

    fun press() {
        pressed = true
        didLongPress = false
    }

    /**
     * The hold timer ran out while the finger is still down. Returns true when the forced
     * TURN connect should run now; a disabled button does nothing (iOS `guard !isDisabled`),
     * but the tap that follows is still swallowed — the user did not mean a tap.
     */
    fun holdElapsed(enabled: Boolean): Boolean {
        if (!pressed || didLongPress) return false
        didLongPress = true
        return enabled
    }

    /**
     * The finger lifted ([cancelled] = false) or the gesture was taken away, e.g. by the
     * scroll ([cancelled] = true). The tap that ends a completed hold is swallowed.
     */
    fun release(cancelled: Boolean, enabled: Boolean): Release {
        val wasPressed = pressed
        val longPressed = didLongPress
        pressed = false
        didLongPress = false
        return when {
            !wasPressed || cancelled || longPressed || !enabled -> Release.NONE
            else -> Release.TAP
        }
    }

    companion object {
        /** Holding the button this long connects through TURN. */
        const val HOLD_MILLIS = 2_000L
    }
}
