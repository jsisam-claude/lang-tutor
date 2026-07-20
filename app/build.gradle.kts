import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // Version-less: AGP is on the root buildscript classpath (see root
    // build.gradle.kts); a versioned request here would fail with
    // InvalidPluginRequestException ("already on the classpath").
    id("com.android.application")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.sisam.langtutor"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.sisam.langtutor"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // Model/content blobs must be stored uncompressed so engines can mmap
        // them directly; Play compresses transport anyway.
        noCompress += setOf("litertlm", "task", "onnx", "gguf", "tflite", "bin")
    }

    bundle {
        language {
            // Offline app: both he+en resources must always be on-device;
            // Play must not split language resources out for later download.
            enableSplit = false
        }
    }

    assetPacks += ":asset-packs:model_pack"
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(projects.core.tutor)
    implementation(projects.core.packs)
    implementation(projects.core.llm)
    implementation(projects.core.speech)
    implementation(projects.core.content)
    implementation(projects.core.profile)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.kotlinx.coroutines.android)
}
