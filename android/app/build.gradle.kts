plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The build number from GitHub Actions, so every new APK installs over the previous one.
val buildNumber = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.easyvideodownloader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.easyvideodownloader"
        minSdk = 24
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        // Almost every phone uses one of these two; leaving out x86 keeps the APK much smaller.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        create("release") {
            // A signing key can be supplied through GitHub secrets. Without one, the key kept in
            // this repository is used so that every build has the same signature (needed for updates).
            storeFile = file(System.getenv("ANDROID_KEYSTORE_FILE") ?: "../keystore/easy-video-downloader.jks")
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD") ?: "evd-public-key"
            keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: "easyvideo"
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD") ?: "evd-public-key"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
        viewBinding = true
    }

    packaging {
        // The download engine runs Python and FFmpeg from the app's native library folder,
        // so those files must be unpacked on install.
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/*.kotlin_module",
            )
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
