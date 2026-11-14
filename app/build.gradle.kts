plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.windowsandroid"
    compileSdk = 37
    // NDK r30 (2026 LTS). The engine is arm64 only: it translates ARM64 guest code to ARM64 host code.
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.windowsandroid"
        // Android 14+: every Galaxy S2x Ultra / Pixel Pro this targets is on 16 or later.
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=none",
                    "-DWINVM_TESTS=OFF",
                    "-DWINVM_TOOLS=OFF",
                )
            }
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    sourceSets {
        // guest/ (scripts that run inside Windows) goes into the APK's assets for the answer disc.
        getByName("main").assets.srcDir(rootProject.file("guest"))
    }

    // Release signing comes from Gradle properties outside the repository (~/.gradle/gradle.properties
    // or -P / ORG_GRADLE_PROJECT_ environment variables). Without them, release builds are signed
    // with the local debug key, so anyone can still build an installable APK.
    val releaseStore = providers.gradleProperty("WINDOWSANDROID_RELEASE_STORE_FILE").orNull
    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = providers.gradleProperty("WINDOWSANDROID_RELEASE_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("WINDOWSANDROID_RELEASE_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("WINDOWSANDROID_RELEASE_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // Keep libwinandroid.so uncompressed and page aligned (16 KB) in the APK.
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.browser)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
