plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.namchok.livesub"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.namchok.livesub"
        minSdk = 29          // Android 10+ (AudioPlaybackCapture)
        targetSdk = 34
        versionCode = 4
        versionName = "1.3"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("personal") {
            storeFile = file("livesub.keystore")
            storePassword = "livesub123"
            keyAlias = "livesub"
            keyPassword = "livesub123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Offline speech recognition (English)
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    // Offline translation EN -> TH (free, on-device)
    implementation("com.google.mlkit:translate:17.0.3")
}
