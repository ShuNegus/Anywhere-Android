package com.argsment.anywhere.vpn.turn

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.CancellationSignal
import com.argsment.anywhere.vpn.util.AnywhereLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executor
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.resume

private val logger = AnywhereLogger("ConnectivityProbe")

/** What the direct path can currently reach (iOS `ConnectivityVerdict`). */
enum class ConnectivityVerdict(val rawValue: String) {
    /** Nothing answered — the device has no usable network at all. */
    OFFLINE("offline"),
    /** Domestic hosts answer, the open internet does not: censored network. */
    BLOCKED("blocked"),
    /** The open internet answers; no bypass needed. */
    OPEN("open");

    companion object {
        fun fromRaw(raw: String?): ConnectivityVerdict? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * How much patience a probe gets (iOS `ProbeProfile`).
 *
 * The pre-flight runs with the user watching the connect button, so it answers fast even
 * at the cost of an occasional wrong "unreachable". The in-tunnel auto-pilot has nobody
 * waiting, and a false `BLOCKED` there is expensive — it moves every flow onto the
 * relay — so it waits longer.
 */
enum class ProbeProfile(val timeoutMs: Long) {
    PREFLIGHT(2_000),
    BACKGROUND(5_000),
}

/** One host's outcome, kept so a decision can be logged with the evidence behind it. */
data class HostResult(val host: String, val reachable: Boolean, val elapsedMs: Long)

data class ProbeOutcome(val verdict: ConnectivityVerdict, val results: List<HostResult>)

/**
 * Classifies the current network by attempting real TLS handshakes. Mirrors iOS
 * `ConnectivityProbe` (spec §7.2).
 *
 * A TCP connect is not enough: censoring middleboxes let the SYN/SYN-ACK through and only
 * kill the flow after seeing the SNI in the ClientHello, so a connect-only probe reports
 * "reachable" precisely in the networks that need the bypass. Every probe here runs a full
 * handshake (system certificate validation + hostname check) with the real server name.
 *
 * **Which network is measured.** iOS probes from the app (before the tunnel) and from the
 * Network Extension, whose sockets never enter its own tun. On Android the app process is
 * excluded from its own VPN (`addDisallowedApplication`), so its sockets already bypass
 * the tun; on top of that every socket and DNS query here is explicitly bound to the
 * underlying non-VPN network ([underlyingNetwork]), so the result never measures our own
 * tunnel even if that exclusion is ever dropped.
 */
object ConnectivityProbe {

    /** Hosts that stay reachable inside a censored network. */
    val HOME_HOSTS = listOf("vk.ru", "ya.ru")

    /**
     * Hosts a censored network blocks. Several: one unlucky handshake against a single
     * host used to be enough to call an ordinary Wi-Fi "censored".
     */
    val OPEN_HOSTS = listOf("google.com", "cloudflare.com", "gstatic.com")

    const val PROBE_PORT = 443

    /** Pure decision, split out so it can be tested without the network. */
    fun verdict(homeReachable: Boolean, openReachable: Boolean): ConnectivityVerdict = when {
        openReachable -> ConnectivityVerdict.OPEN
        homeReachable -> ConnectivityVerdict.BLOCKED
        else -> ConnectivityVerdict.OFFLINE
    }

    /**
     * Folds per-host results into the verdict: a single open host answering is enough
     * (the others may be down, blocked by the operator or slow).
     */
    fun verdict(results: List<HostResult>): ConnectivityVerdict = verdict(
        homeReachable = results.any { it.reachable && it.host in HOME_HOSTS },
        openReachable = results.any { it.reachable && it.host in OPEN_HOSTS },
    )

    suspend fun classify(context: Context, profile: ProbeProfile): ConnectivityVerdict =
        classifyDetailed(context, profile).verdict

    /** The verdict plus per-host evidence, in the order `OPEN_HOSTS + HOME_HOSTS`. */
    suspend fun classifyDetailed(context: Context, profile: ProbeProfile): ProbeOutcome {
        val network = underlyingNetwork(context)
        return classifyDetailed(profile) { host, timeoutMs -> reachable(network, host, timeoutMs) }
    }

    /**
     * All hosts in parallel through [reach]; never throws — any failure counts as
     * "not reachable". Split out so the fan-out can be tested with a fake.
     */
    suspend fun classifyDetailed(
        profile: ProbeProfile,
        reach: suspend (host: String, timeoutMs: Long) -> Boolean,
    ): ProbeOutcome = coroutineScope {
        val results = (OPEN_HOSTS + HOME_HOSTS).map { host ->
            async { measure(host) { runCatching { reach(host, profile.timeoutMs) }.getOrDefault(false) } }
        }.awaitAll()
        val verdict = verdict(results)
        logger.debug("[ConnectivityProbe] ${results.joinToString { "${it.host}=${it.reachable}" }} → ${verdict.rawValue}")
        ProbeOutcome(verdict, results)
    }

    private suspend fun measure(host: String, block: suspend () -> Boolean): HostResult {
        val started = System.nanoTime() // monotonic
        val ok = block()
        return HostResult(host, ok, (System.nanoTime() - started) / 1_000_000)
    }

    // MARK: - One host

    /**
     * Fresh DNS, then a full TLS handshake carrying [host] as the SNI, under a deadline of
     * [timeoutMs] covering connect + handshake. DNS is outside that deadline like on iOS,
     * but bounded by its own [timeoutMs] so a hung resolver cannot stall the probe.
     */
    suspend fun reachable(network: Network?, host: String, timeoutMs: Long): Boolean {
        val address = withTimeoutOrNull(timeoutMs) { resolveFresh(network, host) } ?: return false
        return handshake(network, host, address, timeoutMs)
    }

    private suspend fun handshake(network: Network?, host: String, address: InetAddress, timeoutMs: Long): Boolean =
        withContext(Dispatchers.IO) {
            val raw: Socket = network?.socketFactory?.createSocket() ?: Socket()
            var ssl: SSLSocket? = null
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            coroutineScope {
                // Blocking socket calls ignore coroutine cancellation; closing the socket
                // is what actually aborts them at the deadline.
                val watchdog = launch {
                    delay(timeoutMs)
                    closeQuietly(ssl); closeQuietly(raw)
                }
                try {
                    runInterruptible {
                        raw.connect(InetSocketAddress(address, PROBE_PORT), timeoutMs.toInt())
                        val remaining = ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)
                        raw.soTimeout = remaining.toInt()
                        val socket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                            .createSocket(raw, host, PROBE_PORT, true) as SSLSocket
                        ssl = socket
                        socket.sslParameters = socket.sslParameters.apply {
                            serverNames = listOf(SNIHostName(host))
                            endpointIdentificationAlgorithm = "HTTPS"
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                applicationProtocols = arrayOf("http/1.1")
                            }
                        }
                        socket.startHandshake()
                        // Belt and braces: not every provider honours the endpoint
                        // identification algorithm on a layered socket.
                        HttpsURLConnection.getDefaultHostnameVerifier().verify(host, socket.session) &&
                            System.nanoTime() <= deadline
                    }
                } catch (e: Exception) {
                    false
                } finally {
                    watchdog.cancel()
                    closeQuietly(ssl); closeQuietly(raw)
                }
            }
        }

    /**
     * Resolves [host] on [network], skipping the resolver cache where the platform allows
     * (iOS: `resolveHost(forceFresh: true)`) — a stale answer would be about the previous
     * network. `null` = not resolvable, which counts as unreachable.
     */
    private suspend fun resolveFresh(network: Network?, host: String): InetAddress? {
        if (network != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                val direct = Executor { it.run() }
                try {
                    DnsResolver.getInstance().query(
                        network, host, DnsResolver.FLAG_NO_CACHE_LOOKUP, direct, signal,
                        object : DnsResolver.Callback<List<InetAddress>> {
                            override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                                if (cont.isActive) cont.resume(pickAddress(answer))
                            }

                            override fun onError(error: DnsResolver.DnsException) {
                                if (cont.isActive) cont.resume(null)
                            }
                        }
                    )
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
        return withContext(Dispatchers.IO) {
            runInterruptible {
                runCatching {
                    pickAddress((network?.getAllByName(host) ?: InetAddress.getAllByName(host)).toList())
                }.getOrNull()
            }
        }
    }

    /** IPv4 first: most of our users' networks (and nodes) have no working IPv6 egress. */
    private fun pickAddress(answer: List<InetAddress>): InetAddress? =
        answer.firstOrNull { it is java.net.Inet4Address } ?: answer.firstOrNull()

    private fun closeQuietly(socket: Socket?) {
        try { socket?.close() } catch (_: Exception) {}
    }

    /**
     * The physical network the probe must measure: the default network when it is not a
     * VPN (the app is excluded from its own VPN, so this is the underlying one), otherwise
     * the first non-VPN network with internet. `null` → unbound sockets (no network at
     * all, in which case DNS fails and the verdict is `OFFLINE`).
     */
    @Suppress("DEPRECATION")
    fun underlyingNetwork(context: Context): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        fun usable(network: Network): Boolean {
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        cm.activeNetwork?.let { if (usable(it)) return it }
        return cm.allNetworks.firstOrNull { usable(it) }
    }
}
