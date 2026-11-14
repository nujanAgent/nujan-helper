// :atp-provider, ATP's provider kit, vendored from the ATP drop beside this file (../README.md); this build file is the
// Helper's own. The kit is the Helper's ATP provider: the Binder service, the consent screen, grants and channels.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.nujan_agent.atp.provider"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// ATP's provider and host interfaces compile from the drop's aidl folder, so there is one copy of them to check.
androidComponents {
    onVariants { variant -> variant.sources.aidl?.addStaticSourceDirectory(file("../aidl").absolutePath) }
}

tasks.withType<Test>().configureEach {
    systemProperty("atp.fixtures", file("../fixtures").absolutePath)
}

dependencies {
    api(project(":atp-wire"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
