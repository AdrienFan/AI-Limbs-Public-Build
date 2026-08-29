plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ailimbs.freecessprobe"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ailimbs.freecessprobe"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    flavorDimensions += "experiment"
    productFlavors {
        create("baseline0643") {
            dimension = "experiment"
            manifestPlaceholders["appLabel"] = "Freecess Probe A · 0643"
            applicationIdSuffix = ".a"
            versionNameSuffix = "-a"
            buildConfigField("String", "PROBE_LABEL", "\"A · 0643 baseline\"")
            buildConfigField("boolean", "USE_SYSTEM_EXEMPTED", "false")
            buildConfigField("boolean", "REAPPLY_FGS_ON_SCREEN_OFF", "false")
            buildConfigField("boolean", "USE_HOST_SIGNALS", "false")
            buildConfigField("boolean", "USE_SUSPEND_DETECTOR", "false")
            buildConfigField("boolean", "FORCE_REBUILD_ON_SUSPEND", "false")
        }
        create("systemExempted") {
            dimension = "experiment"
            manifestPlaceholders["appLabel"] = "Freecess Probe B · systemExempted"
            applicationIdSuffix = ".b"
            versionNameSuffix = "-b"
            buildConfigField("String", "PROBE_LABEL", "\"B · + systemExempted\"")
            buildConfigField("boolean", "USE_SYSTEM_EXEMPTED", "true")
            buildConfigField("boolean", "REAPPLY_FGS_ON_SCREEN_OFF", "false")
            buildConfigField("boolean", "USE_HOST_SIGNALS", "false")
            buildConfigField("boolean", "USE_SUSPEND_DETECTOR", "false")
            buildConfigField("boolean", "FORCE_REBUILD_ON_SUSPEND", "false")
        }
        create("screenReapply") {
            dimension = "experiment"
            manifestPlaceholders["appLabel"] = "Freecess Probe C · screen reapply"
            applicationIdSuffix = ".c"
            versionNameSuffix = "-c"
            buildConfigField("String", "PROBE_LABEL", "\"C · + screen-off FGS reapply\"")
            buildConfigField("boolean", "USE_SYSTEM_EXEMPTED", "true")
            buildConfigField("boolean", "REAPPLY_FGS_ON_SCREEN_OFF", "true")
            buildConfigField("boolean", "USE_HOST_SIGNALS", "false")
            buildConfigField("boolean", "USE_SUSPEND_DETECTOR", "false")
            buildConfigField("boolean", "FORCE_REBUILD_ON_SUSPEND", "false")
        }
        create("hostSignals") {
            dimension = "experiment"
            manifestPlaceholders["appLabel"] = "Freecess Probe D · host signals"
            applicationIdSuffix = ".d"
            versionNameSuffix = "-d"
            buildConfigField("String", "PROBE_LABEL", "\"D · + host/network signals\"")
            buildConfigField("boolean", "USE_SYSTEM_EXEMPTED", "true")
            buildConfigField("boolean", "REAPPLY_FGS_ON_SCREEN_OFF", "true")
            buildConfigField("boolean", "USE_HOST_SIGNALS", "true")
            buildConfigField("boolean", "USE_SUSPEND_DETECTOR", "false")
            buildConfigField("boolean", "FORCE_REBUILD_ON_SUSPEND", "false")
        }
        create("suspendDetect") {
            dimension = "experiment"
            manifestPlaceholders["appLabel"] = "Freecess Probe E · suspend detect"
            applicationIdSuffix = ".e"
            versionNameSuffix = "-e"
            buildConfigField("String", "PROBE_LABEL", "\"E · + suspend detector\"")
            buildConfigField("boolean", "USE_SYSTEM_EXEMPTED", "true")
            buildConfigField("boolean", "REAPPLY_FGS_ON_SCREEN_OFF", "true")
            buildConfigField("boolean", "USE_HOST_SIGNALS", "true")
            buildConfigField("boolean", "USE_SUSPEND_DETECTOR", "true")
            buildConfigField("boolean", "FORCE_REBUILD_ON_SUSPEND", "false")
        }
        create("forceRebuild") {
            dimension = "experiment"
            manifestPlaceholders["appLabel"] = "Freecess Probe F · force rebuild"
            applicationIdSuffix = ".f"
            versionNameSuffix = "-f"
            buildConfigField("String", "PROBE_LABEL", "\"F · + force rebuild\"")
            buildConfigField("boolean", "USE_SYSTEM_EXEMPTED", "true")
            buildConfigField("boolean", "REAPPLY_FGS_ON_SCREEN_OFF", "true")
            buildConfigField("boolean", "USE_HOST_SIGNALS", "true")
            buildConfigField("boolean", "USE_SUSPEND_DETECTOR", "true")
            buildConfigField("boolean", "FORCE_REBUILD_ON_SUSPEND", "true")
        }
    }
    buildTypes {
        debug { isMinifyEnabled = false }
        release { isMinifyEnabled = false }
    }
    buildFeatures { buildConfig = true }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
