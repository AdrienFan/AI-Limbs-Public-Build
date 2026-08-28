plugins { id("com.android.application") }

android {
    namespace = "com.ailimbs.mutualprobe"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.ailimbs.mutualprobe.target"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-target-b"
        manifestPlaceholders["appLabel"] = "LEV Target Probe B"
        buildConfigField("String", "ROLE", "\"TARGET B\"")
        buildConfigField("String", "PARTNER_PACKAGE", "\"com.ailimbs.mutualprobe.guardian\"")
    }
    sourceSets["main"].manifest.srcFile("../shared/AndroidManifest.xml")
    sourceSets["main"].java.srcDir("../shared/java")
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
