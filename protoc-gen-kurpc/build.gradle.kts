import com.google.protobuf.gradle.id
import kurpc.gradle.CargoBuildTask
import kurpc.gradle.TestServerArgument
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
    // Compiles the test protos with this module's own plugin, so the tests run generated code.
    alias(libs.plugins.protobuf)
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        freeCompilerArgs.add("-Xjdk-release=1.8")
    }
}

dependencies {
    implementation(libs.protobuf.java)
    implementation(libs.kotlinpoet)

    testImplementation(project(":kurpc"))
    testImplementation(kotlin("test"))
}

/**
 * protoc runs plugins as executables. The protobuf Gradle plugin wraps a jar given as one in a
 * `java -jar` script, so the published plugin is this self-contained jar.
 */
val pluginJar = tasks.register<Jar>("pluginJar") {
    description = "Assembles protoc-gen-kurpc with its dependencies into one runnable jar."
    group = "build"
    archiveClassifier.set("all")
    manifest {
        attributes("Main-Class" to "io.github.xchacha20_poly1305.kurpc.protoc.MainKt")
    }
    from(sourceSets.main.map { it.output })
    from(configurations.runtimeClasspath.map { classpath -> classpath.map { zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("module-info.class", "META-INF/versions/*/module-info.class")
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    plugins {
        id("kurpc") {
            path = pluginJar.get().archiveFile.get().asFile.absolutePath
        }
    }
    generateProtoTasks {
        ofSourceSet("test").configureEach {
            inputs.file(pluginJar.flatMap { it.archiveFile })
                .withPropertyName("kurpcPlugin")
                .withPathSensitivity(PathSensitivity.NONE)
            plugins {
                id("kurpc")
            }
        }
    }
}

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
    coordinates(group.toString(), "protoc-gen-kurpc", version.toString())
    pom {
        name.set("protoc-gen-kurpc")
        description.set("protoc plugin generating kurpc clients for protobuf-java messages.")
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifact(pluginJar)
    }
}
