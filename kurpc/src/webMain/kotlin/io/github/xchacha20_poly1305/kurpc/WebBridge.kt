@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.xchacha20_poly1305.kurpc

import kotlin.js.JsAny
import kotlin.js.Promise

internal external interface WebBridge : JsAny {
    fun metadata(): WebMetadata

    fun validateUrl(value: String): String

    fun open(
        url: String,
        socketUrl: String?,
        path: String,
        useSocket: Boolean,
        request: WebBytes?,
        headers: WebMetadata,
        timeout: Double,
        maxMessage: Int,
        queueBytes: Int,
        queueMessages: Int,
        sendBuffer: Int,
    ): WebCall
}

/** A JavaScript `Uint8Array` holding one encoded message. */
internal external interface WebBytes : JsAny {
    val length: Int
}

/**
 * Both directions copy the whole message in one step: a call into JavaScript per byte would
 * dominate the cost of large messages on Wasm.
 */
internal expect fun ByteArray.toWebBytes(): WebBytes

internal expect fun WebBytes.toByteArray(): ByteArray

internal external interface WebMetadata : JsAny {
    val size: Int
    fun key(index: Int): String
    fun value(index: Int): String
    fun add(key: String, value: String)
}

internal external interface WebCall : JsAny {
    fun next(): Promise<WebResult>
    fun send(message: WebBytes): Promise<WebResult>
    fun halfClose()
    fun cancel()
    fun close()
}

internal external interface WebResult : JsAny {
    val kind: String
    val message: WebBytes?
    val headers: WebMetadata
    val trailers: WebMetadata
    val code: Int
    val description: String
}
