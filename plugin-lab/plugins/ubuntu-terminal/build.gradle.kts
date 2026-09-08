import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ai.limbs.plugins.ubuntu"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.ai.limbs.payload.ubuntu.v030"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "0.3.5"
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
        jniLibs {
            excludes += setOf(
                "**/libbash.so",
                "**/libbusybox.so",
                "**/liboperit_loader.so",
                "**/liboperit_proot.so",
                "**/libsudo.so"
            )
        }
    }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
    compileOnly(project(":plugin-inprocess-api"))
    implementation(project(":ubuntu-terminal-core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.coroutines.android)
}
