plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gdahome.os"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gdahome.os"
        // GdAHome OS: NSPanel Pro (86) e NSPanel Pro 120, Android 8.1 (API 27)
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "2.0.0"
    }

    signingConfigs {
        // Chiave fissa per il sideload: permette di aggiornare l'app senza disinstallarla.
        // Per una distribuzione pubblica crea una tua chiave privata e NON committarla.
        create("gdahome") {
            storeFile = file("../keystore/gdahome-sideload.jks")
            storePassword = "gdahome"
            keyAlias = "gdahome"
            keyPassword = "gdahome"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("gdahome")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("gdahome")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
}
