package kurpc.gradle

import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

/**
 * `kurpc-natives-jvm`: one jar per desktop classifier and no main jar, so a consumer picks its
 * platform with `kurpc-natives-jvm:<version>:<classifier>` (plan §6.4).
 *
 * Maven Central wants sources and javadoc next to every published jar. There is no source for a
 * native library, so the sources jar is empty; the publish plugin adds the (empty) javadoc jar to
 * every publication itself.
 */
internal fun registerNativesPublication(project: Project, jars: List<TaskProvider<Jar>>) {
    val emptySources = project.tasks.register<Jar>("nativesSourcesJar") {
        archiveBaseName.set("kurpc-natives-jvm")
        archiveClassifier.set("sources")
        destinationDirectory.set(project.layout.buildDirectory.dir("libs"))
    }
    project.extensions.getByType<PublishingExtension>().publications
        .create<MavenPublication>("nativesJvm") {
            artifactId = "kurpc-natives-jvm"
            pom.packaging = "pom"
            jars.forEach { artifact(it) }
            artifact(emptySources)
        }
}
