plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.vvvdroid.app"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.vvvdroid.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "0.5"

        ndk {
            // matches the ABIs we link libfbclient.so for; the AAR ships all four
            abiFilters += listOf("arm64-v8a", "x86_64")
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

    packaging {
        jniLibs {
            // Firebird's engine dlopen()s its plugin .so files from disk,
            // so native libs must be extracted on install
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    // Official Firebird 5 embedded Android build (fetched by scripts/fetch-firebird-android.ps1)
    implementation(files("libs/Firebird-5.0.4.1812-0-android-embedded.aar"))

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
}
