plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Pure-JVM module: protocol, session lifecycle, equalizer math and presets.
// No Android dependencies, so everything here runs as fast JVM unit tests and
// is checked against the shared fixtures in ../../shared.
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    // Lets the conformance tests find the shared fixtures regardless of the
    // directory Gradle was launched from.
    systemProperty("xmaudio.sharedDir", rootProject.projectDir.resolve("../shared").canonicalPath)
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
