package io.github.xchacha20_poly1305.kurpc

import androidx.test.platform.app.InstrumentationRegistry
import java.io.BufferedReader
import java.io.File
import java.io.IOException

/**
 * `kurpc-testserver` built for the device ABI. The build packages it in the test APK as
 * `libkurpc_testserver.so`, which the installer extracts to `nativeLibraryDir`, the one
 * directory an app may execute files from on every API level.
 */
internal class DeviceTestServer private constructor(
    val address: String,
    /** The self-signed certificate of a TLS server. */
    val certificatePem: String?,
    private val process: Process,
) : AutoCloseable {
    override fun close() {
        try {
            process.outputStream.close()
        } catch (_: IOException) {
        }
        // `Process.waitFor(timeout)` is API 26; destroy is enough once stdin has closed.
        process.destroy()
    }

    companion object {
        fun tcp(): DeviceTestServer = start("--tcp", "127.0.0.1:0")

        fun tcpTls(): DeviceTestServer = start("--tcp", "127.0.0.1:0", "--tls")

        /** Binds the abstract socket `\0name`; [address] keeps the leading NUL. */
        fun abstractUnix(name: String): DeviceTestServer = start("--unix", "@$name")

        private fun start(vararg args: String): DeviceTestServer {
            val context = InstrumentationRegistry.getInstrumentation().context
            val binary = File(context.applicationInfo.nativeLibraryDir, "libkurpc_testserver.so")
            val process = ProcessBuilder(listOf(binary.path) + args)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader()
            // stderr is merged in, and older linkers (API 24 here) warn about the
            // DT_FLAGS_1 PIE bit on stderr before the server prints anything.
            val skipped = StringBuilder()
            var line = output.readLine()
            while (line != null && !line.startsWith("listening ")) {
                skipped.append(line).append('\n')
                line = output.readLine()
            }
            if (line == null) {
                process.destroy()
                throw IllegalStateException("test server did not start:\n$skipped")
            }
            val certificatePem = if ("--tls" in args) readCertificate(output) else null
            return DeviceTestServer(line.removePrefix("listening "), certificatePem, process)
        }

        private fun readCertificate(output: BufferedReader): String {
            if (output.readLine() != "--- kurpc-testserver tls certificate ---") {
                throw IllegalStateException("expected the tls certificate banner")
            }
            val pem = StringBuilder()
            while (true) {
                val line = output.readLine() ?: throw IllegalStateException("eof in tls certificate")
                if (line == "--- end kurpc-testserver tls certificate ---") {
                    return pem.toString()
                }
                pem.append(line).append('\n')
            }
        }
    }
}

internal fun DeviceTestServer.tcpTransport(): Transport.Tcp {
    val colon = address.lastIndexOf(':')
    return Transport.Tcp(address.substring(0, colon), address.substring(colon + 1).toInt())
}

