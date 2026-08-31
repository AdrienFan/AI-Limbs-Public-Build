import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins { alias(libs.plugins.android.application); alias(libs.plugins.kotlin.android) }
android {
    namespace = "com.ai.limbs.extensions.triggercmd"
    compileSdk = 36
    defaultConfig { applicationId = "com.ai.limbs.payload.triggercmd"; minSdk = 26; targetSdk = 34; versionCode = 1; versionName = "1.0.0" }
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
    implementation("io.socket:socket.io-client:1.0.2") { exclude(group = "org.json", module = "json") }
}
