package io.github.xchacha20_poly1305.kurpc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.ByteChannel

/**
 * Adapts a blocking [ByteChannel], for example a connected `SocketChannel`. A non-blocking
 * channel is not supported: a read that returns 0 would spin.
 */
public fun Connection.Companion.of(channel: ByteChannel): Connection = ByteChannelConnection(channel)

/** Adapts a stream pair, for example a `java.net.Socket`'s. [Connection.close] closes both. */
public fun Connection.Companion.of(input: InputStream, output: OutputStream): Connection =
    StreamConnection(input, output) {
        output.use {
            input.close()
        }
    }

private class ByteChannelConnection(private val channel: ByteChannel) : Connection {
    // runInterruptible: cancellation interrupts the blocked thread, which an interruptible
    // channel answers by closing itself.
    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        runInterruptible(Dispatchers.IO) { channel.read(ByteBuffer.wrap(buffer, offset, length)) }

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
        runInterruptible(Dispatchers.IO) {
            val bytes = ByteBuffer.wrap(buffer, offset, length)
            while (bytes.hasRemaining()) {
                channel.write(bytes)
            }
        }
    }

    override suspend fun close() {
        channel.close()
    }
}

/** [close] is supplied by the adapter: some sockets need a shutdown before a blocked read returns. */
internal class StreamConnection(
    private val input: InputStream,
    private val output: OutputStream,
    private val close: () -> Unit,
) : Connection {
    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        runInterruptible(Dispatchers.IO) { input.read(buffer, offset, length) }

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
        runInterruptible(Dispatchers.IO) {
            output.write(buffer, offset, length)
            output.flush()
        }
    }

    override suspend fun close() {
        close.invoke()
    }
}
