plugins {
    id("com.android.application")
}

val releaseStoreFile = System.getenv("ANDROID_SIGNING_STORE_FILE")
val releaseStorePassword = System.getenv("ANDROID_SIGNING_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("ANDROID_SIGNING_KEY_ALIAS")
val releaseKeyPassword = System.getenv("ANDROID_SIGNING_KEY_PASSWORD")
val releaseSigningConfigured = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

val googleCastAppId = providers.gradleProperty("googleCastAppId").orNull
    ?: System.getenv("GOOGLE_CAST_APP_ID").orEmpty()
val escapedGoogleCastAppId = googleCastAppId.replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.weekd.miracastreceiver"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.weekd.miracastreceiver"
        minSdk = 23
        targetSdk = 36
        versionCode = 59
        versionName = "1.13.16"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GOOGLE_CAST_APP_ID", "\"$escapedGoogleCastAppId\"")
        ndk { abiFilters += setOf("armeabi-v7a", "arm64-v8a") }
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
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
            isMinifyEnabled = false
            if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        // wfdctl is an executable shipped as lib/arm*/libwfdctl.so and is exec'd by su, so the
        // native libraries must be extracted to disk and not mmap'd straight out of the APK.
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            val abiFilter = when {
                project.hasProperty("buildArm64") -> listOf("arm64-v8a")
                project.hasProperty("buildArm32") -> listOf("armeabi-v7a")
                else -> listOf("arm64-v8a", "armeabi-v7a")
            }
            include(*abiFilter.toTypedArray())
            isUniversalApk = !project.hasProperty("buildArm64") && !project.hasProperty("buildArm32")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("androidx.leanback:leanback:1.2.0")
    implementation("androidx.tvprovider:tvprovider:1.1.0")

    val lifecycleVersion = "2.11.0"
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-process:$lifecycleVersion")
    implementation("androidx.media:media:1.8.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    implementation("com.squareup.okhttp3:okhttp:5.3.0")
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")
    implementation("com.google.code.gson:gson:2.14.0")

    implementation("com.google.android.gms:play-services-cast-tv:21.0.1")
    implementation("com.google.android.gms:play-services-cast:22.3.1")

    val media3Version = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")
    implementation("androidx.media3:media3-datasource-okhttp:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")

    // Maintained libwebrtc build (org.webrtc package preserved). The archived
    // org.webrtc:google-webrtc:1.0.32006 ships 4KB-aligned native libs that cannot load on
    // 16KB-page devices (Android 15+); stream-webrtc-android >= 1.3 supports them, keeping the
    // receiver WebRTC-capable through Android 17.
    implementation("io.getstream:stream-webrtc-android:1.3.10")
    implementation("com.jakewharton.timber:timber:5.0.1")
    implementation("com.google.zxing:core:3.5.4")
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    implementation("com.googlecode.plist:dd-plist:1.29")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
