package kurpc.gradle

import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.register

/** One `kurpc-natives-jvm` classifier (plan §6.1, §6.4). */
internal enum class DesktopTarget(
    val classifier: String,
    /** zigbuild triple; the Linux ones carry the glibc floor. */
    val triple: String,
    val libraryFileName: String,
) {
    LINUX_X86_64("linux-x86_64", "x86_64-unknown-linux-gnu.2.17", "libkurpc.so"),
    LINUX_AARCH64("linux-aarch64", "aarch64-unknown-linux-gnu.2.17", "libkurpc.so"),
    MACOS_AARCH64("macos-aarch64", "aarch64-apple-darwin", "libkurpc.dylib"),
    MACOS_X86_64("macos-x86_64", "x86_64-apple-darwin", "libkurpc.dylib"),
    WINDOWS_X86_64("windows-x86_64", "x86_64-pc-windows-gnu", "kurpc.dll"),
    WINDOWS_AARCH64("windows-aarch64", "aarch64-pc-windows-gnullvm", "kurpc.dll"),
    ;

    val taskSuffix: String = classifier.split('-').joinToString("") { part ->
        part.replaceFirstChar { it.uppercaseChar() }
    }
}

/**
 * `-Pkurpc.targets`: `host` (default), `all`, or a comma-separated list of desktop classifiers
 * (`linux-x86_64`) and Android ABIs prefixed with `android-` (`android-arm64-v8a`).
 *
 * `host` selects the host's desktop classifier and every Android ABI: Android natives are only
 * built when an Android artifact is, and an AAR without all four ABIs is not one to ship.
 */
internal class TargetSelection(val desktop: List<DesktopTarget>, val android: List<AndroidAbi>) {
    companion object {
        fun parse(value: String?): TargetSelection {
            val requested = value?.trim().orEmpty().ifEmpty { "host" }
            return when (requested) {
                "host" -> TargetSelection(
                    DesktopTarget.entries.filter { it.classifier == hostClassifier() },
                    AndroidAbi.entries,
                )
                "all" -> TargetSelection(DesktopTarget.entries, AndroidAbi.entries)
                else -> {
                    val names = requested.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                    val desktop = DesktopTarget.entries.filter { it.classifier in names }
                    val android = AndroidAbi.entries.filter { "android-${it.abi}" in names }
                    val known = desktop.map { it.classifier } + android.map { "android-${it.abi}" }
                    val unknown = names - known.toSet()
                    require(unknown.isEmpty()) {
                        "unknown kurpc.targets entries $unknown; expected host, all, " +
                            "${DesktopTarget.entries.map { it.classifier }} or " +
                            "${AndroidAbi.entries.map { "android-${it.abi}" }}"
                    }
                    TargetSelection(desktop, android)
                }
            }
        }
    }
}

/**
 * Release libraries of the selected desktop targets, each packed as a `kurpc-natives-jvm` jar
 * with its classifier. The library sits at the resource path `NativeLibrary` extracts from.
 *
 * cargo-zigbuild is called directly; `-Pkurpc.zigbuild=<path>` picks a specific build (macOS
 * targets need one newer than 0.23.4, plan §6.2).
 */
internal fun registerDesktopNatives(
    project: Project,
    sources: FileCollection,
    targets: List<DesktopTarget>,
): List<TaskProvider<Jar>> {
    val zigbuild = project.providers.gradleProperty("kurpc.zigbuild").orElse("cargo-zigbuild")
    val jars = targets.map { target ->
        val build = project.tasks.register<CargoBuildTask>("cargoBuildJni${target.taskSuffix}") {
            group = "build"
            description = "Builds release libkurpc for ${target.classifier}."
            executable.set(zigbuild)
            cargoSubcommand.set("zigbuild")
            release.set(true)
            cargoPackage.set("kurpc-jni")
            targetTriple.set(target.triple)
            extraArguments.set(emptyList())
            extraEnvironment.set(emptyMap())
            workspaceDirectory.set(project.rootProject.layout.projectDirectory)
            this.sources.setFrom(sources)
            artifactFileName.set(target.libraryFileName)
            outputDirectory.set(project.layout.buildDirectory.dir("natives-release/${target.classifier}"))
            outputPath.set("$NATIVES_RESOURCE_DIR/${target.classifier}/${target.libraryFileName}")
        }
        project.tasks.register<Jar>("nativesJar${target.taskSuffix}") {
            group = "build"
            description = "Packs the ${target.classifier} library as a kurpc-natives-jvm jar."
            archiveBaseName.set("kurpc-natives-jvm")
            archiveClassifier.set(target.classifier)
            destinationDirectory.set(project.layout.buildDirectory.dir("libs"))
            from(build.flatMap { it.outputDirectory })
        }
    }
    project.tasks.register("nativesJars") {
        group = "build"
        description = "Builds every kurpc-natives-jvm jar selected by kurpc.targets."
        dependsOn(jars)
    }
    return jars
}
