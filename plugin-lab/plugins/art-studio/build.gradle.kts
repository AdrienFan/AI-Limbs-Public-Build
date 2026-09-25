import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ai.limbs.plugins.artstudio"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.ai.limbs.payload.artstudio.v0211"
        minSdk = 29
        targetSdk = 34
        versionCode = 14
        versionName = "0.2.11"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = false; compose = true }
    androidResources { additionalParameters += listOf("--package-id", "0x80") }
}
kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
    compileOnly(project(":plugin-inprocess-api"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.coroutines.android)
}
