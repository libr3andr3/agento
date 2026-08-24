plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tech.yaya.agente"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        // Each edition sets its own applicationId below.
        minSdk = 26
        targetSdk = 36
        versionCode = 36
        versionName = "1.5.0"

        // Client API key: supplied per-build, never committed.
        val appKey = System.getenv("AGENTO_APP_KEY")
            ?: (project.findProperty("AGENTO_APP_KEY") as String?)
            ?: "agente-app-dev"
        buildConfigField("String", "APP_KEY", "\"$appKey\"")
        // Where accounts and plans are managed (the gateway's web app).
        val webApp = System.getenv("AGENTO_WEB_APP") ?: "https://llm.yaya.tech/app"
        buildConfigField("String", "WEB_APP_URL", "\"$webApp\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // The agent core's schemas (core.yml + vertical bundles) ship inside the
    // APK, copied from the core crate at build time so there is one source.
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/coreAssets"))

    // Two products from one codebase and one on-device core:
    //   client   — "agento", the consumer app on Google Play: a personal
    //              assistant (general chat now; finds + books local
    //              businesses through the yaya network in phase 2).
    //   business — "agento business", the receptionist for business owners,
    //              distributed from agento.ceo / F-Droid only (its
    //              notification-listener + sideload-update permissions are
    //              what Play policy objects to).
    // Distribution channel. `direct` = APK with the self-hosted update channel
    // (needs REQUEST_INSTALL_PACKAGES). `play` = Google Play build: no install
    // permission, Play handles updates. Within an edition the applicationId
    // is the same — a phone carries one channel or the other, never both.
    flavorDimensions += listOf("edition", "channel")
    productFlavors {
        create("client") {
            dimension = "edition"
            applicationId = "yaya.tech.agento"
            resValue("string", "app_name", "agento")
            buildConfigField("String", "EDITION", "\"client\"")
        }
        create("business") {
            dimension = "edition"
            applicationId = "yaya.tech.agento.business"
            resValue("string", "app_name", "agento business")
            buildConfigField("String", "EDITION", "\"business\"")
        }
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

// ---------------------------------------------------------------- core
//
// libagento_core.so is cross-compiled from ../server with cargo-ndk into
// src/main/jniLibs (gitignored). `-PskipCore` reuses whatever is there.

val coreDir = rootProject.file("../server")
val jniLibs = file("src/main/jniLibs")

val copyCoreSchemas by tasks.registering(Copy::class) {
    from(File(coreDir, "schemas"))
    into(layout.buildDirectory.dir("generated/coreAssets/schemas"))
}

val buildCore by tasks.registering(Exec::class) {
    onlyIf { !project.hasProperty("skipCore") }
    workingDir = coreDir
    val ndk = System.getenv("ANDROID_NDK_HOME")
        ?: android.ndkDirectory.absolutePath
    environment("ANDROID_NDK_HOME", ndk)
    environment("PATH", System.getProperty("user.home") + "/.cargo/bin:" + System.getenv("PATH"))
    commandLine(
        "cargo", "ndk", "-t", "arm64-v8a", "-t", "armeabi-v7a",
        "-o", jniLibs.absolutePath, "build", "--release"
    )
}

tasks.named("preBuild") {
    dependsOn(copyCoreSchemas, buildCore)
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
