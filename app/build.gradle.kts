plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tech.yaya.agente"
    compileSdk = 36

    defaultConfig {
        applicationId = "yaya.tech.agento"
        minSdk = 26
        targetSdk = 36
        versionCode = 30
        versionName = "1.0.1"

        // Client API key: supplied per-build, never committed.
        val appKey = System.getenv("AGENTO_APP_KEY")
            ?: (project.findProperty("AGENTO_APP_KEY") as String?)
            ?: "agente-app-dev"
        buildConfigField("String", "APP_KEY", "\"$appKey\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // Distribution channel. `direct` = APK from agento.ceo with the
    // self-hosted update channel (needs REQUEST_INSTALL_PACKAGES, which Play
    // policy forbids for an app like this). `play` = Google Play build: no
    // install permission, Play handles updates. Same applicationId — a phone
    // carries one or the other, never both.
    flavorDimensions += "channel"
    productFlavors {
        create("direct") {
            dimension = "channel"
            buildConfigField("boolean", "SELF_UPDATE", "true")
        }
        create("play") {
            dimension = "channel"
            buildConfigField("boolean", "SELF_UPDATE", "false")
        }
    }

    // Upload key for Play (and any release build). Lives OUTSIDE the repo:
    // ~/.agento/keystore.env exports AGENTO_KEYSTORE / _PASS / _ALIAS / KEY_PASS.
    // Without them a release build is unsigned (Play rejects it) but still
    // compiles, so CI and contributors are not blocked.
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
            // Shrinking does not make APP_KEY secret — nothing in a client
            // binary can be — but it removes the trivial `strings`-and-read
            // path, and a release build should be minified regardless.
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

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // EXIF orientation fix for catalog photos before upload.
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
}
