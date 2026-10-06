package io.github.xchacha20_poly1305.kurpc

import java.net.InetAddress
import java.net.ServerSocket
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import org.junit.jupiter.api.Assumptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds

internal const val ECHO: String = "/kurpc.test.Test/Echo"
internal const val FAIL: String = "/kurpc.test.Test/Fail"
internal const val SLEEP: String = "/kurpc.test.Test/Sleep"
internal const val COUNT: String = "/kurpc.test.Test/Count"
internal const val INFINITE: String = "/kurpc.test.Test/Infinite"
internal const val STATS: String = "/kurpc.test.Test/Stats"
internal const val COLLECT: String = "/kurpc.test.Test/Collect"
internal const val CHAT: String = "/kurpc.test.Test/Chat"
internal const val SCHEME: String = "/kurpc.test.Test/Scheme"

/**
 * Configures the runtime before the first channel of the test JVM, whichever test class runs
 * first: the first channel starts the runtime and freezes the worker count.
 */
private object TestRuntime {
    init {
        Kurpc.configure(2)
    }

    fun ensureConfigured() = Unit
}

internal enum class HostOs { LINUX, MACOS, WINDOWS }

internal val hostOs: HostOs = System.getProperty("os.name").let { name ->
    when {
        name.startsWith("Windows") -> HostOs.WINDOWS
        name.startsWith("Mac") -> HostOs.MACOS
        else -> HostOs.LINUX
    }
}

/** Skips the calling test unless it runs on one of [systems]. */
internal fun assumeHost(vararg systems: HostOs) {
    Assumptions.assumeTrue(hostOs in systems, "needs one of ${systems.toList()}, running on $hostOs")
}

internal fun test(body: suspend CoroutineScope.() -> Unit) {
    TestRuntime.ensureConfigured()
    runBlocking {
        withTimeout(30.seconds) {
            body()
        }
    }
}

/** Keeps the channel off the caller's frame so the cleaner can observe it. */
internal fun abandonChannel() {
    Channel(ChannelConfig(Transport.Tcp("127.0.0.1", 1), connectTimeout = 1.seconds))
}


internal fun freePort(): Int {
    val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val port = socket.localPort
    socket.close()
    return port
}

internal suspend fun stats(channel: Channel): Map<String, Long> {
    val text = channel.unary(STATS, ByteArray(0), CallOptions(timeout = 5.seconds)).decodeToString()
    val out = HashMap<String, Long>()
    for (line in text.split('\n')) {
        val eq = line.indexOf('=')
        if (eq <= 0) {
            continue
        }
        out[line.substring(0, eq)] = line.substring(eq + 1).trim().toLong()
    }
    return out
}

internal suspend fun awaitStat(channel: Channel, name: String, atLeast: Long) {
    withTimeout(5.seconds) {
        while ((stats(channel)[name] ?: 0L) < atLeast) {
            delay(20.milliseconds)
        }
    }
}

internal suspend fun awaitIdleHandles() {
    withTimeout(5.seconds) {
        while (true) {
            if (NativeBridge.debugLiveHandles().all { it == 0L }) {
                return@withTimeout
            }
            delay(20.milliseconds)
        }
    }
}


