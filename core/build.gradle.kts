// Photos' library and rules: the Photos and albums API, the timeline, the import pipeline (upload,
// register, metadata, preview rendition), the Motion Photo reader, downloads through the shared
// decrypt, and the caches. Plain Kotlin/JVM, so all of it is tested on the JVM against the shared
// library's in-memory server.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api("com.neutrino.shared:neutrino-core")
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)

    testImplementation(testFixtures("com.neutrino.shared:neutrino-core"))
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    systemProperty("user.timezone", "Europe/Berlin")
    systemProperty("user.language", "en")
    systemProperty("user.country", "US")
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
