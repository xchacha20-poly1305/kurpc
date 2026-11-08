import kurpc.gradle.CargoBuildTask
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    // The KMP Android library plugin has no lint tasks of its own.
    alias(libs.plugins.android.lint)
    alias(libs.plugins.maven.publish)
    id("kurpc.native")
}

val installWebBridge = tasks.register<Exec>("installWebBridge") {
    description = "Install wasm web bridge dependencies"
    workingDir(rootProject.file("web-bridge"))
    val npm = if (System.getProperty("os.name").startsWith("Windows")) "npm.cmd" else "npm"
    commandLine(npm, "ci", "--ignore-scripts", "--no-audit", "--no-fund")
    inputs.files(rootProject.files("web-bridge/package.json", "web-bridge/package-lock.json"))
    outputs.dir(rootProject.file("web-bridge/node_modules"))
}

val buildWebBridge = tasks.register<Exec>("buildWebBridge") {
    description = "Build wasm web bridge"
    dependsOn(installWebBridge)
    workingDir(rootProject.file("web-bridge"))
    commandLine("node", "build.mjs", layout.buildDirectory.dir("generated/webBridge").get().asFile.absolutePath)
    inputs.dir(rootProject.file("web-bridge/src"))
    inputs.dir(rootProject.file("web-bridge/licenses"))
    inputs.files(rootProject.files("web-bridge/build.mjs", "web-bridge/package-lock.json"))
    outputs.dir(layout.buildDirectory.dir("generated/webBridge"))
}

val testWebBridge = tasks.register<Exec>("testWebBridge") {
    description = "Test the web bridge against fake fetch and WebSocket implementations"
    group = "verification"
    workingDir(rootProject.file("web-bridge"))
    commandLine("node", "--test")
    inputs.dir(rootProject.file("web-bridge/src"))
    inputs.dir(rootProject.file("web-bridge/test"))
    val marker = layout.buildDirectory.file("webBridgeTest/passed")
    outputs.file(marker)
    doLast {
        marker.get().asFile.writeText("")
    }
}

// jvmMain reads this. Generating it keeps the extraction directory's version in step with the
// project version without a hand-copied constant.
val generateKurpcVersion = tasks.register("generateKurpcVersion") {
    description = "Generate KurpcVersion.kt so jvmMain uses the project version."
    group = "build"
    val version = project.version.toString()
    val outputDir = layout.buildDirectory.dir("generated/kurpcVersion")
    inputs.property("version", version)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("io/github/xchacha20_poly1305/kurpc/KurpcVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package io.github.xchacha20_poly1305.kurpc

            internal const val KURPC_VERSION: String = "$version"

            """.trimIndent(),
        )
    }
}

kotlin {
    explicitApi()
    // Public API dump in `kurpc/api/`; `checkKotlinAbi` fails on an unrecorded change.
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
            // Rejects Java 9+ API at compile time (plan §5.6). Android compiles against
            // android.jar instead, and Lint's NewApi check covers it (plan §5.7).
            freeCompilerArgs.add("-Xjdk-release=1.8")
        }
    }

    js {
        // Published debug information must not expose the build machine's directories.
        compilerOptions.freeCompilerArgs.add("-Xklib-relative-path-base=${rootDir.absolutePath}")
        browser {
            testTask {
                enabled = false
            }
        }
        // webTest replaces fetch and WebSocket, so Node runs it without a browser to install.
        nodejs()
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        compilerOptions.freeCompilerArgs.add("-Xklib-relative-path-base=${rootDir.absolutePath}")
        browser {
            testTask {
                enabled = false
            }
        }
        nodejs()
    }

    android {
        namespace = "io.github.xchacha20_poly1305.kurpc"
        compileSdk = 37
        minSdk = 21
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        packaging {
            // Device tests execute libkurpc_testserver.so from nativeLibraryDir, so the test
            // APK must have its native libraries extracted. The AAR is unaffected.
            jniLibs.useLegacyPackaging = true
        }
        // KmpOptimization is incubating in AGP 9; it is the published API for AAR consumer keep rules.
        @Suppress("UnstableApiUsage")
        optimization {
            // The same rules ship in the JVM jar under META-INF/proguard, where R8 reads them.
            consumerKeepRules.publish = true
            consumerKeepRules.file("src/jvmMain/resources/META-INF/proguard/kurpc.pro")
        }
        lint {
            checkOnly += "NewApi"
            abortOnError = true
        }
    }

    sourceSets {
        val webMain = create("webMain") {
            dependsOn(commonMain.get())
            resources.srcDir(rootProject.file("web-bridge/licenses"))
            kotlin.srcDir(buildWebBridge)
        }
        jsMain {
            dependsOn(webMain)
        }
        wasmJsMain {
            dependsOn(webMain)
        }
        val webTest = create("webTest") {
            dependsOn(commonTest.get())
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        jsTest {
            dependsOn(webTest)
        }
        wasmJsTest {
            dependsOn(webTest)
        }
        commonMain.dependencies {
            // api, because Channel and the typed calls take and return Flow.
            api(libs.kotlinx.coroutines.core)
        }
        val jniMain = create("jniMain") {
            dependsOn(commonMain.get())
        }
        jvmMain {
            dependsOn(jniMain)
            kotlin.srcDir(generateKurpcVersion)
        }
        androidMain.get().dependsOn(jniMain)
        jvmTest {
            dependencies {
                implementation(kotlin("test"))
            }
            // The host library as a classpath resource, so jvmTest loads it the way a published
            // jar does.
            resources.srcDir(tasks.named<CargoBuildTask>("cargoBuildJni").flatMap { it.outputDirectory })
        }
        getByName("androidDeviceTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.junit)
        }
    }
}

tasks.named("check") {
    dependsOn("jsJar", "wasmJsJar", testWebBridge)
}

mavenPublishing {
    publishToMavenCentral()
    // CI passes the in-memory key (plan §6.4); local publishToMavenLocal runs unsigned.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
    coordinates(group.toString(), "kurpc", version.toString())
    // The rest of the POM is shared with protoc-gen-kurpc, in the root gradle.properties.
    pom {
        name.set("kurpc")
        description.set("Kotlin Multiplatform gRPC client for JVM, Android, JS and Wasm browsers.")
    }
}
