package io.github.xchacha20_poly1305.kurpc

/** JVM extracts a classpath resource; Android (phase 3) loads the packaged library directly. */
internal expect fun ensureNativeLibraryLoaded()

internal actual fun configureWorkerThreads(workerThreads: Int) {
    ensureNativeLibraryLoaded()
    NativeBridge.configure(workerThreads)
}
