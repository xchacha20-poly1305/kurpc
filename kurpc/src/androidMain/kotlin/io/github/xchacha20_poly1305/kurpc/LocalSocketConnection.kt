package io.github.xchacha20_poly1305.kurpc

import android.net.LocalSocket
import java.io.IOException

/**
 * Adapts a connected [LocalSocket]. Closing it shuts both directions down first: closing a
 * `LocalSocket` alone does not wake a thread blocked reading from it.
 *
 * For `Transport.FileDescriptor`, hand kurpc a duplicate of the socket instead:
 * `FdConnector { ParcelFileDescriptor.dup(socket.fileDescriptor).detachFd().also { socket.close() } }`.
 */
public fun Connection.Companion.of(socket: LocalSocket): Connection =
    StreamConnection(socket.inputStream, socket.outputStream) {
        try {
            socket.shutdownInput()
            socket.shutdownOutput()
        } catch (_: IOException) {
            // Already shut down or never connected; close below still releases the socket.
        }
        socket.close()
    }
