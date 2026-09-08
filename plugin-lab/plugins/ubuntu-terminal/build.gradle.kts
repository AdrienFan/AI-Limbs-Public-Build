import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.ai.limbs.plugins.ubuntu"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.ai.limbs.payload.ubuntu.v030"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "0.3.2"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = false }
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
    implementation(libs.coroutines.android)
}
