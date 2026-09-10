import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ai.limbs.extensions.systemenvironment.ubuntu"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.ai.limbs.payload.systemenvironment.ubuntu"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "0.1.4"
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
        additionalParameters += listOf("--package-id", "0x81")
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
    compileOnly(project(":system-environment-contract"))
    implementation(project(":ubuntu-system-core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.coroutines.android)
}
