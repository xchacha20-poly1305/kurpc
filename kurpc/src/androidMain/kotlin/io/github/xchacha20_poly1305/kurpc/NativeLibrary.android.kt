package io.github.xchacha20_poly1305.kurpc

/** The AAR ships `libkurpc.so` per ABI; the installer puts it on the app's library path. */
private val loaded: Unit by lazy { System.loadLibrary("kurpc") }

internal actual fun ensureNativeLibraryLoaded() {
    loaded
}
