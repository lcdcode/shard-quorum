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

rootProject.name = "ShardQuorum"

// The crypto core is a pure-JVM module so it builds and unit-tests without the
// Android toolchain (no aapt2, no emulator). :app is the Android UI and
// depends on :sskr.
include(":sskr")
include(":qrcodegen")
include(":app")
