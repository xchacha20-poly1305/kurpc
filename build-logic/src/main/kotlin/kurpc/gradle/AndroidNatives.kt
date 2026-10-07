package kurpc.gradle

import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

/** API level of the NDK clang wrappers. Matches minSdk (plan §5.7). */
private const val ANDROID_API: Int = 21

internal enum class AndroidAbi(
    val abi: String,
    val triple: String,
    /** Prefix of the NDK clang wrapper, which differs from the Rust triple only for armv7. */
    val clangTarget: String,
) {
    ARM64_V8A("arm64-v8a", "aarch64-linux-android", "aarch64-linux-android"),
    ARMEABI_V7A("armeabi-v7a", "armv7-linux-androideabi", "armv7a-linux-androideabi"),
    X86("x86", "i686-linux-android", "i686-linux-android"),
    X86_64("x86_64", "x86_64-linux-android", "x86_64-linux-android"),
    ;

    val taskSuffix: String = abi.split('-').joinToString("") { part ->
        part.replaceFirstChar { it.uppercaseChar() }
    }
}

/**
 * Release `libkurpc.so` for each selected ABI into the AAR, and a `kurpc-testserver` executable into the
 * device-test APK as `libkurpc_testserver.so`, so instrumented tests can start it from
 * `nativeLibraryDir` (the only place an app may execute files from on every API level).
 */
internal fun registerAndroidNatives(
    project: Project,
    sources: FileCollection,
    abis: List<AndroidAbi>,
) {
    val components = project.extensions.getByType<KotlinMultiplatformAndroidComponentsExtension>()
    val ndkVersion = project.extensions.getByType<VersionCatalogsExtension>()
        .named("libs").findVersion("ndk").get().requiredVersion
    val ndk = components.sdkComponents.sdkDirectory.map { it.dir("ndk/$ndkVersion") }

    val libraries = abis.map { abi ->
        project.tasks.register<CargoBuildTask>("cargoBuildJniAndroid${abi.taskSuffix}") {
            group = "build"
            description = "Builds libkurpc for Android ${abi.abi}."
            configureAndroidCargo(project, sources, abi, ndk)
            cargoPackage.set("kurpc-jni")
            release.set(true)
            artifactFileName.set("libkurpc.so")
            outputDirectory.set(project.layout.buildDirectory.dir("jniLibs/main/${abi.abi}"))
            outputPath.set("${abi.abi}/libkurpc.so")
        }
    }
    val testServers = abis.map { abi ->
        project.tasks.register<CargoBuildTask>("cargoBuildTestServerAndroid${abi.taskSuffix}") {
            group = "build"
            description = "Builds kurpc-testserver for Android ${abi.abi} device tests."
            configureAndroidCargo(project, sources, abi, ndk)
            cargoPackage.set("kurpc-testserver")
            // Debug avoids fat LTO for a test-only binary; stripping keeps the test APK small.
            release.set(false)
            extraEnvironment.put("CARGO_PROFILE_DEV_STRIP", "symbols")
            artifactFileName.set("kurpc-testserver")
            outputDirectory.set(project.layout.buildDirectory.dir("jniLibs/deviceTest/${abi.abi}"))
            // Packaged as a library so the installer extracts it next to libkurpc.so.
            outputPath.set("${abi.abi}/libkurpc_testserver.so")
        }
    }

    components.onVariants { variant ->
        val jniLibs = variant.sources.jniLibs ?: return@onVariants
        libraries.forEach { task ->
            jniLibs.addGeneratedSourceDirectory(task, CargoBuildTask::outputDirectory)
        }
        variant.deviceTests.values.forEach { deviceTest ->
            val testJniLibs = deviceTest.sources.jniLibs ?: return@forEach
            testServers.forEach { task ->
                testJniLibs.addGeneratedSourceDirectory(task, CargoBuildTask::outputDirectory)
            }
        }
    }
}

private fun CargoBuildTask.configureAndroidCargo(
    project: Project,
    sources: FileCollection,
    abi: AndroidAbi,
    ndk: Provider<Directory>,
) {
    executable.set("cargo")
    cargoSubcommand.set("build")
    extraArguments.set(emptyList())
    workspaceDirectory.set(project.rootProject.layout.projectDirectory)
    targetTriple.set(abi.triple)
    this.sources.setFrom(sources)
    extraEnvironment.putAll(ndk.map { ndkEnvironment(it, abi) })
}

/**
 * Linker, C compiler (ring in the device-test server builds C and assembly) and archiver from
 * the NDK. NDK r28+ links 64-bit ABIs with 16 KB page alignment by default, which is what
 * Android 15+ devices need; 32-bit ABIs never run on 16 KB page kernels.
 */
private fun ndkEnvironment(ndk: Directory, abi: AndroidAbi): Map<String, String> {
    val windows = System.getProperty("os.name").startsWith("Windows")
    val hostTag = when {
        windows -> "windows-x86_64"
        System.getProperty("os.name").startsWith("Mac") -> "darwin-x86_64"
        else -> "linux-x86_64"
    }
    val bin = ndk.dir("toolchains/llvm/prebuilt/$hostTag/bin").asFile
    val clang = bin.resolve("${abi.clangTarget}$ANDROID_API-clang${if (windows) ".cmd" else ""}").path
    val archiver = bin.resolve("llvm-ar${if (windows) ".exe" else ""}").path
    val cargoTriple = abi.triple.uppercase().replace('-', '_')
    val ccTriple = abi.triple.replace('-', '_')
    return mapOf(
        "CARGO_TARGET_${cargoTriple}_LINKER" to clang,
        "CC_$ccTriple" to clang,
        "AR_$ccTriple" to archiver,
    )
}
