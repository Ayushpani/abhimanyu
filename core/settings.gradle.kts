// The core module is a plain Kotlin/JVM build with no Android dependencies, so its
// parser, risk tiers and reply templates can be unit-tested anywhere:
//   ./gradlew -p core test
// The Android app pulls it in as an included build (see ../settings.gradle.kts).
pluginManagement {
    repositories {
        maven("https://repo1.maven.org/maven2")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        maven("https://repo1.maven.org/maven2")
        mavenCentral()
    }
}
rootProject.name = "core"
