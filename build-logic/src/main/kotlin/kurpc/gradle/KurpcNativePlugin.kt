package kurpc.gradle

import java.time.Duration
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.newInstance
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.gradle.process.CommandLineArgumentProvider

/** Resource directory of the native library inside a jar. Matches `NativeLibrary` in jvmMain. */
const val NATIVES_RESOURCE_DIR: String = "io/github/xchacha20_poly1305/kurpc/natives"

/**
 * Builds `kurpc-jni` and `kurpc-testserver` with cargo and wires them into the module.
 *
 * - Host: debug builds. `cargoBuildJni`'s output directory is a resource root
 *   (`<root>/io/github/xchacha20_poly1305/kurpc/natives/<os>-<arch>/<lib>`); the module adds it to
 *   the jvmTest resources so tests load the library the way a published jar does. The host
 *   test server path goes to every Test task, and `jvmTestJava8` reruns jvmTest on Java 8.
 * - Android ([registerAndroidNatives]): release libraries for four ABIs into the AAR, test
 *   servers into the device-test APK.
 * - Desktop ([registerDesktopNatives]): release zigbuild libraries as `kurpc-natives-jvm` jars,
 *   published by [registerNativesPublication]. `-Pkurpc.targets` ([TargetSelection]) limits both
 *   matrices.
 */
class KurpcNativePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val workspace = project.rootProject.layout.projectDirectory
        val sources = project.files(
            workspace.dir("native"),
            workspace.file("Cargo.lock"),
            workspace.file("Cargo.toml"),
            workspace.file("rust-toolchain.toml"),
        )
        val hostTriple = project.providers.exec {
            commandLine("rustc", "--print", "host-tuple")
        }.standardOutput.asText.map { it.trim() }

        val classifier = hostClassifier()
        val jni = project.tasks.register<CargoBuildTask>("cargoBuildJni") {
            group = "build"
            description = "Builds the host kurpc JNI library."
            configureHostCargo(project, sources, hostTriple)
            cargoPackage.set("kurpc-jni")
            artifactFileName.set(libraryFileName())
            outputDirectory.set(project.layout.buildDirectory.dir("natives/$classifier"))
            outputPath.set("$NATIVES_RESOURCE_DIR/$classifier/${libraryFileName()}")
        }
        val testServer = project.tasks.register<CargoBuildTask>("cargoBuildTestServer") {
            group = "build"
            description = "Builds the host kurpc-testserver binary."
            configureHostCargo(project, sources, hostTriple)
            cargoPackage.set("kurpc-testserver")
            artifactFileName.set(testServerFileName())
            outputDirectory.set(project.layout.buildDirectory.dir("testserver"))
            outputPath.set(testServerFileName())
            // Both run cargo in the same workspace; ordering avoids waiting on its lock.
            mustRunAfter(jni)
        }

        project.tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            timeout.set(Duration.ofMinutes(10))
            testLogging.exceptionFormat = TestExceptionFormat.FULL
            jvmArgumentProviders.add(
                project.objects.newInstance<TestServerArgument>().apply {
                    binary.set(testServer.flatMap { it.outputFile })
                },
            )
        }

        project.plugins.withId("org.jetbrains.kotlin.multiplatform") {
            registerJava8Test(project)
        }
        val targets = TargetSelection.parse(project.providers.gradleProperty("kurpc.targets").orNull)
        project.plugins.withId("com.android.kotlin.multiplatform.library") {
            registerAndroidNatives(project, sources, targets.android)
        }
        val nativesJars = registerDesktopNatives(project, sources, targets.desktop)
        project.plugins.withId("maven-publish") {
            registerNativesPublication(project, nativesJars)
        }
    }

    /** Same classes and classpath as `jvmTest`, on a Java 8 runtime (plan §5.6). */
    private fun registerJava8Test(project: Project) {
        val java8 = project.tasks.register<Test>("jvmTestJava8") {
            // Looked up here: `jvmTest` exists only once the module has declared `jvm()`.
            val jvmTest = project.tasks.named<Test>("jvmTest")
            group = "verification"
            description = "Runs jvmTest with a Java 8 runtime."
            testClassesDirs = project.files(jvmTest.map { it.testClassesDirs })
            classpath = project.files(jvmTest.map { it.classpath })
            javaLauncher.set(
                project.extensions.getByType<JavaToolchainService>().launcherFor {
                    languageVersion.set(JavaLanguageVersion.of(8))
                    // Temurin has no Apple Silicon JDK 8, so foojay falls back to an x64 one under
                    // Rosetta; `os.arch` is then x86_64 and the host aarch64 library is not found.
                    // Zulu 8 is native on every host this runs on.
                    vendor.set(JvmVendorSpec.AZUL)
                },
            )
        }
        project.tasks.named("check") { dependsOn(java8) }
    }
}

private fun CargoBuildTask.configureHostCargo(
    project: Project,
    sources: FileCollection,
    hostTriple: Provider<String>,
) {
    executable.set("cargo")
    cargoSubcommand.set("build")
    release.set(false)
    extraArguments.set(emptyList())
    extraEnvironment.set(emptyMap())
    workspaceDirectory.set(project.rootProject.layout.projectDirectory)
    targetTriple.set(hostTriple)
    this.sources.setFrom(sources)
}

/** `-Dkurpc.testserver=<path>` for jvm tests, tracked as a task input. */
abstract class TestServerArgument : CommandLineArgumentProvider {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val binary: RegularFileProperty

    override fun asArguments(): Iterable<String> =
        listOf("-Dkurpc.testserver=${binary.get().asFile.absolutePath}")
}

/** `<os>-<arch>` directory under [NATIVES_RESOURCE_DIR]. Matches `NativeLibrary`. */
internal fun hostClassifier(): String {
    val os = System.getProperty("os.name")
    val arch = System.getProperty("os.arch")
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

/** Shared-library file name cargo produces for the host. Matches `NativeLibrary`. */
internal fun libraryFileName(): String {
    val os = System.getProperty("os.name")
    return when {
        os.startsWith("Windows") -> "kurpc.dll"
        os.startsWith("Mac") -> "libkurpc.dylib"
        else -> "libkurpc.so"
    }
}

internal fun testServerFileName(): String =
    if (System.getProperty("os.name").startsWith("Windows")) "kurpc-testserver.exe" else "kurpc-testserver"
