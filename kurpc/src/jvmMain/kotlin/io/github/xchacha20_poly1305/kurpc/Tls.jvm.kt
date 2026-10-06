package io.github.xchacha20_poly1305.kurpc

import java.lang.reflect.Method
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket

// ALPN came with Java 9 and was backported to 8u252; the Java 8 API kurpc compiles against
// does not have it, so it is looked up at run time.
private val setApplicationProtocols: Method? =
    publicMethod(SSLParameters::class.java, "setApplicationProtocols", Array<String>::class.java)
private val getApplicationProtocol: Method? = publicMethod(SSLSocket::class.java, "getApplicationProtocol")

/** The factory already sent [serverName] as SNI when it layered the socket. */
internal actual fun prepareTls(socket: SSLSocket, serverName: String) {
    val parameters = socket.sslParameters
    parameters.endpointIdentificationAlgorithm = "HTTPS"
    setApplicationProtocols?.invoke(parameters, arrayOf(ALPN_H2))
    socket.sslParameters = parameters
}

/** Done during the handshake by the endpoint identification [prepareTls] sets. */
internal actual fun verifyServerName(socket: SSLSocket, serverName: String) = Unit

internal actual fun negotiatedProtocol(socket: SSLSocket): String? =
    (getApplicationProtocol?.invoke(socket) as String?)?.takeIf { it.isNotEmpty() }

private fun publicMethod(type: Class<*>, name: String, vararg parameters: Class<*>): Method? = try {
    type.getMethod(name, *parameters)
} catch (_: NoSuchMethodException) {
    null
}
