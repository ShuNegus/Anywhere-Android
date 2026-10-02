package com.argsment.anywhere.vpn.turn

import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.os.Build

/**
 * The identity of the network the device is on, as far as the auto-pilot cares. Mirrors
 * iOS `NetworkFingerprint` (spec §7.3).
 *
 * `NetworkCallback` fires generously — capabilities re-evaluated, signal strength, link
 * properties re-advertised, IPv6 appearing or disappearing — and every one of those
 * events used to trigger a fresh probe on an unchanged network. The fingerprint keeps
 * only the properties that actually say "this is a different network"; IPv6 support,
 * metered-ness and the DNS list are deliberately excluded, because those are exactly the
 * fields that flap while standing still.
 *
 * Equality: kind, interface name and SSID must match; when there is an SSID it settles
 * it, otherwise the gateways (the stand-in for an unreadable SSID) are compared. On a
 * named Wi-Fi a gateway change is routine and must not masquerade as a network change.
 */
class NetworkFingerprint(
    val interfaceKind: InterfaceKind,
    val interfaceName: String?,
    /** Wi-Fi only. */
    val ssid: String?,
    /** Sorted gateways joined by `,`; `null` when there are none. */
    val gatewayFallback: String?,
) {
    enum class InterfaceKind(val rawValue: String) { WIFI("wifi"), CELLULAR("cellular"), WIRED("wired"), OTHER("other") }

    override fun equals(other: Any?): Boolean {
        if (other !is NetworkFingerprint) return false
        if (interfaceKind != other.interfaceKind || interfaceName != other.interfaceName || ssid != other.ssid) {
            return false
        }
        // Both sides have the same SSID; when that is a real name it already settles it.
        if (ssid != null) return true
        return gatewayFallback == other.gatewayFallback
    }

    /** Consistent with [equals]: the gateway only ever narrows equality. */
    override fun hashCode(): Int = listOf(interfaceKind, interfaceName, ssid).hashCode()

    override fun toString(): String {
        val parts = mutableListOf(interfaceKind.rawValue)
        interfaceName?.let { parts += it }
        if (ssid != null) parts += "ssid=$ssid" else gatewayFallback?.let { parts += "gw=$it" }
        return parts.joinToString("/")
    }

    companion object {

        /** Builds the fingerprint from pure values (also the unit-test entry point). */
        fun make(
            kind: InterfaceKind,
            interfaceName: String?,
            rawSsid: String?,
            gateways: List<String>,
        ): NetworkFingerprint = NetworkFingerprint(
            interfaceKind = kind,
            interfaceName = interfaceName,
            ssid = if (kind == InterfaceKind.WIFI) normalizeSsid(rawSsid) else null,
            gatewayFallback = gateways.sorted().takeIf { it.isNotEmpty() }?.joinToString(","),
        )

        /**
         * `WifiInfo.getSSID()` quotes UTF-8 names and answers `<unknown ssid>` without the
         * location permission; the latter means "not readable" and falls back to the
         * gateway, as on iOS when the entitlement is missing.
         */
        fun normalizeSsid(raw: String?): String? {
            var s = raw?.trim() ?: return null
            if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length - 1)
            if (s.isEmpty() || s == WifiUnknownSsid || s == "0x") return null
            return s
        }

        private const val WifiUnknownSsid = "<unknown ssid>"

        fun kind(caps: NetworkCapabilities): InterfaceKind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> InterfaceKind.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> InterfaceKind.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> InterfaceKind.WIRED
            else -> InterfaceKind.OTHER
        }

        /** From a `NetworkCallback`'s view of the underlying network. */
        fun from(caps: NetworkCapabilities, lp: LinkProperties?, wifiInfoFallback: () -> WifiInfo? = { null }): NetworkFingerprint {
            val kind = kind(caps)
            val ssid = if (kind == InterfaceKind.WIFI) {
                val fromCaps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    (caps.transportInfo as? WifiInfo)?.ssid
                } else null
                normalizeSsid(fromCaps) ?: wifiInfoFallback()?.ssid
            } else null
            val gateways = lp?.routes.orEmpty()
                .filter { it.hasGateway() }
                .mapNotNull { it.gateway?.hostAddress }
                .distinct()
            return make(kind, lp?.interfaceName, ssid, gateways)
        }
    }
}
