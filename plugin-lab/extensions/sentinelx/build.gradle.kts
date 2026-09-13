import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins { alias(libs.plugins.android.application); alias(libs.plugins.kotlin.android) }
android {
    namespace = "com.ai.limbs.extensions.sentinelx"
    compileSdk = 36
    defaultConfig { applicationId = "com.ai.limbs.payload.sentinelx"; minSdk = 26; targetSdk = 34; versionCode = 2; versionName = "0.1.1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { buildConfig = false }
}
kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }
dependencies {
    compileOnly(project(":plugin-inprocess-api"))
    compileOnly(project(":bridge-contract"))
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
