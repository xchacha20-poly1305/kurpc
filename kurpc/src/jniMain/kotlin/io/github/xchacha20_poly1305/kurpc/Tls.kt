package io.github.xchacha20_poly1305.kurpc

import java.io.IOException
import java.net.Socket
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/** The ALPN protocol id of HTTP/2 over TLS. */
internal const val ALPN_H2: String = "h2"

/**
 * Runs a TLS client handshake over the connected [socket] and adapts the result for a
 * [Transport.Custom] channel, which should also set [ChannelConfig.secure].
 *
 * [serverName] is sent as SNI and must match the server certificate. [sslSocketFactory] decides
 * trust and client identity; the default trusts the platform's CA store. ALPN offers `h2`, which
 * grpc-go servers require. ALPN needs JDK 8u252 or later; on Android below API 29 it needs a
 * socket of the platform provider (Conscrypt). Without ALPN the handshake still proceeds. If
 * the server selects a protocol other than `h2`, the handshake fails.
 *
 * The handshake blocks an IO thread until it completes or [socket]'s read timeout expires.
 * Closing the connection closes [socket].
 */
public suspend fun Connection.Companion.tls(
    socket: Socket,
    serverName: String,
    sslSocketFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
): Connection {
    val tls = sslSocketFactory.createSocket(socket, serverName, socket.port, true) as SSLSocket
    try {
        runInterruptible(Dispatchers.IO) { handshake(tls, serverName) }
    } catch (error: Throwable) {
        try {
            tls.close()
        } catch (_: IOException) {
        }
        throw error
    }
    return StreamConnection(tls.inputStream, tls.outputStream) {
        // Closing the plain socket first wakes a read blocked inside the SSL socket, which
        // closing the SSL socket alone may wait on.
        try {
            socket.close()
        } finally {
            try {
                tls.close()
            } catch (_: IOException) {
                // The close_notify cannot go out over the closed socket.
            }
        }
    }
}

internal fun handshake(socket: SSLSocket, serverName: String) {
    prepareTls(socket, serverName)
    socket.startHandshake()
    verifyServerName(socket, serverName)
    val protocol = negotiatedProtocol(socket)
    if (protocol != null && protocol != ALPN_H2) {
        throw SSLException("server selected ALPN protocol $protocol instead of $ALPN_H2")
    }
}

/**
 * Before the handshake: offers ALPN [ALPN_H2], makes sure SNI carries [serverName] and, where
 * the handshake can, has it check the certificate against [serverName].
 */
internal expect fun prepareTls(socket: SSLSocket, serverName: String)

/** After the handshake: checks the certificate against [serverName] if [prepareTls] could not. */
internal expect fun verifyServerName(socket: SSLSocket, serverName: String)

/** The ALPN protocol the server selected, or `null` if none or the platform cannot tell. */
internal expect fun negotiatedProtocol(socket: SSLSocket): String?
