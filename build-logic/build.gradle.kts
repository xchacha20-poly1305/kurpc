plugins {
    `kotlin-dsl`
}

repositories {
    google()
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    // The root project loads both plugins (`apply false`); the convention plugin only needs their
    // API at compile time, and shares the root's classes at run time.
    compileOnly(libs.agp.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("kurpcNative") {
            id = "kurpc.native"
            implementationClass = "kurpc.gradle.KurpcNativePlugin"
        }
    }
}
