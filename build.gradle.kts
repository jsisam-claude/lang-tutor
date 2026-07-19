// Root build intentionally declares NO plugins.
//
// Invariant: a `plugins { alias(libs.plugins.android.application) apply false }`
// block here would resolve the Android Gradle Plugin during root configuration,
// which breaks the `-Plangtutor.jvmOnly=true` lane in environments that cannot
// reach dl.google.com. Plugin versions come from gradle/libs.versions.toml and
// are resolved only by the modules that actually apply them.
