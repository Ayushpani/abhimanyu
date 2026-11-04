import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.abhimanyu"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.abhimanyu"
        // Pixel 10a ships with Android 16. 33 is the floor for the AGSL glow shader.
        minSdk = 33
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0-gemma"
        ndk {
            // The Pixel 10a is arm64; dropping other ABIs keeps the APK small.
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("dev.abhimanyu:core:0.1.0")
    // Main brain: Gemma 4 E2B on-device through LiteRT-LM (needs Kotlin 2.3+).
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")
    // Optional backup speech-to-text, used only if Gemma isn't loaded.
    implementation("com.alphacephei:vosk-android:0.3.75")
}
