package io.github.xchacha20_poly1305.kurpc

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds

public class LifecycleTest {
    @Test
    public fun closeFailsInFlightCallAndLaterCalls() = test {
        TestServer.tcp().use { server ->
            val channel = server.channel()
            channel.use { channel ->
                // The in-flight call fails the child. A supervisor keeps that off the test scope
                // until `await`, which is what the assertion catches.
                supervisorScope {
                    val call = async {
                        channel.unary(SLEEP, "30000".encodeToByteArray(), CallOptions(timeout = 60.seconds))
                    }
                    awaitStat(channel, "sleep_started", 1)
                    channel.close()
                    assertFailsWith<ChannelClosedException> { call.await() }
                }
                assertFailsWith<ChannelClosedException> {
                    channel.unary(ECHO, ByteArray(0))
                }
            }
        }
        awaitIdleHandles()
    }

    @Test
    public fun explicitCloseReturnsHandlesToZero() = test {
        TestServer.tcp().use { server ->
            val channel = server.channel()
            channel.unary(ECHO, ByteArray(0))
            channel.serverStreaming(COUNT, "2".encodeToByteArray()).toList()
            val call = launch {
                channel.unary(SLEEP, "30000".encodeToByteArray(), CallOptions(timeout = 60.seconds))
            }
            awaitStat(channel, "sleep_started", 1)
            call.cancel()
            call.join()
            channel.close()
        }
        awaitIdleHandles()
    }

    @Test
    public fun cleanerReleasesAbandonedChannel() = test {
        awaitIdleHandles()
        abandonChannel()
        withTimeout(5.seconds) {
            while (true) {
                System.gc()
                if (NativeBridge.debugLiveHandles().all { it == 0L }) {
                    return@withTimeout
                }
                delay(50.milliseconds)
            }
        }
    }

    @Test
    public fun rejectedArguments() = test {
        // Each platform rejects the other family's local transport before reaching native code.
        val foreign = if (hostOs == HostOs.WINDOWS) {
            Transport.Unix("/tmp/kurpc.sock")
        } else {
            Transport.WindowsNamedPipe("\\\\.\\pipe\\kurpc")
        }
        assertFailsWith<IllegalArgumentException> { Channel(ChannelConfig(foreign)) }
        assertFailsWith<IllegalArgumentException> {
            Channel(ChannelConfig(Transport.Tcp("127.0.0.1", 99999)))
        }
        TestServer.tcp().use { server ->
            server.channel().use { channel ->
                assertFailsWith<IllegalArgumentException> {
                    channel.unary("not-a-method", ByteArray(0), CallOptions(timeout = 5.seconds))
                }
            }
        }
        assertFailsWith<IllegalArgumentException> { Kurpc.configure(0) }
        assertFailsWith<IllegalStateException> { Kurpc.configure(4) }
        awaitIdleHandles()
    }
}
