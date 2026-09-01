pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://dl.bintray.com/rikkaw/Shizuku") }
        maven { url = uri("https://api.xposed.info/") }
        maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots") }
    }
}

rootProject.name = "Operit"
include(":app")
include(":dragonbones")
project(":dragonbones").projectDir = file("avator/dragonbones")
include(":terminal")
include(":mnn")
project(":mnn").projectDir = file("llm/mnn")
include(":llama")
project(":llama").projectDir = file("llm/llama")
include(":mmd")
project(":mmd").projectDir = file("avator/mmd")
include(":fbx")
project(":fbx").projectDir = file("avator/fbx")
include(":showerclient")
include(":quickjs")
include(":plugin-inprocess-api")
project(":plugin-inprocess-api").projectDir = file("plugin-lab/sdk/inprocess-api")
include(":bridge-contract")
project(":bridge-contract").projectDir = file("plugin-lab/sdk/bridge-contract")
include(":plugin-extension-hub")
project(":plugin-extension-hub").projectDir = file("plugin-lab/plugins/extension-hub")
include(":bridge-core-plugin")
project(":bridge-core-plugin").projectDir = file("plugin-lab/plugins/bridge-core")
include(":developer-guide-plugin")
project(":developer-guide-plugin").projectDir = file("plugin-lab/plugins/developer-guide")
include(":rdc-extension")
project(":rdc-extension").projectDir = file("plugin-lab/extensions/rdc")
include(":triggercmd-extension")
project(":triggercmd-extension").projectDir = file("plugin-lab/extensions/triggercmd")
