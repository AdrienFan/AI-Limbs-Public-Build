plugins { id("com.android.application") }

android {
    namespace = "com.ailimbs.netmutualprobe"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.ailimbs.netmutualprobe.guardian"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.2-net-guardian-a"
        manifestPlaceholders["appLabel"] = "LEV Net Guardian A"
        buildConfigField("String", "ROLE", "\"NET GUARDIAN A\"")
        buildConfigField("String", "PARTNER_PACKAGE", "\"com.ailimbs.netmutualprobe.target\"")
        buildConfigField("String", "TOPIC", "\"ailimbs-lev-net-guardian-a-8260828\"")
    }
    sourceSets["main"].manifest.srcFile("../shared/AndroidManifest.xml")
    sourceSets["main"].java.srcDir("../shared/java")
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
