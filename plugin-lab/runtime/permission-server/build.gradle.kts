plugins {
    alias(libs.plugins.android.application)
}
android {
    namespace = "com.ai.limbs.permission.server"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.ai.limbs.permission.server.v022"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.2.2"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildTypes { getByName("release") { isMinifyEnabled = false } }
}
dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    implementation(project(":ail-permission-server-shared"))
    implementation("dev.rikka.hidden:compat:4.4.0")
    compileOnly("dev.rikka.hidden:stub:4.4.0")
    testImplementation("junit:junit:4.13.2")
}
