// PHASE 1: the smallest possible Xposed module. It logs one line when LSPatch loads it
// inside Storytel and changes nothing else. Build with:  ./gradlew :probe:assembleRelease
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.sisukah.storytelspeedmod.probe"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.sisukah.storytelspeedmod.probe"
        minSdk = 28          // LSPatch supports Android 9+
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-probe"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Personal-use module: sign release with the debug key so the APK is installable
            // and embeddable without a keystore setup.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // compileOnly: the framework (LSPatch / LSPosed) provides XposedBridge at runtime.
    // Bundling it would break loading.
    compileOnly("de.robv.android.xposed:api:82")
}
