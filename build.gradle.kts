buildscript {
    val objectboxVersion by extra("5.3.0")
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("io.objectbox:objectbox-gradle-plugin:$objectboxVersion")
    }
}

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}

tasks.register("assembleDebugClone") {
    dependsOn(":app:assembleClone")
    description = "Build the clone (co-installable) debug APK with package name suffix .clone"
}


// Freecess lab branch only: keep the existing cloud workflow unchanged while
// making its ordinary assembleDebug build and export the isolated probe APKs.
val freecessProbeDebugTasks = listOf(
    ":freecess-probe:assembleBaseline0643Debug",
    ":freecess-probe:assembleSystemExemptedDebug",
    ":freecess-probe:assembleScreenReapplyDebug",
    ":freecess-probe:assembleHostSignalsDebug",
    ":freecess-probe:assembleSuspendDetectDebug",
    ":freecess-probe:assembleForceRebuildDebug"
)

val exportFreecessProbeApks = tasks.register<Copy>("exportFreecessProbeApks") {
    dependsOn(freecessProbeDebugTasks)
    from(project(":freecess-probe").layout.buildDirectory.dir("outputs/apk")) {
        include("**/*.apk")
    }
    into(project(":app").layout.buildDirectory.dir("outputs/apk/freecess-probe"))
}

gradle.projectsEvaluated {
    project(":app").tasks.named("assembleDebug").configure {
        dependsOn(freecessProbeDebugTasks)
        finalizedBy(exportFreecessProbeApks)
    }
}
