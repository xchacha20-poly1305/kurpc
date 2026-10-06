package io.github.xchacha20_poly1305.kurpc

import android.os.Build
import java.lang.reflect.Method
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket

/** API 29 made ALPN public. Before it, the platform provider (Conscrypt) has it as hidden methods. */
private val publicAlpn: Boolean = Build.VERSION.SDK_INT >= 29

internal actual fun prepareTls(socket: SSLSocket, serverName: String) {
    if (publicAlpn) {
        val parameters = socket.sslParameters
        parameters.applicationProtocols = arrayOf(ALPN_H2)
        socket.sslParameters = parameters
        return
    }
    // The same calls OkHttp makes on these releases. A socket from another provider lacks
    // them and goes without ALPN.
    method(socket, "setHostname", String::class.java)?.invoke(socket, serverName)
    method(socket, "setAlpnProtocols", ByteArray::class.java)
        ?.invoke(socket, byteArrayOf(ALPN_H2.length.toByte()) + ALPN_H2.encodeToByteArray())
}

/**
 * Android's `SSLSocket` does no host name check of its own, and endpoint identification is
 * API 24; the platform verifier is what `HttpsURLConnection` uses on every release.
 */
internal actual fun verifyServerName(socket: SSLSocket, serverName: String) {
    if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(serverName, socket.session)) {
        throw SSLPeerUnverifiedException("certificate does not match $serverName")
    }
}

internal actual fun negotiatedProtocol(socket: SSLSocket): String? {
    val protocol = if (publicAlpn) {
        socket.applicationProtocol
    } else {
        (method(socket, "getAlpnSelectedProtocol")?.invoke(socket) as ByteArray?)?.decodeToString()
    }
    return protocol?.takeIf { it.isNotEmpty() }
}

private fun method(socket: SSLSocket, name: String, vararg parameters: Class<*>): Method? = try {
    socket.javaClass.getMethod(name, *parameters)
} catch (_: NoSuchMethodException) {
    null
}
