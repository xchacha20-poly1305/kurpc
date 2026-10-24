// Loaded once here so the modules and the build-logic convention plugin share one copy.
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.android.lint) apply false
    // Both modules publish; one copy keeps their shared Maven Central build service one type.
    alias(libs.plugins.maven.publish) apply false
    // Puts the build-logic task types on every module's classpath: protoc-gen-kurpc's tests use
    // the cargo outputs of :kurpc.
    id("kurpc.native") apply false
}
