import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.google.services)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

// Release version: CI passes the tag (-PvapianVersion=1.2.3 for v1.2.3). versionCode is derived from it so it always
// grows with the semantic version: 1.2.3 -> 10203.
val appVersionName = (findProperty("vapianVersion") as String?)?.removePrefix("v") ?: "0.0.0-dev"
val appVersionCode = appVersionName.substringBefore('-').split('.').mapNotNull { it.toIntOrNull() }
    .let { (it.getOrElse(0) { 0 } * 10_000 + it.getOrElse(1) { 0 } * 100 + it.getOrElse(2) { 0 }).coerceAtLeast(1) }

// Release signing: from keystore.properties (gitignored, local) or from environment variables (CI secrets).
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? = keystoreProps.getProperty(key) ?: System.getenv(env)

android {
    namespace = "com.bortho.autovelox"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.bortho.autovelox"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Never committed: lives in the gitignored local.properties (or the CI secret).
        // OpenStreetMap endpoints: production unless local.properties says otherwise (the OSM sandbox, to test edits).
        fun osm(key: String, default: String) = "\"" + localProperties.getProperty(key, default) + "\""
        buildConfigField("String", "OSM_BASE_URL", osm("OSM_BASE_URL", "https://www.openstreetmap.org"))
        buildConfigField("String", "OSM_API_URL", osm("OSM_API_URL", "https://api.openstreetmap.org"))
        buildConfigField("String", "OSM_CLIENT_ID", osm("OSM_CLIENT_ID", "WsglTNqzI33CX2s3qkUBR8hZsHsWepuP-stXnZXhufk"))
        manifestPlaceholders["MAPS_API_KEY"] = localProperties.getProperty("MAPS_API_KEY", "")
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    signingConfigs {
        create("release") {
            val storePath = signingValue("storeFile", "VAPIAN_KEYSTORE_FILE")
            if (storePath != null) {
                storeFile = rootProject.file(storePath)
                storePassword = signingValue("storePassword", "VAPIAN_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "VAPIAN_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "VAPIAN_KEY_PASSWORD")
                storeType = signingValue("storeType", "VAPIAN_KEYSTORE_TYPE") ?: "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            // Unsigned (and so not installable) when no keystore is configured: better than silently debug-signing.
            if (signingConfigs.getByName("release").storeFile != null) signingConfig = signingConfigs.getByName("release")
            // R8 full mode on everything (code shrinking + obfuscation + optimisation + resource shrinking)
            optimization {
                enable = true
            }
            isDebuggable = false
            ndk.debugSymbolLevel = "NONE"
        }
    }
    // the app is Italian-only: drop the translations bundled by every library
    androidResources {
        localeFilters += listOf("it")
    }

    // no signing-block dependency metadata, and none of the licence/metadata files libraries drag in
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    packaging {
        resources.excludes += setOf(
            "META-INF/*.version", "META-INF/*.kotlin_module", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES",
            "META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/INDEX.LIST", "DebugProbesKt.bin", "kotlin-tooling-metadata.json",
            "**/*.kotlin_builtins", "**/*.kotlin_metadata",
        )
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.cbor)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play)
    implementation(libs.googleid)
    implementation(libs.firebase.firestore)

    implementation(libs.play.services.maps)
    implementation(libs.play.services.location)
    implementation(libs.maps.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
}
