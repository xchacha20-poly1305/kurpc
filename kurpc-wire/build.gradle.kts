import kurpc.gradle.CargoBuildTask
import kurpc.gradle.TestServerArgument
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

val wireCompiler = configurations.create("wireCompiler")

dependencies {
    api(libs.wire.schema)
    implementation(libs.wire.kotlin.generator)
    implementation(libs.kotlinpoet)

    testImplementation(project(":kurpc"))
    testImplementation(libs.wire.runtime)
    testImplementation(kotlin("test"))

    wireCompiler(libs.wire.compiler)
}

val generateTestMessages = tasks.register<JavaExec>("generateTestMessages") {
    val protos = layout.projectDirectory.dir("src/test/proto")
    val output = layout.buildDirectory.dir("generated/testMessages")
    inputs.dir(protos).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(output)
    classpath = wireCompiler + sourceSets.main.get().runtimeClasspath
    mainClass.set("com.squareup.wire.WireCompiler")
    args(
        "--proto_path=${protos.asFile}",
        "--kotlin_out=${output.get().asFile}",
        "--kotlin_rpc_role=none",
        "--custom_out=${output.get().asFile}",
        "--schema_handler_factory_class=io.github.xchacha20_poly1305.kurpc.wire.KurpcSchemaHandlerFactory",
    )
    doFirst { output.get().asFile.deleteRecursively() }
}
kotlin.sourceSets.test { kotlin.srcDir(generateTestMessages) }

// The tests call the test server the way :kurpc's jvmTest does, with :kurpc's host builds.
evaluationDependsOn(":kurpc")
val kurpcTasks = project(":kurpc").tasks
sourceSets.test {
    resources.srcDir(kurpcTasks.named<CargoBuildTask>("cargoBuildJni").flatMap { it.outputDirectory })
}
tasks.test {
    useJUnitPlatform()
    jvmArgumentProviders.add(
        objects.newInstance<TestServerArgument>().apply {
            binary.set(kurpcTasks.named<CargoBuildTask>("cargoBuildTestServer").flatMap { it.outputFile })
        },
    )
}

mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
    coordinates(group.toString(), "kurpc-wire", version.toString())
    pom {
        name.set("kurpc-wire")
        description.set("Wire schema handler generating kurpc clients.")
    }
}
