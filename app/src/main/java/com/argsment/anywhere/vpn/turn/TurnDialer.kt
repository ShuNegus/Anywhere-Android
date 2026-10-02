package com.argsment.anywhere.vpn.turn

import com.argsment.anywhere.turn.TurnDefaults
import com.argsment.anywhere.turn.TurnServerInfo
import com.argsment.anywhere.vpn.util.AnywhereLogger
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.anywhere.turn.anywhere.Anywhere
import app.anywhere.turn.anywhere.Dialer
import app.anywhere.turn.anywhere.LogSink
import app.anywhere.turn.anywhere.Stream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private val logger = AnywhereLogger("TurnDialer")

/**
 * A pool of TURN sessions to one relay that hands out streams on demand — the face the
 * registry sees. [GoTurnDialer] is the real one; tests substitute fakes.
 */
interface TurnDialerHandle {
    val host: String
    /** Sessions currently held open to the relay; 0 once closed. */
    val sessionCount: Int
    /** Raw [TurnPhase]; 0 once closed. */
    val phase: Int
    /** Legacy counters, superseded by [coreStatus]. */
    val credentialSets: Int
    val credentialSetsPassed: Int
    val captchaHits: Int
    /** Full core snapshot; `null` once closed or for an unknown format. */
    val coreStatus: TurnCoreStatus?
    /** Streams handed out and not yet closed. */
    val openStreamCount: Int

    /** Suspends until at least one session is up. Cheap once the pool is warm. */
    suspend fun waitReady(timeoutMs: Long? = null)

    /** Opens a fresh multiplexed stream over an established session. */
    fun openStream(): TurnStream

    /** Balances [openStream]; used only for [openStreamCount]. */
    fun noteStreamClosed()

    /** Idempotent. */
    fun close()
}

/** Builds dialers; throws [TurnError] when the server or link cannot be used. */
fun interface TurnDialerFactory {
    fun create(
        server: TurnServerInfo,
        vkLink: String,
        defaults: TurnDefaults?,
        peers: Int,
        manualCaptcha: Boolean,
    ): TurnDialerHandle
}

/**
 * Kotlin face of the Go `clientcore.Dialer` (gomobile `app.anywhere.turn.anywhere.Dialer`).
 * Mirrors iOS `TurnDialer`.
 *
 * Construction is cheap — the Go side starts maintaining sessions in the background (VK
 * login and possibly a captcha begin right away) and returns at once — so the pool only
 * really exists after [waitReady] succeeds.
 */
class GoTurnDialer private constructor(
    override val host: String,
    private val dialer: Dialer,
    private val readyTimeoutMs: Long,
) : TurnDialerHandle {

    private val closed = AtomicBoolean(false)
    private val streamCounter = AtomicInteger(0)
    private val streamSerial = AtomicInteger(0)

    override val sessionCount: Int
        get() = if (closed.get()) 0 else dialer.sessionCount().toInt()

    override val phase: Int
        get() = if (closed.get()) 0 else dialer.phase().toInt()

    override val credentialSets: Int
        get() = if (closed.get()) 0 else dialer.credentialSets().toInt()

    override val credentialSetsPassed: Int
        get() = if (closed.get()) 0 else dialer.credentialSetsPassed().toInt()

    override val captchaHits: Int
        get() = if (closed.get()) 0 else dialer.captchaHits().toInt()

    override val coreStatus: TurnCoreStatus?
        get() = if (closed.get()) null else TurnCoreStatus.decode(dialer.statusJSON())

    override val openStreamCount: Int
        get() = streamCounter.get()

    override suspend fun waitReady(timeoutMs: Long?) {
        if (closed.get()) throw TurnError.StreamClosed()
        val ms = maxOf(1L, timeoutMs ?: readyTimeoutMs)
        // Blocking Go call; keep it off the shared coroutine pools.
        withContext(TurnDispatchers.blocking) {
            try {
                dialer.waitReady(ms)
            } catch (e: Exception) {
                throw TurnError.NotReady(e)
            }
        }
        logger.debug("TURN ready ($host): Go heap ${Anywhere.heapInUse() / TurnMemoryPolicy.MIB} MiB")
    }

    override fun openStream(): TurnStream {
        if (closed.get()) throw TurnError.StreamClosed()
        val raw = try {
            dialer.dial()
        } catch (e: Exception) {
            throw TurnError.Io(e)
        }
        streamCounter.incrementAndGet()
        return TurnStream(GoTurnStreamIo(raw), "$host-${streamSerial.incrementAndGet()}")
    }

    override fun noteStreamClosed() {
        streamCounter.decrementAndGet()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        TurnDispatchers.scope.launch {
            runCatching { dialer.close() }
            logger.debug("TURN dialer closed for $host")
        }
    }

    companion object {
        /** Default `WaitReady` budget when the subscription gives no `ready_timeout`. */
        const val DEFAULT_READY_TIMEOUT_SEC = 30

        /** The production [TurnDialerFactory]. */
        val factory = TurnDialerFactory { server, vkLink, defaults, peers, manualCaptcha ->
            create(server, vkLink, defaults, peers, manualCaptcha)
        }

        fun create(
            server: TurnServerInfo,
            vkLink: String,
            defaults: TurnDefaults?,
            peers: Int,
            manualCaptcha: Boolean,
        ): GoTurnDialer {
            if (!server.isUsable) throw TurnError.UnsupportedServer(server.host)
            val link = vkLink.trim()
            if (link.isEmpty()) throw TurnError.MissingVKLink()

            val config = TurnDialerConfig.make(server, link, defaults, peers, manualCaptcha)
            val dialer = try {
                Anywhere.newDialer(config.toString(), TurnLogRelay(server.host))
            } catch (e: UnsatisfiedLinkError) {
                throw TurnError.Unavailable(e)
            } catch (e: NoClassDefFoundError) {
                throw TurnError.Unavailable(e)
            } catch (e: Exception) {
                throw TurnError.Io(e)
            } ?: throw TurnError.UnsupportedServer(server.host)

            // One line per dialer. Credential sets (and their captchas) are fetched by the
            // core as the relay fills up; the `[Creds]` lines in the core log show each.
            logger.info("TURN dialer ${server.host}: num_streams=${TurnDialerConfig.sessionCount(peers)}, credential sets on demand")
            val readyTimeoutSec = defaults?.readyTimeout ?: DEFAULT_READY_TIMEOUT_SEC
            return GoTurnDialer(server.host, dialer, readyTimeoutSec * 1000L)
        }
    }
}

/** One gomobile stream. Go reports end-of-stream as an error with text "EOF". */
private class GoTurnStreamIo(private val stream: Stream) : TurnStreamIo {
    override fun read(max: Int): ByteArray = stream.read(max.toLong()) ?: ByteArray(0)

    override fun write(data: ByteArray) {
        stream.write(data)
    }

    override fun close() {
        stream.close()
    }
}

/**
 * Forwards the Go core's log lines (`[VK Auth]`, `[session N]`, `[ALLOC]`, `[Creds]` …)
 * into logcat (debug) and [TurnCoreLogFile].
 */
private class TurnLogRelay(private val host: String) : LogSink {
    override fun onLog(msg: String?) {
        if (msg.isNullOrEmpty()) return
        logger.debug("[turn $host] $msg")
        TurnCoreLogFile.append("[$host] $msg")
    }
}
