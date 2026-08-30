// SPDX-License-Identifier: AGPL-3.0-or-later
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    // The Kotlin package is `tech.yaya.agente` (the app's original name).
    // It cannot change: the agent core's JNI entry points are resolved by
    // this name (Java_tech_yaya_agente_AgentoCore_*), see AgentoCore.kt.
    namespace = "tech.yaya.agente"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        targetSdk = 36
        versionCode = 53
        versionName = "1.15.0"

        resValue("string", "app_name", "agento")
        // Where accounts and plans are managed (the gateway's web app).
        val webApp = System.getenv("AGENTO_WEB_APP") ?: "https://agento.ceo/app"
        buildConfigField("String", "WEB_APP_URL", "\"$webApp\"")
        // D14: plans are sold in a WhatsApp chat with agento's own sales agent.
        val sales = System.getenv("AGENTO_SALES_PHONE") ?: "51913879819"
        buildConfigField("String", "SALES_WHATSAPP", "\"$sales\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // One app, two ways to get it. `direct` is the APK from agento.ceo/dl
    // with the self-hosted update channel; `play` is the Google Play build:
    // no install permission (Play updates it), no QUERY_ALL_PACKAGES (the
    // wallet-installed hint is skipped), its own package id — the one
    // registered in Play Console. Everything else is identical.
    flavorDimensions += "channel"
    productFlavors {
        create("direct") {
            dimension = "channel"
            applicationId = "yaya.tech.agento.business"
            buildConfigField("boolean", "PLAY", "false")
        }
        create("play") {
            dimension = "channel"
            applicationId = "yaya.tech.agento"
            buildConfigField("boolean", "PLAY", "true")
        }
    }

    // Release signing. The upload key lives OUTSIDE the repo; export
    // AGENTO_KEYSTORE / AGENTO_KEYSTORE_PASS / AGENTO_KEY_ALIAS / AGENTO_KEY_PASS
    // (docs/RELEASE.md). Without them a release build is unsigned but still
    // compiles, so CI and contributors are never blocked.
    signingConfigs {
        create("upload") {
            val ks = System.getenv("AGENTO_KEYSTORE")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("AGENTO_KEYSTORE_PASS")
                keyAlias = System.getenv("AGENTO_KEY_ALIAS") ?: "agento-upload"
                keyPassword = System.getenv("AGENTO_KEY_PASS")
            }
        }
    }

    buildTypes {
        release {
            if (System.getenv("AGENTO_KEYSTORE") != null) {
                signingConfig = signingConfigs.getByName("upload")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// The agent core (libagento_core.so, one per ABI) is a prebuilt native
// library checked in under src/main/jniLibs — see docs/ARCHITECTURE.md and
// src/main/jniLibs/CORE.md. Its schemas ship as assets (src/main/assets/schemas).
// Nothing here needs Rust, the NDK or a network connection to build.

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // EXIF orientation fix for catalog photos before upload.
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
}
