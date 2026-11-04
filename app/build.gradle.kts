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
        versionCode = 1
        versionName = "0.1.0-phase1"
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
    // Offline speech-to-text. Models are imported by the user; the app never downloads anything.
    implementation("com.alphacephei:vosk-android:0.3.75")
}
