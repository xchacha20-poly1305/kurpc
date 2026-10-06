package io.github.xchacha20_poly1305.kurpc

/**
 * JNI entry points. The object may be internal; the `external` members must stay public so Kotlin
 * does not mangle their JVM names. Each one is documented on the matching export in `bridge.rs`.
 */
internal object NativeBridge {
    /** [dialer] is required for `TRANSPORT_CUSTOM` and ignored otherwise. */
    public external fun channelCreate(config: NativeChannelConfig, dialer: NativeDialer?): Long

    public external fun channelClose(channel: Long)

    public external fun configure(workerThreads: Int)

    /** `[channels, calls, dial requests, pipes]` still owned by Kotlin. */
    public external fun debugLiveHandles(): LongArray

    /** A `null` [request] starts a client-streaming call fed by [callSend]. */
    public external fun unaryStart(
        channel: Long,
        method: String,
        request: ByteArray?,
        metadata: Array<String>,
        timeoutMs: Long,
        waitForReady: Boolean,
        callback: UnaryCallback,
    ): Long

    /** A `null` [request] starts a bidi call fed by [callSend]. */
    public external fun streamStart(
        channel: Long,
        method: String,
        request: ByteArray?,
        metadata: Array<String>,
        timeoutMs: Long,
        waitForReady: Boolean,
        initialDemand: Int,
        callback: StreamCallback,
    ): Long

    public external fun callRequest(call: Long, n: Int)

    /** Queues one request; [SendCallback.onSent] reports whether it was taken. */
    public external fun callSend(call: Long, message: ByteArray, callback: SendCallback)

    /** Ends the request stream (half-close). */
    public external fun callCloseSend(call: Long)

    /** Cancels the call if it is still running, then frees the handle. */
    public external fun callRelease(call: Long)

    /** Answers a dial with a pipe; `0` if the dial was abandoned. Consumes [request]. */
    public external fun dialStream(request: Long): Long

    /** Answers a dial with a connected socket owned by kurpc from here on. Consumes [request]. */
    public external fun dialFd(request: Long, fd: Int)

    /** Fails a dial; calls see a [TransportException] with [message]. Consumes [request]. */
    public external fun dialFail(request: Long, message: String)

    /** Blocks until tonic wrote bytes; `-1` once it dropped the connection. */
    public external fun pipeRead(pipe: Long, buffer: ByteArray, offset: Int, length: Int): Int

    /** Blocks until tonic took all bytes; `false` once it dropped the connection. */
    public external fun pipeWrite(pipe: Long, buffer: ByteArray, offset: Int, length: Int): Boolean

    /** End of stream toward tonic, which then drops the connection. */
    public external fun pipeShutdown(pipe: Long)

    /** After both pump directions have returned. */
    public external fun pipeRelease(pipe: Long)
}

/**
 * Asked for every connection of a `TRANSPORT_CUSTOM` channel, on a runtime worker. Must return
 * at once and later answer [request] exactly once with `dialStream`, `dialFd` or `dialFail`.
 */
internal interface NativeDialer {
    public fun dial(request: Long)
}

/** Fields match `convert.rs` `channel_config`; `META-INF/proguard/kurpc.pro` keeps their names. */
internal class NativeChannelConfig(
    @JvmField public val transport: Int,
    @JvmField public val address: String,
    @JvmField public val port: Int,
    @JvmField public val authority: String,
    @JvmField public val secure: Boolean,
    @JvmField public val metadata: Array<String>,
    @JvmField public val connectTimeoutMs: Long,
    @JvmField public val keepAliveIntervalMs: Long,
    @JvmField public val keepAliveTimeoutMs: Long,
    @JvmField public val keepAliveWhileIdle: Boolean,
    @JvmField public val userAgent: String?,
)

/** `kind` is 0 status, 1 transport, 2 closed, 3 invalid argument. */
internal interface CallFailureCallback {
    public fun onFailure(
        kind: Int,
        code: Int,
        message: String,
        details: ByteArray,
        trailers: Array<String>,
    )
}

/** Runs once per `callSend`, on a runtime worker. `false`: the call takes no more requests. */
internal interface SendCallback {
    public fun onSent(queued: Boolean)
}

/** Exactly one of [onSuccess] and [onFailure] runs, on a runtime worker. */
internal interface UnaryCallback : CallFailureCallback {
    public fun onSuccess(message: ByteArray, headers: Array<String>, trailers: Array<String>)
}

/**
 * [onHeaders] at most once, then [onMessage] per message, then exactly one of [onComplete]
 * (status OK) and [onFailure]. All run on runtime workers, one at a time.
 */
internal interface StreamCallback : CallFailureCallback {
    public fun onHeaders(headers: Array<String>)

    public fun onMessage(message: ByteArray)

    public fun onComplete(trailers: Array<String>)
}
