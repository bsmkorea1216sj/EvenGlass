plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// "EvenGlass 자동번역": conversation auto-translation + reply suggestions (phone mic).
// Separate app from the camera translator (different applicationId, installs side by side).
android {
    namespace = "kr.bsm.evenglass.autotranslate"
    compileSdk = 34

    defaultConfig {
        applicationId = "kr.bsm.evenglass.autotranslate"
        // 26+: the Anthropic Java SDK uses java.time (all current Galaxy phones are 26+).
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "0.5.1"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST",
                "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.md",
                "META-INF/versions/*/module-info.class", "module-info.class",
                "META-INF/{AL2.0,LGPL2.1}"
            )
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // 면접 mode: Claude via the official Anthropic Java SDK (ChatGPT via plain REST)
    implementation("com.anthropic:anthropic-java:3.14.0")

    // On-device language identification and X->ko translation
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.mlkit:translate:17.0.3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
}
