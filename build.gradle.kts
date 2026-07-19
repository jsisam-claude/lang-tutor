// Root build intentionally declares NO plugins {} block.
//
// Invariant: a `plugins { alias(libs.plugins.android.application) apply false }`
// block here would resolve the Android Gradle Plugin during root configuration,
// which breaks the `-Plangtutor.jvmOnly=true` lane in environments that cannot
// reach dl.google.com. Plugin versions come from gradle/libs.versions.toml and
// are resolved only by the modules that actually apply them.
//
// Full (non-jvmOnly) builds DO put AGP on the root classpath below, so :app and
// :asset-packs:model_pack share one plugin classloader — required for AGP's
// AnalyticsService, which otherwise fails asset-pack builds with
// "Service ...AnalyticsService_<uuid> is not registered".
buildscript {
    val jvmOnly = providers.gradleProperty("langtutor.jvmOnly").orNull == "true"
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
    dependencies {
        if (!jvmOnly) {
            // Keep in sync with `agp` in gradle/libs.versions.toml (literal
            // because `libs` accessors are not available inside buildscript {}).
            classpath("com.android.tools.build:gradle:8.13.0")
        }
    }
}
