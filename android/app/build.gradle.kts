import java.util.Properties

plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
    // Firebase (required by firebase_core / cloud_firestore)
    id("com.google.gms.google-services")
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}
val mapsApiKey: String = localProperties.getProperty("MAPS_API_KEY") ?: ""

// Release signing — never committed (android/.gitignore ignores key.properties
// and *.p12/*.jks). Populated locally by pointing storeFile at your own
// keystore, or in CI by writing this file + decoding the keystore from a
// secret before the build (see .github/workflows/release.yml). Falls back to
// debug signing when absent, so `flutter build apk --release` still works
// without it (just not installable as an update over a real release build).
val keystoreProperties = Properties().apply {
    val keystorePropertiesFile = rootProject.file("key.properties")
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = keystoreProperties.containsKey("storeFile")

android {
    namespace = "com.bortho.autovelox"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.bortho.autovelox"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        // androidx.car.app requires minSdk 23, hence the override here.
        minSdk = maxOf(flutter.minSdkVersion, 23)
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                storeType = keystoreProperties.getProperty("storeType") ?: "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                // No key.properties on this machine — fall back to the debug
                // key so `flutter build/run --release` still works locally.
                signingConfigs.getByName("debug")
            }
        }
    }
}

flutter {
    source = "../.."
}

dependencies {
    // Android Auto / Automotive OS screens, built with the native Car App Library.
    // All real-time driving logic (GPS, Firestore, TTS) lives here in Kotlin;
    // Flutter and this side only share Firestore collections, never a Method Channel.
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")

    // Firestore Android SDK — independent from cloud_firestore (Dart), same
    // collections/offline cache, used by CameraRepository et al.
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-firestore")
    // Same signed-in user (Google/anonymous) as the phone app — Firebase Auth
    // persists its session on-device, shared by both SDKs in this app install.
    implementation("com.google.firebase:firebase-auth")

    // GPS/heading for the proximity loop (Fase 6) and the Fase 5 nearby-cameras screen.
    implementation("com.google.android.gms:play-services-location:21.3.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
}
