pluginManagement {
    repositories { google(); gradlePluginPortal(); mavenCentral() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
    versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}
rootProject.name = "AI-Limbs-Privacy-Audit"
include(":plugin-inprocess-api", ":privacy-audit-plugin")
project(":plugin-inprocess-api").projectDir = file("../plugin-lab/sdk/inprocess-api")
project(":privacy-audit-plugin").projectDir = file("../plugin-lab/plugins/privacy-audit")
