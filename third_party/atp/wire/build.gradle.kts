// :atp-wire, vendored from the ATP drop beside this file (README.md); this build file is the Helper's own.
// Pure Kotlin/JVM, so :core can depend on it and its upstream tests run here.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.test {
    systemProperty("atp.fixtures", file("../fixtures").absolutePath)
    maxHeapSize = "1g"
}
