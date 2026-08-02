import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.jtransforms)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    // Golden fixtures for the VAD gate and the GOP scorer are JSON dumps of
    // real model output; parsed only in tests.
    testImplementation(libs.kotlinx.serialization.json)
}
