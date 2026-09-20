plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
}

android {
    namespace = "dev.anonrode.player.core.media"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
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

    // v0.8.7: the sync engine's unit tests drive the real AudioSyncProcessor on
    // a plain JVM (no Robolectric). AppLog only reaches android.util.Log on a
    // failed file write, but a stub that throws would fail the whole suite, so
    // any android.* reference must degrade to a default instead.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.onnxruntime.android)
    implementation(libs.androidx.work.runtime.ktx)

    // v0.6.2 sub-sync UX pass: SubtitleMatcher unit tests. JUnit4 is
    // pulled inline (not declared in libs.versions.toml) so this module
    // does not depend on Agent 3's libs catalog update.
    testImplementation("junit:junit:${libs.versions.junit4.get()}")
}
