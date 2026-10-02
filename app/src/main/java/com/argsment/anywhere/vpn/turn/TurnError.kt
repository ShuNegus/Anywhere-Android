package com.argsment.anywhere.vpn.turn

import android.content.Context
import com.argsment.anywhere.R

/**
 * Errors from the TURN transport. Mirrors iOS `TurnError`. The messages are English
 * because they only reach logs; the localized versions for UI live in
 * `strings_turn_core.xml` (`turn_error_*`).
 */
sealed class TurnError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The gomobile core could not be loaded (missing `turn.aar` / `libgojni.so`). */
    class Unavailable(cause: Throwable? = null) :
        TurnError("The TURN transport is not available in this build.", cause)

    class MissingVKLink : TurnError("No VK Calls link is configured for TURN.")

    class UnsupportedServer(val host: String) : TurnError("No TURN relay is available for $host.")

    class NotReady(cause: Throwable? = null) : TurnError("No TURN session could be established.", cause)

    class StreamClosed : TurnError("The TURN stream is closed.")

    class Io(cause: Throwable) : TurnError(cause.message ?: cause.toString(), cause)

    /** The message for UI. */
    fun localizedMessage(context: Context): String = when (this) {
        is Unavailable -> context.getString(R.string.turn_error_unavailable)
        is MissingVKLink -> context.getString(R.string.turn_error_missing_vk_link)
        is UnsupportedServer -> context.getString(R.string.turn_error_unsupported_server, host)
        is NotReady -> context.getString(R.string.turn_error_not_ready)
        is StreamClosed -> context.getString(R.string.turn_error_stream_closed)
        is Io -> message ?: context.getString(R.string.turn_error_not_ready)
    }

    companion object {
        /** Go reports an orderly end of stream as an error whose text is (or ends in) "EOF". */
        fun isEndOfStream(error: Throwable): Boolean {
            val message = error.message ?: return false
            return message == "EOF" || message.endsWith("EOF")
        }
    }
}
