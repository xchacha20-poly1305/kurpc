package io.github.xchacha20_poly1305.kurpc

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A `kurpc-testserver` subprocess. It prints `listening <addr>` and, with `--tls`, a PEM block,
 * then exits when stdin closes.
 */
private const val PIPE_PREFIX: String = "\\\\.\\pipe\\"

internal class TestServer private constructor(
    val address: String,
    val certificatePem: String?,
    private val process: Process,
) : AutoCloseable {
    fun transport(): Transport {
        if (address.startsWith("/") || address.startsWith("\u0000")) {
            return Transport.Unix(address)
        }
        if (address.startsWith(PIPE_PREFIX)) {
            return Transport.WindowsNamedPipe(address)
        }
        val colon = address.lastIndexOf(':')
        if (colon <= 0) {
            throw IllegalStateException("unrecognized listening address $address")
        }
        return Transport.Tcp(address.substring(0, colon), address.substring(colon + 1).toInt())
    }

    fun socketAddress(): InetSocketAddress {
        val tcp = transport() as Transport.Tcp
        return InetSocketAddress(tcp.host, tcp.port)
    }

    fun channel(
        metadata: Metadata = Metadata.Empty,
        keepAlive: KeepAlive? = null,
        userAgent: String? = null,
        connectTimeout: Duration = 10.seconds,
    ): Channel = Channel(
        ChannelConfig(
            transport = transport(),
            metadata = metadata,
            keepAlive = keepAlive,
            userAgent = userAgent,
            connectTimeout = connectTimeout,
        ),
    )

    override fun close() {
        try {
            process.outputStream.close()
        } catch (_: IOException) {
        }
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
        }
        if (address.startsWith("/")) {
            java.io.File(address).delete()
        }
    }

    internal companion object {
        fun tcp(port: Int = 0, tls: Boolean = false): TestServer =
            start(listOf("--tcp", "127.0.0.1:$port"), tls)

        fun unix(path: String): TestServer = start(listOf("--unix", path), tls = false)

        /** [name] is the abstract socket name without the leading NUL; the server binds `\0name`. */
        fun abstractUnix(name: String): TestServer = start(listOf("--unix", "@$name"), tls = false)

        /** Windows only. [name] is the part after `\\.\pipe\`. */
        fun pipe(name: String): TestServer = start(listOf("--pipe", PIPE_PREFIX + name), tls = false)

        private fun start(args: List<String>, tls: Boolean): TestServer {
            val binary = System.getProperty("kurpc.testserver")
                ?: throw IllegalStateException("kurpc.testserver system property is not set")
            val command = ArrayList<String>(args.size + 2)
            command.add(binary)
            command.addAll(args)
            if (tls) {
                command.add("--tls")
            }
            val process = ProcessBuilder(command).start()
            val stderr = StringBuilder()
            val errThread = Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(process.errorStream, "UTF-8"))
                    while (true) {
                        val line = reader.readLine() ?: break
                        synchronized(stderr) {
                            stderr.append(line).append('\n')
                        }
                    }
                } catch (_: IOException) {
                }
            }
            errThread.isDaemon = true
            errThread.name = "kurpc-testserver-stderr"
            errThread.start()

            val ready = CountDownLatch(1)
            val address = arrayOfNulls<String>(1)
            val pem = arrayOfNulls<String>(1)
            val failure = arrayOfNulls<Throwable>(1)
            val outThread = Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(process.inputStream, "UTF-8"))
                    val line = reader.readLine()
                    if (line == null || !line.startsWith("listening ")) {
                        throw IllegalStateException("expected listening banner, got ${line ?: "EOF"}")
                    }
                    address[0] = line.substring("listening ".length)
                    if (tls) {
                        pem[0] = readPem(reader)
                    }
                } catch (error: Throwable) {
                    failure[0] = error
                } finally {
                    ready.countDown()
                }
            }
            outThread.isDaemon = true
            outThread.name = "kurpc-testserver-stdout"
            outThread.start()

            if (!ready.await(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw IllegalStateException("timed out waiting for test server\n$stderr")
            }
            val error = failure[0]
            if (error != null) {
                process.destroyForcibly()
                throw IllegalStateException("test server failed to start: $error\n$stderr", error)
            }
            return TestServer(address[0]!!, pem[0], process)
        }

        private fun readPem(reader: BufferedReader): String {
            val begin = reader.readLine()
            if (begin != "--- kurpc-testserver tls certificate ---") {
                throw IllegalStateException("expected tls certificate banner, got $begin")
            }
            val pem = StringBuilder()
            while (true) {
                val line = reader.readLine() ?: throw IllegalStateException("eof in tls certificate")
                if (line == "--- end kurpc-testserver tls certificate ---") {
                    break
                }
                pem.append(line).append('\n')
            }
            return pem.toString()
        }
    }
}
