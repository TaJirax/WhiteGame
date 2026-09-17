import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.kapt")
}

// Signing material stays out of git: the .jks and its passwords live in keystore/
// (gitignored). Without them the project still builds and tests -- debug falls back
// to the throwaway debug key -- but release comes out unsigned. See keystore/README.md.
val releaseKeystore = file("../keystore/whitebooster.jks")
val signingProps = Properties().apply {
    val f = file("../keystore/signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    // `-Pabi=armeabi-v7a` (comma-separated for several) builds per-ABI APKs instead of one
    // universal APK — roughly half the size on a 32-bit phone. Empty means: build them all.
    val abiSplit = (project.findProperty("abi") as String?)
        ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        .orEmpty()

    namespace = "com.whitegame.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.whitegame.app"
        // Android 6.0 — the floor the bundled libwg-go.so actually needs (its newest libc
        // dependency, __register_atfork, lands in API 23). Higher than this and 2016-2017
        // phones just say "problem parsing the package".
        minSdk = 23
        targetSdk = 34
        versionCode = 45
        versionName = "7.2.0"
        vectorDrawables { useSupportLibrary = true }
        // AGP rejects abiFilters and split filters together, so only one of them applies.
        ndk {
            if (abiSplit.isEmpty()) abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    // Splits rather than plain abiFilters: AGP does not re-merge native libs when a filter
    // changes, so a filtered build can silently ship the previous run's ABIs.
    splits {
        abi {
            isEnable = abiSplit.isNotEmpty()
            reset()
            include(*abiSplit.toTypedArray())
            isUniversalApk = false
        }
    }

    signingConfigs {
        if (releaseKeystore.exists()) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = signingProps.getProperty("storePassword")
                    ?: System.getenv("WG_STORE_PASSWORD")
                keyAlias = signingProps.getProperty("keyAlias") ?: "whitebooster"
                keyPassword = signingProps.getProperty("keyPassword")
                    ?: System.getenv("WG_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
        // Debug is signed with the release key when it is available so debug and
        // release builds can replace each other on a test device.
        debug {
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.8" }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        // Per-ABI builds stay uncompressed + page-aligned (16 KB page-size devices). The
        // universal APK carries four copies of the Xray core, so it compresses them instead.
        jniLibs { useLegacyPackaging = abiSplit.isEmpty() }
    }
    // The Xray core ships 28 MB of geo databases; routing here uses plain CIDRs instead.
    androidResources {
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~" +
            ":!geoip.dat:!geosite.dat:!geoip-only-cn-private.dat"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("com.google.dagger:hilt-android:2.50")
    kapt("com.google.dagger:hilt-compiler:2.50")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // AndroidLibXrayLite: Xray-core with its own TUN inbound, run in the :xray process.
    implementation(files("libs/libv2ray.aar"))

    testImplementation("junit:junit:4.13.2")
    // The android.jar org.json is a stub in unit tests; config builders need the real one.
    testImplementation("org.json:json:20240303")

}

kapt {
    correctErrorTypes = true
}
