package io.github.xchacha20_poly1305.kurpc

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Bytes moved per pipe read or write. Matches the native pipe's buffer. */
private const val PUMP_CHUNK: Int = 64 * 1024

/**
 * Answers native dials of a `Transport.Custom` or `Transport.FileDescriptor` channel (plan §5.4)
 * by running the user's connector, and for streams pumps the connection on [Dispatchers.IO]: the
 * pipe calls block, so each direction holds an IO thread while the connection lives.
 */
internal class RelayDialer private constructor(
    private val answer: suspend (request: Long) -> Unit,
) : NativeDialer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ATOMIC runs the body even if the scope is already cancelled, so every request is answered
    // and its handle consumed. That guarantee is the reason for the delicate start mode.
    @OptIn(DelicateCoroutinesApi::class)
    override fun dial(request: Long) {
        scope.launch(start = CoroutineStart.ATOMIC) { answer(request) }
    }

    /** Cancels connects in progress. Established connections end when tonic drops them. */
    fun close() {
        scope.cancel()
    }

    companion object {
        fun forTransport(transport: Transport): RelayDialer? = when (transport) {
            is Transport.Custom -> RelayDialer { request -> relayStream(request, transport.connector) }
            is Transport.FileDescriptor -> RelayDialer { request -> relayFd(request, transport.connector) }
            is Transport.Tcp, is Transport.Unix, is Transport.WindowsNamedPipe -> null
        }
    }
}

private suspend fun relayStream(request: Long, connector: Connector) {
    val connection = try {
        connector.connect()
    } catch (error: Throwable) {
        NativeBridge.dialFail(request, error.toString())
        return
    }
    val pipe = NativeBridge.dialStream(request)
    if (pipe == 0L) {
        closeQuietly(connection)
        return
    }
    try {
        pump(pipe, connection)
    } finally {
        NativeBridge.pipeRelease(pipe)
    }
}

private suspend fun relayFd(request: Long, connector: FdConnector) {
    val fd = try {
        connector.connect()
    } catch (error: Throwable) {
        NativeBridge.dialFail(request, error.toString())
        return
    }
    NativeBridge.dialFd(request, fd)
}

/**
 * Runs both directions until the connection is over. Each side, when it stops for any reason,
 * makes the other one stop: inbound EOF shuts the pipe down, so tonic drops the connection and
 * the outbound read ends; outbound EOF closes the user's connection, so the inbound read ends.
 */
private suspend fun pump(pipe: Long, connection: Connection) {
    val closed = AtomicBoolean(false)
    suspend fun closeConnection() {
        if (closed.compareAndSet(false, true)) {
            closeQuietly(connection)
        }
    }
    coroutineScope {
        launch {
            try {
                val buffer = ByteArray(PUMP_CHUNK)
                while (true) {
                    val read = connection.read(buffer, 0, buffer.size)
                    if (read < 0 || !NativeBridge.pipeWrite(pipe, buffer, 0, read)) {
                        break
                    }
                }
            } catch (_: Exception) {
                // A failed read ends the connection the same way EOF does.
            } finally {
                NativeBridge.pipeShutdown(pipe)
            }
        }
        launch {
            try {
                val buffer = ByteArray(PUMP_CHUNK)
                while (true) {
                    val read = NativeBridge.pipeRead(pipe, buffer, 0, buffer.size)
                    if (read < 0) {
                        break
                    }
                    connection.write(buffer, 0, read)
                }
            } catch (_: Exception) {
                // tonic sees the failure as the connection going away.
            } finally {
                closeConnection()
            }
        }
    }
}

private suspend fun closeQuietly(connection: Connection) {
    try {
        withContext(NonCancellable) { connection.close() }
    } catch (_: Exception) {
    }
}
