package io.github.xchacha20_poly1305.kurpc

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

public class TransportTest {
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
