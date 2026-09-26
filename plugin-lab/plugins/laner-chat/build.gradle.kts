import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ai.limbs.plugins.lanerchat"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ai.limbs.payload.lanerchat.v010"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }

    androidResources {
        additionalParameters += listOf("--package-id", "0x82")
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    compileOnly(project(":plugin-inprocess-api"))
    implementation(libs.kotlinx.serialization)
    implementation(libs.coroutines.android)

    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.coroutines.test)
}
