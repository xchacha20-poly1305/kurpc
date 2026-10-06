package io.github.xchacha20_poly1305.kurpc

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds

public class StreamingTest {
    @Test
    public fun serverStreamCountIsCold() = test {
        TestServer.tcp().use { server ->
            server.channel().use { channel ->
                val first = channel.serverStreaming(COUNT, "4".encodeToByteArray()).toList()
                val second = channel.serverStreaming(COUNT, "4".encodeToByteArray()).toList()
                assertEquals(4, first.size)
                assertEquals(4, second.size)
                for (messages in listOf(first, second)) {
                    for ((index, message) in messages.withIndex()) {
                        assertEquals(64 * 1024, message.size)
                        assertEquals(index, message.decodeToString().trim().toInt())
                    }
                }
                assertEquals(8L, stats(channel)["stream_sent"])
            }
        }
        awaitIdleHandles()
    }

    @Test
    public fun serverStreamErrorsAfterK() = test {
        TestServer.tcp().use { server ->
            server.channel().use { channel ->
                val got = ArrayList<ByteArray>()
                val error = assertFailsWith<StatusException> {
                    channel.serverStreaming(COUNT, "10:3".encodeToByteArray()).collect { got.add(it) }
                }
                assertEquals(3, got.size)
                assertEquals(Status.Code.ABORTED, error.code)
                assertTrue(error.description.contains("3"), error.description)
            }
        }
        awaitIdleHandles()
    }

    @Test
    public fun infiniteStreamCancelReachesTheServer() = test {
        TestServer.tcp().use { server ->
            server.channel().use { channel ->
                val received = AtomicInteger()
                val call = launch {
                    channel.serverStreaming(INFINITE, ByteArray(0)).collect {
                        received.incrementAndGet()
                    }
                }
                withTimeout(5.seconds) {
                    while (received.get() < 1) {
                        delay(20.milliseconds)
                    }
                }
                call.cancel()
                call.join()
                awaitStat(channel, "stream_cancelled", 1)
            }
        }
        awaitIdleHandles()
    }

    @Test
    public fun failingCollectorCancelsTheCall() = test {
        TestServer.tcp().use { server ->
            server.channel().use { channel ->
                val error = assertFailsWith<IllegalStateException> {
                    channel.serverStreaming(INFINITE, ByteArray(0)).collect {
                        throw IllegalStateException("collector failed")
                    }
                }
                assertEquals("collector failed", error.message)
                awaitStat(channel, "stream_cancelled", 1)
            }
        }
        awaitIdleHandles()
    }

    @Test
    public fun slowCollectorStallsTheServer() = test {
        TestServer.tcp().use { server ->
            server.channel().use { channel ->
                val seen = AtomicInteger()
                val gate = CompletableDeferred<Unit>()
                val call = launch {
                    channel.serverStreaming(COUNT, "400".encodeToByteArray()).collect {
                        if (seen.incrementAndGet() == 1) {
                            gate.await()
                        }
                    }
                }
                withTimeout(5.seconds) {
                    while (seen.get() < 1) {
                        delay(10.milliseconds)
                    }
                }
                delay(200.milliseconds)
                val first = stats(channel)["stream_sent"] ?: 0L
                delay(400.milliseconds)
                val second = stats(channel)["stream_sent"] ?: 0L
                assertTrue(second < 150L, "server sent $second messages while the collector held one")
                assertTrue(second - first < 16L, "server advanced from $first to $second during the stall")
                assertEquals(1, seen.get())
                gate.complete(Unit)
                call.cancel()
                call.join()
            }
        }
        awaitIdleHandles()
    }
}
