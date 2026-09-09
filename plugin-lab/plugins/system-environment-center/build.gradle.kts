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
        versionCode = 5
        versionName = "0.2.3"
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
    // Shared ABI type identity is owned by the real AI Limbs host; parent and .ailx must not embed copies.
    compileOnly(project(":system-environment-contract"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.coroutines.android)
}
