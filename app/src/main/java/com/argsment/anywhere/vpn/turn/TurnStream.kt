package com.argsment.anywhere.vpn.turn

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Where the blocking gomobile calls run. An unbounded cached pool on purpose: every live
 * stream parks one thread in a blocking `read`, so a bounded pool (`Dispatchers.IO`,
 * 64 threads) would let idle reads starve writes and `waitReady` (spec §5.2). Idle
 * threads die after 60 s.
 */
internal object TurnDispatchers {
    private val counter = AtomicInteger()

    val blocking: CoroutineDispatcher = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "turn-io-${counter.incrementAndGet()}").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    val scope = CoroutineScope(SupervisorJob() + blocking)
}

/**
 * The blocking byte-stream primitive under a [TurnStream]: one gomobile `Stream` in
 * production ([GoTurnStreamIo]), a fake in tests.
 */
interface TurnStreamIo {
    /** Blocks for up to [max] bytes. Throws at end of stream (Go: an "EOF" error). */
    fun read(max: Int): ByteArray
    /** Writes all of [data]. */
    fun write(data: ByteArray)
    /** Idempotent; unblocks a pending [read]. */
    fun close()
}

/**
 * A single multiplexed connection through the TURN tunnel. Mirrors iOS `TurnStream`.
 *
 * Reads run on [TurnDispatchers.blocking]; writes go through a per-stream FIFO queue
 * drained by one coroutine on the same pool, so a stalled read never delays a write and
 * fire-and-forget writes ([writeAsync]) keep their order relative to awaited ones.
 */
class TurnStream(private val io: TurnStreamIo, val label: String) {

    private class WriteOp(val data: ByteArray, val done: CompletableDeferred<Unit>?)

    private val closed = AtomicBoolean(false)
    private val writes = Channel<WriteOp>(Channel.UNLIMITED)
    @Volatile
    private var writeFailure: Throwable? = null

    val isOpen: Boolean get() = !closed.get()

    init {
        TurnDispatchers.scope.launch {
            for (op in writes) {
                val failure = writeFailure
                if (failure != null) {
                    op.done?.completeExceptionally(failure)
                    continue
                }
                try {
                    io.write(op.data)
                    op.done?.complete(Unit)
                } catch (e: Exception) {
                    val error = if (closed.get()) TurnError.StreamClosed() else TurnError.Io(e)
                    writeFailure = error
                    op.done?.completeExceptionally(error)
                }
            }
        }
    }

    /** Reads one chunk. `null` means the peer (or we) closed the stream. */
    suspend fun read(): ByteArray? {
        if (closed.get()) return null
        return withContext(TurnDispatchers.blocking) {
            var chunk: ByteArray
            do {
                chunk = try {
                    io.read(READ_CHUNK_SIZE)
                } catch (e: Exception) {
                    if (closed.get() || TurnError.isEndOfStream(e)) return@withContext null
                    throw TurnError.Io(e)
                }
                // An empty chunk would read as EOF upstream (ProxyConnection.startReceiving).
            } while (chunk.isEmpty())
            chunk
        }
    }

    /** Writes the whole buffer, in order with every earlier write. */
    suspend fun write(data: ByteArray) {
        if (data.isEmpty()) return
        val done = CompletableDeferred<Unit>()
        enqueue(WriteOp(data, done))
        done.await()
    }

    /** Fire-and-forget write; ordered with [write]. Failures surface on the next read/write. */
    fun writeAsync(data: ByteArray) {
        if (data.isEmpty()) return
        runCatching { enqueue(WriteOp(data, null)) }
    }

    private fun enqueue(op: WriteOp) {
        if (closed.get()) throw TurnError.StreamClosed()
        writeFailure?.let { throw it }
        if (writes.trySend(op).isFailure) throw TurnError.StreamClosed()
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        writes.close()
        // Close off the caller's thread: it unblocks a pending read inside Go.
        TurnDispatchers.scope.launch { runCatching { io.close() } }
    }

    companion object {
        /** Crossing the gomobile boundary costs a hop and a copy, so read in large chunks. */
        const val READ_CHUNK_SIZE = 32 * 1024
    }
}
