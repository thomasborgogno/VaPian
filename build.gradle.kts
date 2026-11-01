// Top-level build file where you can add configuration options common to all sub-projects/modules.

// AGP 9 bundles Kotlin 2.2.x, but current AndroidX/coroutines artifacts are compiled with 2.4:
// force the newer Kotlin Gradle plugin (must match the Compose/serialization plugin versions).
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.google.services) apply false
}
