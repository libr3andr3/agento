plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tech.yaya.agente"
    compileSdk = 36

    defaultConfig {
        applicationId = "tech.yaya.agente.replies"
        minSdk = 26
        targetSdk = 36
        versionCode = 18
        versionName = "0.10.0"

        // Client API key: supplied per-build, never committed.
        val appKey = System.getenv("AGENTO_APP_KEY")
            ?: (project.findProperty("AGENTO_APP_KEY") as String?)
            ?: "agente-app-dev"
        buildConfigField("String", "APP_KEY", "\"$appKey\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
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
