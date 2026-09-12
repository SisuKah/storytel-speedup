// The real module: discovery mode + Media3/ExoPlayer speed hooks + tiny config Activity.
// Build with:  ./gradlew :app:assembleRelease   (output: app/build/outputs/apk/release/app-release.apk)
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.sisukah.storytelspeedmod"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.sisukah.storytelspeedmod"
        minSdk = 28          // LSPatch supports Android 9+
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        buildConfig = true   // BuildConfig.VERSION_NAME is printed in the load log line
    }

    buildTypes {
        release {
            // Keep names intact: the hook entry class is referenced from assets/xposed_init and
            // hook code is easier to read in stack traces. The module is tiny anyway.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Provided by LSPatch/LSPosed at runtime. Must stay compileOnly.
    compileOnly("de.robv.android.xposed:api:82")

    // Pure-JVM unit tests for the speed policy and config parsing (no device needed).
    testImplementation("junit:junit:4.13.2")
}
