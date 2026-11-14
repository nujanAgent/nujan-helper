import java.util.Properties

// :app — Android module (Compose UI, foreground service, the Helper's tools for ATP's provider kit, proot runtime
// manager, PTY JNI, terminal). Implements every :core interface. Mirrors NuJan's :app conventions
// (single-activity Compose, ServiceLocator, no DI framework, no Navigation lib).

plugins {
    // AGP 9+ has built-in Kotlin support — no separate kotlin.android plugin (it errors if applied).
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

// The release key, named by keystore.properties at the repository root (storeFile, storePassword, keyAlias, keyPassword),
// which git ignores, as it does the key itself.
val releaseKeys: Properties? = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
}

android {
    namespace = "com.nujan.helper"
    // Compile against the latest SDK for APIs/lint, but SHIP targetSdk 28 (see below).
    compileSdk = 37

    defaultConfig {
        applicationId = "com.nujan.helper"
        minSdk = 26        // D2: match NuJan (minSdk 26) so the pair always co-installs.
        // D1 (the load-bearing decision): targetSdk 28. Android 10+ (targetSdk >= 29) enforces
        // W^X — it blocks exec() of binaries living in writable app storage, which is exactly what
        // an embedded proot userland needs to do. This app is SIDELOAD-ONLY and never goes to
        // Google Play, so Play's "target the latest SDK" mandate does not apply. This single line
        // is what makes an on-device Linux environment possible. Revisit only if Android hard-
        // deprecates installing targetSdk-28 APKs (tracked as risk R3 in the plan).
        targetSdk = 28
        versionCode = 1
        versionName = "0.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // arm64 only: every toolchain the Helper ships (Debian arm64) is arm64.
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { } } // build libnujanpty.so (the PTY bridge).
    }

    // Pin the NDK so CI/other machines resolve the same toolchain as this build.
    ndkVersion = "27.1.12297006"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        if (releaseKeys != null) {
            create("release") {
                storeFile = rootProject.file(releaseKeys.getProperty("storeFile"))
                storePassword = releaseKeys.getProperty("storePassword")
                keyAlias = releaseKeys.getProperty("keyAlias")
                keyPassword = releaseKeys.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Signed with the release key when keystore.properties names it (see RELEASING.md); unsigned otherwise.
            if (releaseKeys != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false // R8 stays off until keep rules cover JNI, forkpty, AIDL and serialization.
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        // AGP built-in Kotlin derives the JVM target from these (matches NuJan's app module).
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // We intentionally ship targetSdk 28 (see defaultConfig). These checks exist ONLY to
        // enforce Google Play's target-API requirement, which does not apply to a sideload-only
        // app — so they are disabled here by design, not to hide a real problem.
        disable += "ExpiredTargetSdkVersion"
        disable += "OldTargetApi"
    }
}

// Bundle the vendored proot prebuilts (native/proot/arm64-v8a — the single source of truth) into
// the APK as assets under `proot-runtime/`, so there is no committed duplication. They are unpacked
// to the app's files dir and chmod +x on first run (AssetUnpacker). targetSdk 28 makes
// executing them from app storage legal.
val prootRuntimeAssets = layout.buildDirectory.dir("generated/proot-assets")
val copyProotRuntime by tasks.registering(Copy::class) {
    from(rootProject.file("native/proot/arm64-v8a"))
    into(prootRuntimeAssets.map { it.dir("proot-runtime") })
}
// AGP 9 disallows a Provider here; pass the resolved static path and carry the task dep manually.
android.sourceSets.getByName("main").assets.srcDir(prootRuntimeAssets.get().asFile)
// The generated asset dir feeds asset-merge AND lint (analysis + model writer) tasks; every consumer
// must depend on the copy or AGP 9's strict task-validation fails the build. Match all of them.

// The licence files at the repository root, their single source of truth, go into the APK under `licences/`, where the
// Licences page reads them; the usb-serial library's licence joins the others.
val licenceAssets = layout.buildDirectory.dir("generated/licence-assets")
val copyLicences by tasks.registering(Copy::class) {
    from(rootProject.file("LICENSE"), rootProject.file("NOTICE"), rootProject.file("THIRD_PARTY_NOTICES.md"))
    from(rootProject.file("LICENSES")) { into("LICENSES") }
    from(rootProject.file("third_party/usb-serial-for-android/LICENSE.txt")) {
        into("LICENSES")
        rename { "MIT-usb-serial-for-android.txt" }
    }
    into(licenceAssets.map { it.dir("licences") })
}
android.sourceSets.getByName("main").assets.srcDir(licenceAssets.get().asFile)
tasks.matching {
    it.name.matches(Regex("merge.*Assets")) || it.name.contains("lint", ignoreCase = true)
}.configureEach { dependsOn(copyProotRuntime, copyLicences) }

// The Hands add-on's APK, built for the same variant and so signed with the same key, goes in under `hands/`.
listOf("debug", "release").forEach { variant ->
    val title = variant.replaceFirstChar { it.uppercase() }
    val assets = layout.buildDirectory.dir("generated/hands-assets/$variant")
    val copyHands = tasks.register<Copy>("copyHands$title") {
        dependsOn(":hands:assemble$title")
        from(rootProject.file("hands/build/outputs/apk/$variant")) {
            include("*.apk")
            rename { "hands.apk" }
        }
        into(assets.map { it.dir("hands") })
    }
    android.sourceSets.getByName(variant).assets.srcDir(assets.get().asFile)
    tasks.matching {
        it.name.contains(title) && (it.name.matches(Regex("merge.*Assets")) || it.name.contains("lint", ignoreCase = true))
    }.configureEach { dependsOn(copyHands) }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":atp-provider"))
    implementation(project(":usb-serial"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.tukaani.xz)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
