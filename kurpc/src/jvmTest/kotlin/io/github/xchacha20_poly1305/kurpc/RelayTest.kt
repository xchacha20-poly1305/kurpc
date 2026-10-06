package io.github.xchacha20_poly1305.kurpc

import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.supervisorScope

public class RelayTest {
    @Test
    public fun byteChannelConnectionCarriesUnaryAndStreams() = test {
        TestServer.tcp().use { server ->
            val connects = AtomicInteger()
            val transport = Transport.Custom {
                connects.incrementAndGet()
                Connection.of(SocketChannel.open(server.socketAddress()))
            }
            Channel(ChannelConfig(transport)).use { channel ->
                assertEquals("relay", channel.unary(ECHO, "relay".encodeToByteArray()).decodeToString())
                // More than the initial demand, and 64 KiB each, so both pipe directions fill up.
                val messages = channel.serverStreaming(COUNT, "40".encodeToByteArray()).toList()
                assertEquals(40, messages.size)
                assertEquals(1, connects.get())
            }
        }
        awaitIdleHandles()
    }

    @Test
    public fun streamPairConnectionCarriesUnary() = test {
        TestServer.tcp().use { server ->
            val transport = Transport.Custom {
                val socket = Socket()
                socket.connect(server.socketAddress())
                Connection.of(socket.getInputStream(), socket.getOutputStream())
            }
            Channel(ChannelConfig(transport)).use { channel ->
                assertEquals("streams", channel.unary(ECHO, "streams".encodeToByteArray()).decodeToString())
            }
        }
        awaitIdleHandles()
    }

    @Test
    public fun connectorIsCalledAgainAfterServerRestart() = test {
        val first = TestServer.tcp()
        val address = first.socketAddress()
        val connects = AtomicInteger()
        val transport = Transport.Custom(
            Connector {
                connects.incrementAndGet()
                Connection.of(SocketChannel.open(address))
            },
        )
        Channel(ChannelConfig(transport)).use { channel ->
            first.use {
                assertEquals("one", channel.unary(ECHO, "one".encodeToByteArray()).decodeToString())
            }
            TestServer.tcp(address.port).use {
                val options = CallOptions(timeout = 10.seconds, waitForReady = true)
                assertEquals("two", channel.unary(ECHO, "two".encodeToByteArray(), options).decodeToString())
            }
            assertTrue(connects.get() >= 2, "connector ran ${connects.get()} times")
        }
        awaitIdleHandles()
    }

    @Test
    public fun connectorFailureIsTransportException() = test {
        val transport = Transport.Custom(Connector { throw IOException("no route to the daemon") })
        Channel(ChannelConfig(transport)).use { channel ->
            val error = assertFailsWith<TransportException> {
                channel.unary(ECHO, ByteArray(0), CallOptions(waitForReady = false))
            }
            assertTrue(error.message!!.contains("no route to the daemon"), error.message)
        }
        awaitIdleHandles()
    }

    @Test
    public fun connectionEofFailsTheCall() = test {
        val closed = CompletableDeferred<Unit>()
        val transport = Transport.Custom(
            Connector {
                object : Connection {
                    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1

                    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) = Unit

                    override suspend fun close() {
                        closed.complete(Unit)
                    }
                }
            },
        )
        Channel(ChannelConfig(transport)).use { channel ->
            assertFailsWith<TransportException> {
                channel.unary(ECHO, ByteArray(0), CallOptions(waitForReady = false))
            }
            closed.await()
        }
        awaitIdleHandles()
    }

    @Test
    public fun closeCancelsAConnectInProgress() = test {
        val connecting = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val transport = Transport.Custom(
            Connector {
                connecting.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            },
        )
        val channel = Channel(ChannelConfig(transport))
        supervisorScope {
            val call = async { channel.unary(ECHO, ByteArray(0)) }
            connecting.await()
            channel.close()
            assertFailsWith<ChannelClosedException> { call.await() }
        }
        cancelled.await()
        awaitIdleHandles()
    }

    @Test
    public fun fileDescriptorTransportHandsTheSocketToKurpc() = test {
        // Finding a JVM socket's fd goes through /proc (see socketFd).
        assumeHost(HostOs.LINUX)
        TestServer.tcp().use { server ->
            val transport = Transport.FileDescriptor(
                FdConnector { socketFd(SocketChannel.open(server.socketAddress())) },
            )
            Channel(ChannelConfig(transport)).use { channel ->
                assertEquals("fd", channel.unary(ECHO, "fd".encodeToByteArray()).decodeToString())
                assertEquals(3, channel.serverStreaming(COUNT, "3".encodeToByteArray()).toList().size)
            }
        }
        awaitIdleHandles()
    }
}

/**
 * Channels whose fd was handed to kurpc. The JVM has no API to give up a socket's fd, so these
 * objects stay reachable and are never closed: closing one would close a number kurpc owns.
 */
private val surrendered = ArrayList<SocketChannel>()

/**
 * The fd of [channel], found without reflection (which would need `--add-opens`, unknown to
 * Java 8): its local port gives the socket inode in `/proc/net/tcp`, and `/proc/self/fd` maps
 * the inode to the fd. Linux only.
 */
private fun socketFd(channel: SocketChannel): Int {
    synchronized(surrendered) { surrendered.add(channel) }
    val port = (channel.localAddress as InetSocketAddress).port
    val localSuffix = ":%04X".format(port)
    // The JVM opens dual-stack sockets, so an IPv4 connection is usually listed in tcp6.
    val inode = listOf("/proc/net/tcp", "/proc/net/tcp6")
        .flatMap { File(it).readLines().drop(1) }
        .map { it.trim().split(Regex("\\s+")) }
        .first { it[1].endsWith(localSuffix) }[9]
    val link = "socket:[$inode]"
    return File("/proc/self/fd").listFiles()!!
        .first { file ->
            try {
                Files.readSymbolicLink(file.toPath()).toString() == link
            } catch (_: IOException) {
                false
            }
        }
        .name.toInt()
}
