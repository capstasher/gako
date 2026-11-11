plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

import java.util.Properties

// CI passes its run number so every build installs over the previous one.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

// Signing: a real release key from the environment if one is provided, otherwise the
// repo's dev key, so every build (CI or local) can install over the last one.
val releaseKeystore: String? = System.getenv("GAKO_KEYSTORE")
val devSigning = Properties().apply {
    rootProject.file("signing/gako-dev.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "io.github.capstasher.gako"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.capstasher.gako"
        minSdk = 31          // Android 12
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.3.0-stage3+$buildNumber"
        // Phones are arm64. Add x86_64 for the emulator with -Pgako.abis=arm64-v8a,x86_64
        val abis = (project.findProperty("gako.abis") as String?) ?: "arm64-v8a"
        ndk { abiFilters += abis.split(',').map { it.trim() }.filter { it.isNotEmpty() } }
    }

    signingConfigs {
        create("gako") {
            if (releaseKeystore != null) {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("GAKO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("GAKO_KEY_ALIAS") ?: "gako"
                keyPassword = System.getenv("GAKO_KEY_PASSWORD") ?: System.getenv("GAKO_KEYSTORE_PASSWORD")
            } else if (devSigning.containsKey("storePassword")) {
                storeFile = rootProject.file("signing/gako-dev.jks")
                storePassword = devSigning.getProperty("storePassword")
                keyAlias = devSigning.getProperty("keyAlias", "gako")
                keyPassword = devSigning.getProperty("storePassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseKeystore != null || devSigning.containsKey("storePassword"))
                signingConfigs.getByName("gako") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    // GEGL and babl scan directories for their plug-in modules, so the libraries
    // must be extracted to disk rather than mapped straight out of the APK.
    packaging { jniLibs { useLegacyPackaging = true } }

    testOptions { unitTests.isReturnDefaultValues = true }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.heifwriter:heifwriter:1.0.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
