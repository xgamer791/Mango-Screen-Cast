plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI sets these: every published build gets a higher version code than any
// before it, and is signed with the same permanent key so the TV can update
// itself. Local builds fall back to the debug key.
val keystorePath: String? = System.getenv("MANGO_KEYSTORE_FILE")
val buildVersionCode = System.getenv("MANGO_VERSION_CODE")?.toIntOrNull() ?: 3
val buildVersionName = System.getenv("MANGO_VERSION_NAME") ?: "1.2.0-local"
// Where the app looks for updates: this repository's latest GitHub Release.
val updateRepository = System.getenv("GITHUB_REPOSITORY") ?: "xgamer791/Mango-Screen-Cast"

android {
    namespace = "com.mangoscreencast.tv"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.mangoscreencast.tv"
        // Android 7 covers older Fire TV sticks. Low-latency features that
        // need newer Android are switched on at runtime.
        minSdk = 24
        targetSdk = 35
        versionCode = buildVersionCode
        versionName = buildVersionName
        buildConfigField(
            "String", "UPDATE_BASE_URL",
            "\"https://github.com/$updateRepository/releases/latest/download/\"",
        )

        ndk {
            // TVs and streaming sticks are ARM; x86_64 is for the emulator.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("MANGO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MANGO_KEY_ALIAS")
                keyPassword = System.getenv("MANGO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (keystorePath != null) "release" else "debug")
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
        prefab = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // Prebuilt OpenSSL for the NDK, published by Google. UxPlay uses it for
    // AirPlay pairing and stream decryption.
    implementation("com.android.ndk.thirdparty:openssl:1.1.1q-beta-1")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM tests (android.jar only has stubs).
    testImplementation("org.json:json:20240303")
}
