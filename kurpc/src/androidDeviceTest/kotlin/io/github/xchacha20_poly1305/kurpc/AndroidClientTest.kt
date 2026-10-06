package io.github.xchacha20_poly1305.kurpc

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.milliseconds

private const val ECHO: String = "/kurpc.test.Test/Echo"
private const val COUNT: String = "/kurpc.test.Test/Count"
private const val SCHEME: String = "/kurpc.test.Test/Scheme"

@RunWith(AndroidJUnit4::class)
public class AndroidClientTest {
    @Test
    public fun unaryOverAbstractUnixSocket(): Unit = deviceTest {
        DeviceTestServer.abstractUnix(socketName()).use { server ->
            Channel(ChannelConfig(Transport.Unix(server.address))).use { channel ->
                assertEquals("uds", channel.unary(ECHO, "uds".encodeToByteArray()).decodeToString())
            }
        }
    }

    @Test
    public fun unaryAndStreamOverTcp(): Unit = deviceTest {
        DeviceTestServer.tcp().use { server ->
            Channel(ChannelConfig(server.tcpTransport())).use { channel ->
                assertEquals("tcp", channel.unary(ECHO, "tcp".encodeToByteArray()).decodeToString())
                val messages = channel.serverStreaming(COUNT, "20".encodeToByteArray()).toList()
                assertEquals(20, messages.size)
            }
        }
    }

    /** Below API 29 this goes through the hidden Conscrypt ALPN methods. */
    @Test
    public fun tlsNegotiatesH2AndCarriesCalls(): Unit = deviceTest {
        DeviceTestServer.tcpTls().use { server ->
            val factory = trusting(server.certificatePem!!)
            val address = server.tcpTransport()
            val socket = Socket(address.host, address.port)
            val tls = factory.createSocket(socket, "localhost", address.port, true) as SSLSocket
            tls.use {
                handshake(tls, "localhost")
                assertEquals(ALPN_H2, negotiatedProtocol(tls))
            }

            val transport = Transport.Custom {
                Connection.tls(Socket(address.host, address.port), "localhost", factory)
            }
            Channel(ChannelConfig(transport, secure = true)).use { channel ->
                assertEquals("tls", channel.unary(ECHO, "tls".encodeToByteArray()).decodeToString())
                assertEquals("https", channel.unary(SCHEME, ByteArray(0)).decodeToString())
            }
        }
    }

    @Test
    public fun tlsRejectsWrongServerName(): Unit = deviceTest {
        DeviceTestServer.tcpTls().use { server ->
            val address = server.tcpTransport()
            val socket = Socket(address.host, address.port)
            try {
                Connection.tls(socket, "wrong.test", trusting(server.certificatePem!!))
                fail("handshake with a wrong server name succeeded")
            } catch (_: SSLException) {
            }
            assertTrue(socket.isClosed)
        }
    }

    @Test
    public fun customTransportOverLocalSocket(): Unit = deviceTest {
        val name = socketName()
        DeviceTestServer.abstractUnix(name).use {
            val transport = Transport.Custom { Connection.of(connectLocal(name)) }
            Channel(ChannelConfig(transport)).use { channel ->
                assertEquals("local", channel.unary(ECHO, "local".encodeToByteArray()).decodeToString())
            }
        }
    }

    @Test
    public fun fileDescriptorTransportOverLocalSocket(): Unit = deviceTest {
        val name = socketName()
        DeviceTestServer.abstractUnix(name).use {
            val transport = Transport.FileDescriptor {
                val socket = connectLocal(name)
                // kurpc owns the duplicate; the original goes away with the LocalSocket.
                ParcelFileDescriptor.dup(socket.fileDescriptor).detachFd().also { socket.close() }
            }
            Channel(ChannelConfig(transport)).use { channel ->
                assertEquals("fd", channel.unary(ECHO, "fd".encodeToByteArray()).decodeToString())
            }
        }
    }
}

private fun deviceTest(body: suspend () -> Unit) {
    runBlocking {
        withTimeout(30.seconds) {
            body()
            // Every channel above is closed; its calls and connections must release their handles.
            while (!NativeBridge.debugLiveHandles().all { it == 0L }) {
                delay(20.milliseconds)
            }
        }
    }
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

private fun socketName(): String = "kurpc-device-${System.nanoTime()}"

private fun connectLocal(name: String): LocalSocket {
    val socket = LocalSocket()
    socket.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
    return socket
}
