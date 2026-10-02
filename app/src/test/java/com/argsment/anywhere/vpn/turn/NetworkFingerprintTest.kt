package com.argsment.anywhere.vpn.turn

import com.argsment.anywhere.vpn.turn.NetworkFingerprint.InterfaceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkFingerprintTest {

    private fun wifi(ssid: String?, vararg gw: String, name: String = "wlan0") =
        NetworkFingerprint.make(InterfaceKind.WIFI, name, ssid, gw.toList())

    private fun cell(vararg gw: String, name: String = "rmnet0") =
        NetworkFingerprint.make(InterfaceKind.CELLULAR, name, null, gw.toList())

    /** On a named Wi-Fi the SSID settles it: a gateway change is routine, not a new network. */
    @Test
    fun ssidDecidesAndGatewayIsIgnoredWhenNamed() {
        assertEquals(wifi("Home", "192.168.1.1"), wifi("Home", "192.168.1.254"))
        assertEquals(wifi("Home", "192.168.1.1").hashCode(), wifi("Home", "10.0.0.1").hashCode())
        assertNotEquals(wifi("Home", "192.168.1.1"), wifi("Cafe", "192.168.1.1"))
    }

    /** Without a readable SSID the gateway stands in for it. */
    @Test
    fun gatewayDecidesWhenTheSsidIsUnknown() {
        assertEquals(wifi(null, "192.168.1.1"), wifi("<unknown ssid>", "192.168.1.1"))
        assertNotEquals(wifi(null, "192.168.1.1"), wifi(null, "10.0.0.1"))
        assertEquals(cell("10.0.0.1", "fe80::1"), cell("fe80::1", "10.0.0.1")) // sorted
        assertNotEquals(cell("10.0.0.1"), cell("10.0.0.2"))
        assertEquals(cell(), cell())
    }

    /** A named and an unnamed fingerprint of the same Wi-Fi differ — hence SSID before reporting. */
    @Test
    fun namedAndUnnamedDiffer() {
        assertNotEquals(wifi("Home", "192.168.1.1"), wifi(null, "192.168.1.1"))
    }

    @Test
    fun kindAndInterfaceMatter() {
        assertNotEquals(cell("10.0.0.1"), NetworkFingerprint.make(InterfaceKind.WIRED, "rmnet0", null, listOf("10.0.0.1")))
        assertNotEquals(cell("10.0.0.1", name = "rmnet0"), cell("10.0.0.1", name = "rmnet1"))
    }

    /** The SSID is only a Wi-Fi property. */
    @Test
    fun ssidIsDroppedOffWifi() {
        assertNull(NetworkFingerprint.make(InterfaceKind.CELLULAR, "rmnet0", "Home", emptyList()).ssid)
    }

    @Test
    fun ssidNormalization() {
        assertEquals("Home", NetworkFingerprint.normalizeSsid("\"Home\""))
        assertNull(NetworkFingerprint.normalizeSsid("<unknown ssid>"))
        assertNull(NetworkFingerprint.normalizeSsid(""))
        assertNull(NetworkFingerprint.normalizeSsid(null))
        assertEquals("Home", NetworkFingerprint.normalizeSsid("Home"))
    }

    @Test
    fun descriptionMatchesIos() {
        assertEquals("wifi/wlan0/ssid=Home", wifi("Home", "1.1.1.1").toString())
        assertEquals("cellular/rmnet0/gw=10.0.0.1", cell("10.0.0.1").toString())
    }
}
