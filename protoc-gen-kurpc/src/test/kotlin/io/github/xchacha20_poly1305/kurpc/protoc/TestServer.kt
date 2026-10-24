package io.github.xchacha20_poly1305.kurpc.protoc

import io.github.xchacha20_poly1305.kurpc.Channel
import io.github.xchacha20_poly1305.kurpc.ChannelConfig
import io.github.xchacha20_poly1305.kurpc.Transport
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/** A `kurpc-testserver` subprocess on a TCP port. It exits when its stdin closes. */
internal class TestServer : AutoCloseable {
    private val process: Process
    private val host: String
    private val port: Int

    init {
        val binary = System.getProperty("kurpc.testserver")
            ?: throw IllegalStateException("kurpc.testserver system property is not set")
        process = ProcessBuilder(binary, "--tcp", "127.0.0.1:0")
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        val banner = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).readLine()
        if (banner == null || !banner.startsWith(LISTENING)) {
            process.destroyForcibly()
            throw IllegalStateException("expected listening banner, got ${banner ?: "EOF"}")
        }
        val address = banner.removePrefix(LISTENING)
        host = address.substringBeforeLast(':')
        port = address.substringAfterLast(':').toInt()
    }

    fun channel(): Channel = Channel(ChannelConfig(Transport.Tcp(host, port)))

    override fun close() {
        process.outputStream.close()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
        }
    }

    private companion object {
        const val LISTENING = "listening "
    }
}
