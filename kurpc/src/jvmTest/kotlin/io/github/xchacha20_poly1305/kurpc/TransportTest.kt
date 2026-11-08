package io.github.xchacha20_poly1305.kurpc

import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

public class TransportTest {
    @Test
    public fun missingUnixSocketReachesTheConnector() = test {
        val path = File(System.getProperty("java.io.tmpdir"), "kurpc-${UUID.randomUUID()}.sock").path
        // On Windows this used to throw IllegalArgumentException in JNI before dialing.
        Channel(ChannelConfig(Transport.Unix(path))).use { channel ->
            assertFailsWith<TransportException> {
                channel.unary(ECHO, byteArrayOf(), NativeCallOptions(waitForReady = false))
            }
        }
        awaitIdleHandles()
    }

    @Test
    public fun tcpRoundTrip() = test {
        TestServer.tcp().use { server ->
            server.channel().use { channel ->
                assertEquals("tcp", channel.unary(ECHO, "tcp".encodeToByteArray()).decodeToString())
            }
        }
        awaitIdleHandles()
    }

    @Test
    public fun unixSocketRoundTrip() = test {
        assumeHost(HostOs.LINUX, HostOs.MACOS)
        // /tmp rather than java.io.tmpdir: macOS's per-user tmpdir can push the path past the
        // 104-byte sun_path limit.
        TestServer.unix("/tmp/kurpc-${UUID.randomUUID()}.sock").use { server ->
            server.channel().use { channel ->
                assertEquals("uds", channel.unary(ECHO, "uds".encodeToByteArray()).decodeToString())
            }
        }
        awaitIdleHandles()
    }

    @Test
    public fun abstractUnixSocketRoundTrip() = test {
        assumeHost(HostOs.LINUX)
        TestServer.abstractUnix("k${System.nanoTime()}").use { server ->
            assertTrue(server.address.startsWith("\u0000"), server.address)
            server.channel().use { channel ->
                assertEquals(
                    "abstract",
                    channel.unary(ECHO, "abstract".encodeToByteArray()).decodeToString(),
                )
            }
        }
        awaitIdleHandles()
    }

    @Test
    public fun windowsNamedPipeRoundTrip() = test {
        assumeHost(HostOs.WINDOWS)
        TestServer.pipe("kurpc-${UUID.randomUUID()}").use { server ->
            assertTrue(server.transport() is Transport.WindowsNamedPipe, server.address)
            server.channel().use { channel ->
                assertEquals("pipe", channel.unary(ECHO, "pipe".encodeToByteArray()).decodeToString())
            }
        }
        awaitIdleHandles()
    }
}
