package io.github.xchacha20_poly1305.kurpc

public object Kurpc {
    /**
     * Worker count of the native runtime. The runtime starts with the first [Channel] and its
     * worker count is fixed from then on, so a call after that throws [IllegalStateException].
     */
    public fun configure(workerThreads: Int) {
        if (workerThreads < 1) {
            throw IllegalArgumentException("workerThreads must be positive, got $workerThreads")
        }
        configureWorkerThreads(workerThreads)
    }
}

internal expect fun configureWorkerThreads(workerThreads: Int)
