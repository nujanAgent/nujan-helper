// :core — Pure Kotlin/JVM. NO android.* imports, EVER. Holds the MCP protocol, JobEngine model,
// workspace model, tool interfaces, and validation. JUnit-tested on the JVM in seconds.
// This split is the project's most important structural rule (mirrors NuJan's :core).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // ATP's framing, codecs and names, shared byte for byte with NuJan.
    api(project(":atp-wire"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
