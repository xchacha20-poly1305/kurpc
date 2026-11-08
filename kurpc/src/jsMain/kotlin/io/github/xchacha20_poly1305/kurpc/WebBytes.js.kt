package io.github.xchacha20_poly1305.kurpc

// A Kotlin/JS ByteArray is an Int8Array; a typed array built from another converts every element.
internal actual fun ByteArray.toWebBytes(): WebBytes = copyToUint8Array(this)

internal actual fun WebBytes.toByteArray(): ByteArray = copyToInt8Array(this)

@Suppress("UNUSED_PARAMETER")
private fun copyToUint8Array(bytes: ByteArray): WebBytes = js("new Uint8Array(bytes)")

@Suppress("UNUSED_PARAMETER")
private fun copyToInt8Array(bytes: WebBytes): ByteArray = js("new Int8Array(bytes)")
