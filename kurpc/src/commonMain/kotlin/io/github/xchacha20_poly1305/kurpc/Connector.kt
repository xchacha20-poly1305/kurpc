package io.github.xchacha20_poly1305.kurpc

/** Called once per connection, including after a drop, so a custom transport reconnects. */
public fun interface Connector {
    public suspend fun connect(): Connection
}

/**
 * A byte stream kurpc speaks HTTP/2 over. One coroutine reads while another writes; [close] must
 * make a pending [read] return -1 or throw, because that is how kurpc stops the reading side.
 */
public interface Connection {
    /** Bytes read, or -1 at EOF. */
    public suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int

    /** Writes all [length] bytes. */
    public suspend fun write(buffer: ByteArray, offset: Int, length: Int)

    public suspend fun close()

    /** Platform adapters are extension functions on this companion, such as `Connection.of`. */
    public companion object
}

/** Returns a connected socket fd. kurpc owns it afterwards. Unix only. */
public fun interface FdConnector {
    public suspend fun connect(): Int
}
