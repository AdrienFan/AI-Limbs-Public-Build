import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "com.ai.limbs.extensions.drawguess"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.ai.limbs.payload.drawguess.v016"
        minSdk = 29
        targetSdk = 34
        versionCode = 7
        versionName = "0.1.6"
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
    implementation(libs.coroutines.android)
    testImplementation(project(":plugin-inprocess-api"))
    testImplementation(libs.junit)
    testImplementation(libs.json.jvm)
}
