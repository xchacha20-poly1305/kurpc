package io.github.xchacha20_poly1305.kurpc

import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.toList

public class TlsTest {
    @Test
    public fun tlsConnectionCarriesCallsWithHttpsScheme() = test {
        TestServer.tcp(tls = true).use { server ->
            val factory = trusting(server.certificatePem!!)
            val transport = Transport.Custom { Connection.tls(server.connect(), "localhost", factory) }
            Channel(ChannelConfig(transport, secure = true)).use { channel ->
                assertEquals("tls", channel.unary(ECHO, "tls".encodeToByteArray()).decodeToString())
                assertEquals("https", channel.unary(SCHEME, ByteArray(0)).decodeToString())
                assertEquals(30, channel.serverStreaming(COUNT, "30".encodeToByteArray()).toList().size)
            }
        }
        awaitIdleHandles()
    }

    @Test
    public fun plainChannelSendsHttpScheme() = test {
        TestServer.tcp().use { server ->
            server.channel().use { channel ->
                assertEquals("http", channel.unary(SCHEME, ByteArray(0)).decodeToString())
            }
        }
        awaitIdleHandles()
    }

    @Test
    public fun handshakeNegotiatesH2() = test {
        TestServer.tcp(tls = true).use { server ->
            val socket = server.connect()
            val tls = trusting(server.certificatePem!!)
                .createSocket(socket, "localhost", socket.port, true) as SSLSocket
            tls.use {
                handshake(tls, "localhost")
                assertEquals(ALPN_H2, negotiatedProtocol(tls))
            }
        }
    }

    @Test
    public fun wrongServerNameFailsAndClosesTheSocket() = test {
        TestServer.tcp(tls = true).use { server ->
            val socket = server.connect()
            assertFailsWith<SSLException> {
                Connection.tls(socket, "wrong.test", trusting(server.certificatePem!!))
            }
            assertTrue(socket.isClosed)
        }
    }

    @Test
    public fun untrustedCertificateFails() = test {
        TestServer.tcp(tls = true).use { server ->
            assertFailsWith<SSLException> { Connection.tls(server.connect(), "localhost") }
        }
    }

    @Test
    public fun failedHandshakeIsATransportError() = test {
        TestServer.tcp(tls = true).use { server ->
            val transport = Transport.Custom { Connection.tls(server.connect(), "localhost") }
            Channel(ChannelConfig(transport, secure = true)).use { channel ->
                assertFailsWith<TransportException> {
                    channel.unary(ECHO, ByteArray(0), CallOptions(timeout = 5.seconds, waitForReady = false))
                }
            }
        }
        awaitIdleHandles()
    }
}

private fun TestServer.connect(): Socket {
    val socket = Socket()
    socket.connect(socketAddress())
    return socket
}

/** Trusts only the test server's self-signed certificate. */
private fun trusting(certificatePem: String): SSLSocketFactory {
    val certificate = CertificateFactory.getInstance("X.509")
        .generateCertificate(certificatePem.byteInputStream())
    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
    keyStore.load(null, null)
    keyStore.setCertificateEntry("kurpc-testserver", certificate)
    val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    trust.init(keyStore)
    val context = SSLContext.getInstance("TLS")
    context.init(null, trust.trustManagers, null)
    return context.socketFactory
}
