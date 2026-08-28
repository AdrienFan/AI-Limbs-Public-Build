plugins { id("com.android.application") }

android {
    namespace = "com.ailimbs.netmutualprobe"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.ailimbs.netmutualprobe.target"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.2-net-target-b"
        manifestPlaceholders["appLabel"] = "LEV Net Target B"
        buildConfigField("String", "ROLE", "\"NET TARGET B\"")
        buildConfigField("String", "PARTNER_PACKAGE", "\"com.ailimbs.netmutualprobe.guardian\"")
        buildConfigField("String", "TOPIC", "\"ailimbs-lev-net-target-b-8260828\"")
    }
    sourceSets["main"].manifest.srcFile("../shared/AndroidManifest.xml")
    sourceSets["main"].java.srcDir("../shared/java")
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
