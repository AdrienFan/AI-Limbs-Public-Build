import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ai.limbs.plugins.logcenter"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.ai.limbs.payload.logcenter.v010"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.1.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = false
        compose = true
    }
    androidResources {
        additionalParameters += listOf("--package-id", "0x80")
    }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
    compileOnly(project(":plugin-inprocess-api"))
    // Host-shared UI/runtime libraries are resolved parent-first from AI Limbs; keep them out of payload DEX.
    implementation(platform(libs.compose.bom))
    compileOnly(libs.compose.ui)
    compileOnly(libs.compose.material3)
    compileOnly(libs.compose.material.icons.extended)
    compileOnly(libs.activity.compose)
    compileOnly(libs.coroutines.android)
}
