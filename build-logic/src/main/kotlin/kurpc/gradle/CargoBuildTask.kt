package kurpc.gradle

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

/**
 * Runs one cargo invocation and copies the crate's artifact out of `target/`.
 *
 * Desktop cross builds pass [cargoSubcommand] `zigbuild` and [release] `true`. Android builds
 * pass `build` and put the NDK linker in [extraEnvironment]. An empty [targetTriple] is the
 * host target (`target/<profile>/` rather than `target/<triple>/<profile>/`).
 *
 * When [prebuiltSource] is set (`-Pkurpc.prebuiltNatives=<dir>`), cargo is not invoked and the
 * file is copied to [outputFile] instead. Publish jobs use this to reuse a prior cross build.
 */
abstract class CargoBuildTask : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    /** `cargo`, or `cargo-zigbuild` called directly (it takes `zigbuild` as its first argument). */
    @get:Input
    abstract val executable: Property<String>

    /** `build` or `zigbuild`. */
    @get:Input
    abstract val cargoSubcommand: Property<String>

    @get:Input
    abstract val cargoPackage: Property<String>

    /** Rust target triple. Empty selects the host target and omits `--target`. */
    @get:Input
    @get:Optional
    abstract val targetTriple: Property<String>

    @get:Input
    abstract val release: Property<Boolean>

    @get:Input
    abstract val extraArguments: ListProperty<String>

    /** Overlay on the inherited process environment. An empty map leaves `PATH` alone. */
    @get:Input
    abstract val extraEnvironment: MapProperty<String, String>

    @get:Internal
    abstract val workspaceDirectory: DirectoryProperty

    /** File name cargo writes, for example `libkurpc.so` or `kurpc-testserver`. */
    @get:Input
    abstract val artifactFileName: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /**
     * Root the artifact is copied under. Consumers take the directory (a resource root, a
     * jniLibs root) rather than the file, so the task dependency travels with the provider.
     */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    /** Artifact path inside [outputDirectory]. */
    @get:Input
    abstract val outputPath: Property<String>

    /**
     * Prebuilt library copied to [outputFile] in place of a cargo build. Absent unless
     * `-Pkurpc.prebuiltNatives` is set.
     */
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val prebuiltSource: RegularFileProperty

    @get:Internal
    val outputFile: Provider<RegularFile>
        get() = outputDirectory.file(outputPath)

    @TaskAction
    fun build() {
        if (prebuiltSource.isPresent) {
            copyArtifact(prebuiltSource.get().asFile.toPath())
            return
        }

        val triple = targetTriple.orNull?.takeIf { it.isNotEmpty() }
        val profile = if (release.get()) "release" else "debug"
        val command = mutableListOf(executable.get(), cargoSubcommand.get())
        if (release.get()) {
            command += "--release"
        }
        command += listOf("-p", cargoPackage.get())
        if (triple != null) {
            command += listOf("--target", triple)
        }
        command += extraArguments.get()

        execOperations.exec {
            commandLine(command)
            workingDir(workspaceDirectory.get().asFile)
            environment(extraEnvironment.get())
        }.assertNormalExitValue()

        // zigbuild accepts `x86_64-unknown-linux-gnu.2.17` but writes to the plain triple's
        // directory; a Rust triple itself never contains a dot.
        val targetDirectory = triple?.substringBefore('.')
        val artifact = workspaceDirectory.get().asFile.toPath()
            .resolve("target")
            .let { root -> if (targetDirectory != null) root.resolve(targetDirectory) else root }
            .resolve(profile)
            .resolve(artifactFileName.get())
        copyArtifact(artifact)
    }

    private fun copyArtifact(source: Path) {
        val destination = outputFile.get().asFile.toPath()
        Files.createDirectories(destination.parent)
        Files.copy(
            source,
            destination,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.COPY_ATTRIBUTES,
        )
    }
}

/**
 * `-Pkurpc.prebuiltNatives=<dir>` points at a tree that mirrors `:kurpc`'s `build/`
 * (`natives-release/`, `jniLibs/main/`). Relative paths resolve from the root project.
 */
internal fun CargoBuildTask.usePrebuiltNative(project: Project, relativePath: String) {
    prebuiltSource.fileProvider(
        project.providers.gradleProperty("kurpc.prebuiltNatives").map { path ->
            project.rootProject.file("$path/$relativePath")
        },
    )
}
