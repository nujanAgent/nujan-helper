import java.util.Properties

// :hands — the Accessibility add-on, a small app of its own so the Helper itself never asks for that permission. It has no
// internet permission and no screen; the Helper carries its APK as an asset and installs it when the person asks.

plugins {
    alias(libs.plugins.android.application)
}

val releaseKeys: Properties? = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
}

android {
    namespace = "com.nujan.helper.hands"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.nujan.helper.hands"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1"
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
            // Signed with the Helper's key, without which the Helper and the add-on will not talk to each other.
            if (releaseKeys != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
