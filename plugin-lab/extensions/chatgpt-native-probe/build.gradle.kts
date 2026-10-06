import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.ai.limbs.extensions.chatgptprobe"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ai.limbs.payload.chatgptprobe.v014"
        minSdk = 26
        targetSdk = 34
        versionCode = 14
        versionName = "0.0.14"
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
    implementation(libs.okhttp)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    testImplementation(project(":plugin-inprocess-api"))
    testImplementation(project(":bridge-contract"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
