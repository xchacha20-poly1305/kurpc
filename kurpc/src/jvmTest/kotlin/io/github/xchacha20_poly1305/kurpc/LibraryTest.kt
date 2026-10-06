package io.github.xchacha20_poly1305.kurpc

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

public class LibraryTest {
    @Test
    public fun loadsLibraryFromExtractedResource() {
        // The test JVM sets no `kurpc.library.path`, so this takes the classpath resource.
        ensureNativeLibraryLoaded()
        val extracted = File(System.getProperty("java.io.tmpdir"))
            .listFiles { file -> file.name.startsWith("kurpc-$KURPC_VERSION-") }
            .orEmpty()
        assertTrue(extracted.isNotEmpty(), "nothing extracted to the versioned directory")
        if (hostOs == HostOs.LINUX) {
            // Proof that the extracted copy is the one in the process, not just a file on disk.
            val maps = File("/proc/self/maps").readText()
            assertTrue(maps.contains("/kurpc-$KURPC_VERSION-"), "library was not loaded from the extract directory")
        }
    }
}
