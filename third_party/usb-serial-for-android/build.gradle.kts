// :usb-serial, vendored from the usb-serial-for-android release beside this file (README.md); this build file is the Helper's own.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.hoho.android.usbserial"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("proguard-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.annotation)
}
