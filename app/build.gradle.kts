import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.sunrestic"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "app.sunrestic"
        minSdk = 29
        targetSdk = 36
        versionCode = 9
        versionName = "0.9.0"
        ndk { abiFilters += "arm64-v8a" }
        manifestPlaceholders["appLabel"] = "Sunrestic"
    }

    // Two builds of the same app:
    //  full = everything, for the owner's own use.
    //  play = only what Google Play allows (see BUILDS.md). BuildConfig.PLAY tells the code which one it is.
    flavorDimensions += "dist"
    productFlavors {
        create("full") {
            dimension = "dist"
            buildConfigField("boolean", "PLAY", "false")
        }
        create("play") {
            dimension = "dist"
            buildConfigField("boolean", "PLAY", "true")
            versionNameSuffix = "-play"
        }
    }

    // Release signing: put your own key in keystore.properties (see keystore.properties.example, never commit it).
    // Without that file the release build is signed with the local debug key, which is fine for testing but not for publishing.
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        create("release") {
            if (keystoreProps.containsKey("storeFile")) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            } else {
                storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            // A debug build is a separate app, so it can be tried next to the one you use.
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appLabel"] = "Sunrestic (debug)"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    packaging {
        jniLibs {
            // Extract libs to nativeLibraryDir so librestic.so / libssh.so can be executed.
            useLegacyPackaging = true
            keepDebugSymbols += "**/*.so"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // real org.json, the Android one is only a stub in unit tests
}
