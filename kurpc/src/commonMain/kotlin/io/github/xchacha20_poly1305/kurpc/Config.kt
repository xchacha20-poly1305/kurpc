package io.github.xchacha20_poly1305.kurpc

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * @property secure sends `:scheme` `https` instead of `http`. kurpc never encrypts by itself:
 * TLS is part of the transport, for example a [Transport.Custom] whose connector returns
 * `Connection.tls(...)`, and [secure] only tells the server what the transport does.
 */
public data class ChannelConfig(
    public val transport: Transport,
    public val authority: String = "localhost",
    public val secure: Boolean = false,
    public val metadata: Metadata = Metadata.Empty,
    public val connectTimeout: Duration = 10.seconds,
    public val keepAlive: KeepAlive? = null,
    public val userAgent: String? = null,
)

public sealed interface Transport {
    public data class Tcp(public val host: String, public val port: Int) : Transport

    /** A path starting with `"\u0000"` is a Linux/Android abstract-namespace socket. */
    public data class Unix(public val path: String) : Transport

    /** Full pipe path, for example `\\.\pipe\kurpc`. */
    public data class WindowsNamedPipe(public val name: String) : Transport

    public class Custom public constructor(public val connector: Connector) : Transport

    /** Unix only. The connector returns a connected socket; kurpc takes ownership of the fd. */
    public class FileDescriptor public constructor(public val connector: FdConnector) : Transport
}

public data class KeepAlive(
    public val interval: Duration,
    public val timeout: Duration,
    public val whileIdle: Boolean = false,
)
