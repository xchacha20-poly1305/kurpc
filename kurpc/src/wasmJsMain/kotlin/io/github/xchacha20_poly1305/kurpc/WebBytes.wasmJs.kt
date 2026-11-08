@file:OptIn(ExperimentalWasmJsInterop::class, UnsafeWasmMemoryApi::class)

package io.github.xchacha20_poly1305.kurpc

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.WebAssembly
import kotlin.wasm.unsafe.wasmMemory
import kotlin.wasm.unsafe.withScopedMemoryAllocator

// A Wasm GC array is opaque to JavaScript, so messages cross through linear memory, which both
// sides can address. The scoped allocation is released as soon as the copy is done.

internal actual fun ByteArray.toWebBytes(): WebBytes = withScopedMemoryAllocator { allocator ->
    val start = allocator.allocate(size)
    for (index in indices) {
        (start + index).storeByte(this[index])
    }
    copyFromMemory(wasmMemory, start.address.toInt(), size)
}

internal actual fun WebBytes.toByteArray(): ByteArray = withScopedMemoryAllocator { allocator ->
    val start = allocator.allocate(length)
    copyToMemory(wasmMemory, this, start.address.toInt())
    ByteArray(length) { index -> (start + index).loadByte() }
}

// memory.buffer is read here, after the allocation: growing the memory replaces the buffer.

@JsFun("(memory, address, length) => new Uint8Array(memory.buffer, address, length).slice()")
private external fun copyFromMemory(memory: WebAssembly.Memory, address: Int, length: Int): WebBytes

@JsFun("(memory, bytes, address) => new Uint8Array(memory.buffer).set(bytes, address)")
private external fun copyToMemory(memory: WebAssembly.Memory, bytes: WebBytes, address: Int)
