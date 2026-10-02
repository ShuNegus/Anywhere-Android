package com.argsment.anywhere.vpn.turn

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue

class TurnStreamTest {

    private class QueueIo : TurnStreamIo {
        val incoming = LinkedBlockingQueue<Any>()
        val written: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var closed = false
        override fun read(max: Int): ByteArray = when (val next = incoming.take()) {
            is ByteArray -> next
            is Exception -> throw next
            else -> error("unexpected")
        }
        override fun write(data: ByteArray) { written += String(data) }
        override fun close() { closed = true; incoming.put(Exception("io: read/write on closed pipe")) }
    }

    @Test
    fun skipsEmptyChunksAndMapsEofToNull() = runBlocking {
        val io = QueueIo()
        val stream = TurnStream(io, "t")
        io.incoming.put(ByteArray(0))
        io.incoming.put("hi".toByteArray())
        io.incoming.put(Exception("EOF"))
        assertArrayEquals("hi".toByteArray(), stream.read())
        assertNull(stream.read())
    }

    @Test
    fun otherReadErrorsSurface() = runBlocking {
        val io = QueueIo()
        val stream = TurnStream(io, "t")
        io.incoming.put(Exception("connection reset"))
        try {
            stream.read()
            fail("expected an error")
        } catch (e: TurnError.Io) {
            assertEquals("connection reset", e.message)
        }
    }

    @Test
    fun asyncAndAwaitedWritesKeepTheirOrder() = runBlocking {
        val io = QueueIo()
        val stream = TurnStream(io, "t")
        stream.writeAsync("1".toByteArray())
        stream.writeAsync("2".toByteArray())
        stream.write("3".toByteArray())
        assertEquals(listOf("1", "2", "3"), io.written.toList())
    }

    @Test
    fun closeUnblocksAPendingReadAndRejectsWrites() = runBlocking {
        val io = QueueIo()
        val connection = TurnProxyConnection(TurnStream(io, "t")) {}
        assertTrue(connection.isConnected)
        connection.cancel()
        assertNull(connection.receive())
        try {
            connection.send("x".toByteArray())
            fail("expected StreamClosed")
        } catch (_: TurnError.StreamClosed) {
        }
        Thread.sleep(100)
        assertTrue(io.closed)
    }
}
