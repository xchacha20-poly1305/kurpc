package io.github.xchacha20_poly1305.kurpc

import java.lang.ref.PhantomReference
import java.lang.ref.ReferenceQueue
import java.util.Collections
import java.util.HashSet

/**
 * Releases a native handle after its Kotlin owner becomes unreachable.
 *
 * `java.lang.ref.Cleaner` is Java 9 and Android API 33, so this is a `PhantomReference`, a
 * `ReferenceQueue`, and one daemon. Phantom references are retained here: nothing else points at
 * them, and an unreferenced phantom is never enqueued. The cleanup action must not capture the
 * referent, or the referent can never be collected.
 */
internal object NativeCleaner {
    private class NativeRef(
        referent: Any,
        queue: ReferenceQueue<Any>,
        val action: () -> Unit,
    ) : PhantomReference<Any>(referent, queue)

    private val queue = ReferenceQueue<Any>()
    private val refs = Collections.synchronizedSet(HashSet<NativeRef>())

    init {
        val thread = Thread {
            while (true) {
                val ref = try {
                    queue.remove()
                } catch (_: InterruptedException) {
                    return@Thread
                } ?: continue
                val nativeRef = ref as NativeRef
                refs.remove(nativeRef)
                nativeRef.clear()
                try {
                    nativeRef.action()
                } catch (error: Throwable) {
                    System.err.println("kurpc: native cleanup failed: $error")
                }
            }
        }
        thread.name = "kurpc-cleaner"
        thread.isDaemon = true
        thread.start()
    }

    /** [action] runs at most once, on the cleaner thread, and must not capture [referent]. */
    fun register(referent: Any, action: () -> Unit): Cleanup {
        val ref = NativeRef(referent, queue, action)
        refs.add(ref)
        return Cleanup {
            refs.remove(ref)
            ref.clear()
        }
    }
}

/** Drops the phantom so an explicit release is not repeated when the referent is collected. */
internal fun interface Cleanup {
    public fun detach()
}
