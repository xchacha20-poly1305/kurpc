package io.github.xchacha20_poly1305.kurpc

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

public class UnaryTest {
    @Test
    public fun unaryEchoesBodyAndMetadata() = test {
        TestServer.tcp().use { server ->
            val metadata = Metadata.of("authorization" to "Bearer t") +
                Metadata.ofBinary(
                    "a-bin" to byteArrayOf(0x00),
                    "bb-bin" to byteArrayOf(0x01, 0xff.toByte()),
                    "ccc-bin" to byteArrayOf(1, 2, 3),
                )
            server.channel(
                metadata = metadata,
                keepAlive = KeepAlive(30.seconds, 10.seconds, whileIdle = true),
                userAgent = "kurpc-jvm-test",
            ).use { channel ->
                val empty = channel.unary(ECHO, ByteArray(0), CallOptions(timeout = 5.seconds))
                assertEquals(0, empty.size)
                val response = channel.unaryCall(
                    ECHO,
                    "hi".encodeToByteArray(),
                    CallOptions(timeout = 5.seconds, metadata = Metadata.of("x-req" to "ascii")),
                )
                assertEquals("hi", response.message.decodeToString())
                assertEquals("Bearer t", response.headers.text("authorization"))
                assertEquals("ascii", response.headers.text("x-req"))
                assertContentEquals(byteArrayOf(0x00), response.headers.binary("a-bin"))
                assertContentEquals(byteArrayOf(0x01, 0xff.toByte()), response.headers.binary("bb-bin"))
                assertContentEquals(byteArrayOf(1, 2, 3), response.headers.binary("ccc-bin"))
            }
        }
        awaitIdleHandles()
    }

    @Test
    public fun statusErrorCarriesCodeAndMessage() = test {
        TestServer.tcp().use { server ->
            server.channel().use { channel ->
                val error = assertFailsWith<StatusException> {
                    channel.unary(
                        FAIL,
                        "5:missing item".encodeToByteArray(),
                        CallOptions(timeout = 5.seconds),
                    )
                }
                assertEquals(Status.Code.NOT_FOUND, error.code)
                assertTrue(error.description.contains("missing item"), error.description)
            }
        }
        awaitIdleHandles()
    }

    @Test
    public fun transportErrorWhenNothingListens() = test {
        val port = freePort()
        val channel = Channel(
            ChannelConfig(
                transport = Transport.Tcp("127.0.0.1", port),
                connectTimeout = 1.seconds,
            ),
        )
        try {
            assertFailsWith<TransportException> {
                channel.unary(
                    ECHO,
                    ByteArray(0),
                    CallOptions(timeout = 2.seconds, waitForReady = false),
                )
            }
        } finally {
            channel.close()
        }
        awaitIdleHandles()
    }

    @Test
    public fun timeoutIsDeadlineExceeded() = test {
        TestServer.tcp().use { server ->
            server.channel().use { channel ->
                val error = assertFailsWith<StatusException> {
                    channel.unary(
                        SLEEP,
                        "30000".encodeToByteArray(),
                        CallOptions(timeout = 200.milliseconds),
                    )
                }
                assertEquals(Status.Code.DEADLINE_EXCEEDED, error.code)
            }
        }
        awaitIdleHandles()
    }

    @Test
    public fun cancellationReachesTheServer() = test {
        TestServer.tcp().use { server ->
            server.channel().use { channel ->
                val call = launch {
                    channel.unary(
                        SLEEP,
                        "30000".encodeToByteArray(),
                        CallOptions(timeout = 60.seconds),
                    )
                }
                awaitStat(channel, "sleep_started", 1)
                call.cancel()
                call.join()
                awaitStat(channel, "sleep_cancelled", 1)
            }
        }
        awaitIdleHandles()
    }

    @Test
    public fun waitForReadyCompletesWhenTheServerAppears() = test {
        val port = freePort()
        val channel = Channel(
            ChannelConfig(
                transport = Transport.Tcp("127.0.0.1", port),
                connectTimeout = 500.milliseconds,
            ),
        )
        channel.use { channel ->
            val call = async {
                channel.unary(
                    ECHO,
                    "ready".encodeToByteArray(),
                    CallOptions(timeout = 15.seconds, waitForReady = true),
                )
            }
            delay(300.milliseconds)
            assertTrue(call.isActive, "wait-for-ready returned before the server existed")
            TestServer.tcp(port).use {
                assertEquals("ready", call.await().decodeToString())
            }
        }
        awaitIdleHandles()
    }
}
