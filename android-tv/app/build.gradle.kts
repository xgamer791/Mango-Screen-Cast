plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

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
        versionCode = 2
        versionName = "1.1.0"

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

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sideloaded builds: sign with the debug key so the APK installs as-is.
            // Use your own key before publishing to a store.
            signingConfig = signingConfigs.getByName("debug")
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
}
