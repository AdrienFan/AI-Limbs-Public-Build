import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.ai.limbs.extensions.chatgptprobe"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.ai.limbs.payload.chatgptprobe.v001"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = false }
}
kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
    compileOnly(project(":plugin-inprocess-api"))
    compileOnly(project(":bridge-contract"))
    implementation(libs.coroutines.android)
}
