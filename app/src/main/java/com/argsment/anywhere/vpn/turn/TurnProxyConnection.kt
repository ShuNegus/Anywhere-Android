package com.argsment.anywhere.vpn.turn

import com.argsment.anywhere.vpn.protocol.ProxyConnection
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A [ProxyConnection] backed by one TURN stream. Mirrors iOS `TurnProxyConnection`.
 *
 * It is never the outbound protocol itself — it is handed to `VlessClient`/
 * `TrojanClient`/`ShadowsocksClient` as their `tunnel`, so the whole proxy stack
 * (TLS/Reality/WS/HTTPUpgrade/gRPC/XHTTP) is built on top of it, the analogue of a
 * sing-box `detour`. TURN carries a byte stream; UDP is not tunnelled through it.
 *
 * Unlike iOS there is no `deinit` safety net: the owner must [cancel] it (the protocol
 * clients do so through `TunneledTransport.forceCancel`).
 */
class TurnProxyConnection(
    private val stream: TurnStream,
    private val onClose: () -> Unit,
) : ProxyConnection() {

    private val cancelled = AtomicBoolean(false)

    override val isConnected: Boolean
        get() = !cancelled.get() && stream.isOpen

    override suspend fun sendRaw(data: ByteArray) {
        if (!isConnected) throw TurnError.StreamClosed()
        stream.write(data)
    }

    override fun sendRawAsync(data: ByteArray) {
        if (!isConnected) return
        stream.writeAsync(data)
    }

    override suspend fun receiveRaw(): ByteArray? {
        if (!isConnected) return null
        return stream.read()
    }

    override fun cancel() {
        if (!cancelled.compareAndSet(false, true)) return
        stream.close()
        onClose()
    }
}
