package io.github.xchacha20_poly1305.kurpc

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Loads `libkurpc`. Order is an explicit [Kurpc.loadLibrary] path, then `kurpc.library.path`,
 * then the classpath resource extracted under `java.io.tmpdir`, then `System.loadLibrary`.
 *
 * The resource directory and file name match `hostClassifier` / `libraryFileName` in the Gradle
 * plugin: `io/github/xchacha20_poly1305/kurpc/natives/<os>-<arch>/<libname>`.
 */
internal object NativeLibrary {
    private val lock = Any()
    private var loaded = false

    fun ensureLoaded() {
        synchronized(lock) {
            if (loaded) {
                return
            }
            val property = System.getProperty("kurpc.library.path")
            if (!property.isNullOrEmpty()) {
                loadFile(property)
            } else if (!loadFromClasspath()) {
                System.loadLibrary("kurpc")
            }
            loaded = true
        }
    }

    fun loadExplicit(path: String) {
        synchronized(lock) {
            if (loaded) {
                throw IllegalStateException("kurpc native library is already loaded")
            }
            loadFile(path)
            loaded = true
        }
    }

    private fun loadFromClasspath(): Boolean {
        val resource = "io/github/xchacha20_poly1305/kurpc/natives/${classifier()}/${libraryFileName()}"
        val stream = NativeLibrary::class.java.classLoader.getResourceAsStream(resource) ?: return false
        val bytes = stream.use { it.readBytes() }
        val file = extract(bytes)
        loadFile(file.absolutePath)
        return true
    }

    /** `java.io.tmpdir/kurpc-<version>-<sha256>/`, reused when the file is already the same length. */
    private fun extract(bytes: ByteArray): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "kurpc-$KURPC_VERSION-${sha256Hex(bytes)}")
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw IllegalStateException("could not create $dir")
        }
        val dest = File(dir, libraryFileName())
        if (dest.isFile && dest.length() == bytes.size.toLong()) {
            return dest
        }
        val tmp = File(dir, libraryFileName() + ".tmp." + System.nanoTime())
        FileOutputStream(tmp).use { it.write(bytes) }
        if (!tmp.renameTo(dest)) {
            tmp.delete()
            if (!dest.isFile || dest.length() != bytes.size.toLong()) {
                throw IllegalStateException("could not install $dest")
            }
        }
        return dest
    }
}

/**
 * Every source except the `System.loadLibrary` fallback is a file outside `java.library.path`
 * (a caller-chosen path or the copy extracted from the jar), so it has to be loaded by path.
 */
@Suppress("UnsafeDynamicallyLoadedCode")
private fun loadFile(path: String) {
    System.load(path)
}

internal actual fun ensureNativeLibraryLoaded() {
    NativeLibrary.ensureLoaded()
}

public fun Kurpc.loadLibrary(path: String) {
    NativeLibrary.loadExplicit(path)
}

private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val digits = "0123456789abcdef"
    val hex = CharArray(digest.size * 2)
    for (index in digest.indices) {
        val value = digest[index].toInt() and 0xff
        hex[index * 2] = digits[value ushr 4]
        hex[index * 2 + 1] = digits[value and 0x0f]
    }
    return String(hex)
}

/** `<os>-<arch>`. Keep in step with `KurpcNativePlugin.hostClassifier`. */
private fun classifier(): String {
    val os = System.getProperty("os.name") ?: ""
    val arch = System.getProperty("os.arch") ?: ""
    val osName = when {
        os.startsWith("Windows") -> "windows"
        os.startsWith("Mac") -> "macos"
        else -> "linux"
    }
    val archName = when (arch) {
        "amd64", "x86_64" -> "x86_64"
        "aarch64", "arm64" -> "aarch64"
        "x86", "i386", "i686" -> "x86"
        else -> arch
    }
    return "$osName-$archName"
}

/** Keep in step with `KurpcNativePlugin.libraryFileName`. */
private fun libraryFileName(): String {
    val os = System.getProperty("os.name") ?: ""
    return when {
        os.startsWith("Windows") -> "kurpc.dll"
        os.startsWith("Mac") -> "libkurpc.dylib"
        else -> "libkurpc.so"
    }
}
