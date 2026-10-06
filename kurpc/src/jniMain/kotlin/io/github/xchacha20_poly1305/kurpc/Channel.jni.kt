package io.github.xchacha20_poly1305.kurpc

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel as MessageChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

private const val TRANSPORT_TCP: Int = 0
private const val TRANSPORT_UNIX: Int = 1
private const val TRANSPORT_WINDOWS_NAMED_PIPE: Int = 2
private const val TRANSPORT_CUSTOM: Int = 3

/** Matches `kurpc_core::DEFAULT_INITIAL_DEMAND`. One `callRequest` per consumed element refills it. */
private const val INITIAL_DEMAND: Int = 16

private const val FAILURE_STATUS: Int = 0
private const val FAILURE_TRANSPORT: Int = 1
private const val FAILURE_CLOSED: Int = 2
private const val FAILURE_INVALID_ARGUMENT: Int = 3

public actual class Channel actual constructor(config: ChannelConfig) : AutoCloseable {
    private val lock = Any()
    private val handle: NativeHandle
    private var closed = false

    init {
        val nativeConfig = toNative(config)
        ensureNativeLibraryLoaded()
        val dialer = RelayDialer.forTransport(config.transport)
        val raw = try {
            NativeBridge.channelCreate(nativeConfig, dialer)
        } catch (error: Throwable) {
            dialer?.close()
            throw error
        }
        // Closing the native channel first drops its connections, which ends the relay pumps;
        // closing the dialer then stops connects still in progress.
        handle = NativeHandle(raw) { pointer ->
            NativeBridge.channelClose(pointer)
            dialer?.close()
        }
    }

    public actual suspend fun unary(
        method: String,
        request: ByteArray,
        options: CallOptions,
    ): ByteArray = unaryCall(method, request, options).message

    public actual suspend fun unaryCall(
        method: String,
        request: ByteArray,
        options: CallOptions,
    ): UnaryResponse = singleResponse(method, request, null, options)

    public actual suspend fun clientStreaming(
        method: String,
        requests: Flow<ByteArray>,
        options: CallOptions,
    ): ByteArray = singleResponse(method, null, requests, options).message

    public actual fun serverStreaming(
        method: String,
        request: ByteArray,
        options: CallOptions,
    ): Flow<ByteArray> = streamResponse(method, request, null, options)

    public actual fun bidiStreaming(
        method: String,
        requests: Flow<ByteArray>,
        options: CallOptions,
    ): Flow<ByteArray> = streamResponse(method, null, requests, options)

    /**
     * Unary ([request] set) or client streaming ([requests] set). The call handle stays in this
     * coroutine and its children, and `finally` releases it after they are done; releasing
     * cancels a call that is still running, which is how coroutine cancellation reaches it.
     */
    private suspend fun singleResponse(
        method: String,
        request: ByteArray?,
        requests: Flow<ByteArray>?,
        options: CallOptions,
    ): UnaryResponse {
        val response = CompletableDeferred<UnaryResponse>()
        val call = withRaw { channel ->
            NativeBridge.unaryStart(
                channel,
                method,
                request,
                options.metadata.toNativeFlat(),
                timeoutMillis(options.timeout),
                options.waitForReady,
                UnaryToDeferred(response),
            )
        }
        try {
            if (requests == null) {
                return response.await()
            }
            return coroutineScope {
                val sender = launch { sendAll(call, requests) }
                // The server may answer before the requests end; then the rest is not sent.
                response.await().also { sender.cancel() }
            }
        } finally {
            NativeBridge.callRelease(call)
        }
    }

    /** Server streaming ([request] set) or bidi ([requests] set). Cold: one call per collection. */
    private fun streamResponse(
        method: String,
        request: ByteArray?,
        requests: Flow<ByteArray>?,
        options: CallOptions,
    ): Flow<ByteArray> = flow {
        // Native demand never exceeds INITIAL_DEMAND undelivered messages, so this never fills.
        val messages = MessageChannel<ByteArray>(INITIAL_DEMAND)
        val call = withRaw { channel ->
            NativeBridge.streamStart(
                channel,
                method,
                request,
                options.metadata.toNativeFlat(),
                timeoutMillis(options.timeout),
                options.waitForReady,
                INITIAL_DEMAND,
                StreamToChannel(messages),
            )
        }
        try {
            coroutineScope {
                val sender = requests?.let { launch { sendAll(call, it) } }
                for (message in messages) {
                    // `emit` returns once the collector is done with the element, which is when
                    // the next one may be produced.
                    emit(message)
                    NativeBridge.callRequest(call, 1)
                }
                sender?.cancel()
            }
        } finally {
            NativeBridge.callRelease(call)
        }
    }

    actual override fun close() {
        synchronized(lock) {
            if (closed) {
                return
            }
            closed = true
            handle.close()
        }
    }

    /** Borrows the channel under [lock] so `close` cannot free it mid-call. */
    private inline fun <T> withRaw(block: (Long) -> T): T = synchronized(lock) {
        if (closed) {
            throw ChannelClosedException()
        }
        block(handle.raw())
    }
}

private class UnaryToDeferred(private val response: CompletableDeferred<UnaryResponse>) : UnaryCallback {
    override fun onSuccess(message: ByteArray, headers: Array<String>, trailers: Array<String>) {
        response.complete(UnaryResponse(message, Metadata.fromNative(headers), Metadata.fromNative(trailers)))
    }

    override fun onFailure(
        kind: Int,
        code: Int,
        message: String,
        details: ByteArray,
        trailers: Array<String>,
    ) {
        response.completeExceptionally(callFailure(kind, code, message, details, trailers))
    }
}

/**
 * Sends [requests] in order, then half-closes. Stops early, without error, once the call takes
 * no more requests: the call has ended and its outcome is reported by the response side.
 * A failure of [requests] itself propagates and, through the caller's scope, cancels the call.
 */
private suspend fun sendAll(call: Long, requests: Flow<ByteArray>) {
    requests.takeWhile { message -> send(call, message) }.collect()
    NativeBridge.callCloseSend(call)
}

/** Whether the message was queued. The next send waits for this, which keeps the order. */
private suspend fun send(call: Long, message: ByteArray): Boolean = suspendCancellableCoroutine { continuation ->
    NativeBridge.callSend(
        call,
        message,
        object : SendCallback {
            override fun onSent(queued: Boolean) {
                continuation.resume(queued)
            }
        },
    )
}

/** Callbacks run one at a time on runtime workers; `trySend` never blocks them. */
private class StreamToChannel(
    private val messages: SendChannel<ByteArray>,
) : StreamCallback {
    override fun onHeaders(headers: Array<String>) = Unit

    override fun onMessage(message: ByteArray) {
        val result = messages.trySend(message)
        if (result.isFailure && !result.isClosed) {
            messages.close(IllegalStateException("native stream delivered past its demand"))
        }
    }

    override fun onComplete(trailers: Array<String>) {
        messages.close()
    }

    override fun onFailure(
        kind: Int,
        code: Int,
        message: String,
        details: ByteArray,
        trailers: Array<String>,
    ) {
        messages.close(callFailure(kind, code, message, details, trailers))
    }
}

/**
 * Frees [pointer] exactly once, from [close] or from [NativeCleaner] if the owner is abandoned.
 * The cleaner action closes over the pointer and the flag, not this instance.
 */
internal class NativeHandle(
    private val pointer: Long,
    private val release: (Long) -> Unit,
) {
    private val released = AtomicBoolean(false)
    private val cleanup: Cleanup

    init {
        val pointerLocal = pointer
        val releaseLocal = release
        val releasedLocal = released
        cleanup = NativeCleaner.register(this) {
            if (releasedLocal.compareAndSet(false, true)) {
                releaseLocal(pointerLocal)
            }
        }
    }

    fun raw(): Long = pointer

    fun close() {
        if (released.compareAndSet(false, true)) {
            cleanup.detach()
            release(pointer)
        }
    }
}

private fun timeoutMillis(timeout: Duration?): Long {
    val millis = timeout?.inWholeMilliseconds ?: return -1L
    return if (millis < 0L) -1L else millis
}

private fun positiveMillis(name: String, duration: Duration): Long {
    val millis = duration.inWholeMilliseconds
    if (millis < 0L) {
        throw IllegalArgumentException("$name must not be negative")
    }
    return millis
}

private fun toNative(config: ChannelConfig): NativeChannelConfig {
    val transport = config.transport
    val kind: Int
    val address: String
    val port: Int
    when (transport) {
        is Transport.Tcp -> {
            kind = TRANSPORT_TCP
            address = transport.host
            port = transport.port
        }
        is Transport.Unix -> {
            if (isWindows()) {
                throw IllegalArgumentException("Unix sockets are not supported on this platform")
            }
            kind = TRANSPORT_UNIX
            address = transport.path
            port = 0
        }
        is Transport.WindowsNamedPipe -> {
            if (!isWindows()) {
                throw IllegalArgumentException("Windows named pipes are not supported on this platform")
            }
            kind = TRANSPORT_WINDOWS_NAMED_PIPE
            address = transport.name
            port = 0
        }
        is Transport.Custom -> {
            kind = TRANSPORT_CUSTOM
            address = ""
            port = 0
        }
        is Transport.FileDescriptor -> {
            if (isWindows()) {
                throw IllegalArgumentException("file descriptor transport is not supported on this platform")
            }
            kind = TRANSPORT_CUSTOM
            address = ""
            port = 0
        }
    }
    val keepAlive = config.keepAlive
    return NativeChannelConfig(
        transport = kind,
        address = address,
        port = port,
        authority = config.authority,
        secure = config.secure,
        metadata = config.metadata.toNativeFlat(),
        connectTimeoutMs = positiveMillis("connectTimeout", config.connectTimeout),
        keepAliveIntervalMs = if (keepAlive == null) 0L else positiveMillis("keepAlive.interval", keepAlive.interval),
        keepAliveTimeoutMs = if (keepAlive == null) 0L else positiveMillis("keepAlive.timeout", keepAlive.timeout),
        keepAliveWhileIdle = keepAlive?.whileIdle == true,
        userAgent = config.userAgent,
    )
}

private fun isWindows(): Boolean = osName().startsWith("Windows")

private fun osName(): String = System.getProperty("os.name") ?: ""

internal fun callFailure(
    kind: Int,
    code: Int,
    message: String,
    details: ByteArray,
    trailers: Array<String>,
): Throwable = when (kind) {
    FAILURE_STATUS -> StatusException(
        Status.Code.fromValue(code),
        message,
        details.copyOf(),
        Metadata.fromNative(trailers),
    )
    FAILURE_TRANSPORT -> TransportException(message)
    FAILURE_CLOSED -> ChannelClosedException(message)
    FAILURE_INVALID_ARGUMENT -> IllegalArgumentException(message)
    else -> IllegalStateException("unknown failure kind $kind: $message")
}
