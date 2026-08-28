plugins {
    id("com.android.application")
}

android {
    namespace = "com.ailimbs.levprobe.v1"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ailimbs.levprobe.v1"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}