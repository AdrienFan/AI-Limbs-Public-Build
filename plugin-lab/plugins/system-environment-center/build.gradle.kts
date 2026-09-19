import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ai.limbs.plugins.systemenvironment"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.ai.limbs.payload.systemenvironment.center"
        minSdk = 26
        targetSdk = 34
        versionCode = 13
        versionName = "0.2.11"
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
        // Dynamic in-process APKs must not share AI Limbs' default 0x7f resource package.
        // AI Limbs mounts this APK as an additional resource provider beside AI Limbs resources.
        additionalParameters += listOf("--package-id", "0x80")
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
    compileOnly(project(":plugin-inprocess-api"))
    // Host-shared UI/runtime libraries are resolved parent-first from AI Limbs; keep them out of payload DEX.
    // Shared ABI type identity is owned by the real AI Limbs host; parent and .ailx must not embed copies.
    compileOnly(project(":system-environment-contract"))
    implementation(platform(libs.compose.bom))
    compileOnly(libs.compose.ui)
    compileOnly(libs.compose.material3)
    compileOnly(libs.compose.material.icons.extended)
    compileOnly(libs.activity.compose)
    compileOnly(libs.coroutines.android)
}
