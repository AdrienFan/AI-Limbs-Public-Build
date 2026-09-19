import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "com.ai.limbs.plugins.permission"
    compileSdk = 36
    ndkVersion = "27.0.12077973"
    defaultConfig {
        applicationId = "com.ai.limbs.payload.permission.v013"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "0.1.3"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=none" } }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures { buildConfig = false; compose = true; prefab = true }
    externalNativeBuild { cmake { path = file("src/main/jni/CMakeLists.txt"); version = "3.31.0" } }
    androidResources { additionalParameters += listOf("--package-id", "0x80") }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/server-assets"))
}
kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_21 } }

val prepareServer by tasks.registering(Copy::class) {
    dependsOn(":permission-server:assembleRelease")
    from(project(":permission-server").layout.buildDirectory.file("outputs/apk/release/permission-server-release-unsigned.apk"))
    into(layout.buildDirectory.dir("generated/server-assets"))
    rename { "permission-server.apk" }
}
tasks.named("preBuild") { dependsOn(prepareServer) }

dependencies {
    compileOnly(project(":plugin-inprocess-api"))
    // Host-shared UI/runtime libraries are resolved parent-first from AI Limbs; keep them out of payload DEX.
    implementation(platform(libs.compose.bom))
    compileOnly(libs.compose.ui)
    compileOnly(libs.compose.material3)
    compileOnly(libs.compose.material.icons.extended)
    compileOnly(libs.activity.compose)
    compileOnly(libs.coroutines.android)
    implementation("org.bouncycastle:bcpkix-jdk18on:1.80")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    implementation("io.github.vvb2060.ndk:boringssl:20250114")
    implementation("org.lsposed.libcxx:libcxx:27.0.12077973")
}
