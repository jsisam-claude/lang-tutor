pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "lang-tutor"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// Logic modules: pure JVM, buildable without the Android SDK.
include(":core:tutor", ":core:llm", ":core:speech", ":core:content", ":core:profile", ":core:packs")

// -Plangtutor.jvmOnly=true builds/tests only the JVM modules — used in sandboxed
// environments (and a cheap CI lane) where Android SDK / dl.google.com are
// unavailable. The Android app + asset pack need the full toolchain.
val jvmOnly = providers.gradleProperty("langtutor.jvmOnly").orNull == "true"
if (!jvmOnly) {
    include(":app")
    include(":asset-packs:model_pack")
}
