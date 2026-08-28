plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "dev.anonrode.player"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.anonrode.player"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 11
        versionName = "0.6.0"
    }

    // One stable signing identity for every build so updates always install
    // in place (no uninstall/reinstall cycle, user data survives). The
    // keystore itself lives in CI secrets — this repo is public, so it must
    // never be committed. The workflow decodes it to a temp file and points
    // ANONRODE_KEYSTORE_PATH at it. Without that env (plain checkout) the
    // build falls back to the transient debug key.
    val sharedKeyAvailable = System.getenv("ANONRODE_KEYSTORE_PATH") != null

    signingConfigs {
        create("shared") {
            val path = System.getenv("ANONRODE_KEYSTORE_PATH")
            if (path != null) {
                storeFile = java.io.File(path)
                storePassword = System.getenv("ANONRODE_STORE_PASSWORD").orEmpty()
                keyAlias = System.getenv("ANONRODE_KEY_ALIAS") ?: "anonrode"
                keyPassword = System.getenv("ANONRODE_KEY_PASSWORD").orEmpty()
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            if (sharedKeyAvailable) signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (sharedKeyAvailable) signingConfig = signingConfigs.getByName("shared")
        }
        create("releaseWithDebugSigning") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            // R8 breaks DataStore/serialization on this path — keep sideload
            // builds unshrunk until proper keep rules are proven.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig =
                if (sharedKeyAvailable) signingConfigs.getByName("shared")
                else signingConfigs.getByName("debug")
            applicationIdSuffix = ".release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures { compose = true }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:media"))
    implementation(project(":core:ui"))
    implementation(project(":feature:player"))
    implementation(project(":feature:library"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtimeCompose)
    implementation(libs.androidx.lifecycle.viewModel.ktx)
    implementation(libs.androidx.lifecycle.viewModelCompose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.iconsExtended)
    implementation(libs.androidx.mediarouter)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.kotlinx.coroutines.android)
}
