pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "NujanHelper"
include(":app")
include(":core")
// The Accessibility add-on, an app of its own that the Helper carries and installs on request.
include(":hands")

// The App Tools Protocol's wire module, vendored as identical bytes from the ATP drop (third_party/atp).
include(":atp-wire")
project(":atp-wire").projectDir = file("third_party/atp/wire")

// ATP's provider kit from the same drop: the Helper's provider service, consent screen, grants and channels.
include(":atp-provider")
project(":atp-provider").projectDir = file("third_party/atp/provider")

// USB-serial drivers (CDC-ACM, CP210x, CH34x, FTDI, PL2303), vendored as identical bytes from usb-serial-for-android.
include(":usb-serial")
project(":usb-serial").projectDir = file("third_party/usb-serial-for-android")
