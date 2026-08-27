package com.ai.assistance.operit.util

import android.content.Context
import android.os.Environment
import java.io.File

object OperitPaths {

    private const val AI_LIMBS_DIR_NAME = "AiLimbs"
    private const val LEGACY_OPERIT_DIR_NAME = "Operit"
    private const val CLEAN_ON_EXIT_DIR_NAME = "cleanOnExit"
    private const val PLUGINS_DIR_NAME = "plugins"
    private const val MCP_PLUGINS_DIR_NAME = "mcp_plugins"
    private const val BRIDGE_DIR_NAME = "bridge"
    private const val EXPORTS_DIR_NAME = "exports"
    private const val WORKSPACE_DIR_NAME = "workspace"
    private const val TEST_DIR_NAME = "test"
    private const val WEBSESSION_DIR_NAME = "websession"
    private const val USERSCRIPTS_DIR_NAME = "userscripts"

    const val SHERPA_NCNN_MODELS_DIR_NAME = ".sherpa_ncnn_models"
    const val VECTOR_INDEX_DIR_NAME = ".vector_index"

    const val IMAGE_POOL_DIR_NAME = "image_pool"
    const val MEDIA_POOL_DIR_NAME = "media_pool"
    const val SKILL_REPO_ZIP_POOL_DIR_NAME = "skill_repo_zip_pool"

    fun downloadsDir(): File {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    }

    fun aiLimbsRootDir(): File {
        val downloads = downloadsDir()
        val primary = File(downloads, AI_LIMBS_DIR_NAME)
        if (primary.exists()) return ensureDir(primary)

        val legacy = File(downloads, LEGACY_OPERIT_DIR_NAME)
        if (legacy.exists()) {
            if (legacy.renameTo(primary)) return ensureDir(primary)
            return ensureDir(legacy)
        }
        return ensureDir(primary)
    }

    /** Source compatibility for callers that still use the old helper name. */
    fun operitRootDir(): File = aiLimbsRootDir()

    fun legacyOperitRootDir(): File = File(downloadsDir(), LEGACY_OPERIT_DIR_NAME)

    fun cleanOnExitDir(): File {
        return ensureDir(File(aiLimbsRootDir(), CLEAN_ON_EXIT_DIR_NAME))
    }

    fun pluginsDir(): File {
        return ensureDir(File(aiLimbsRootDir(), PLUGINS_DIR_NAME))
    }

    fun pluginConfigDir(pluginId: String): File {
        val trimmed = pluginId.trim()
        val safeBaseName =
            trimmed
                .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
                .trim('.', ' ')
                .ifBlank { "plugin" }
        val safeName =
            if (safeBaseName == trimmed) {
                safeBaseName
            } else {
                "$safeBaseName-${Integer.toHexString(trimmed.hashCode())}"
            }
        return ensureDir(File(pluginsDir(), safeName))
    }

    fun cleanOnExitInternalDir(context: Context): File {
        return ensureDir(File(ensureDir(File(context.cacheDir, AI_LIMBS_DIR_NAME)), CLEAN_ON_EXIT_DIR_NAME))
    }

    fun mcpPluginsDir(): File {
        return ensureDir(File(aiLimbsRootDir(), MCP_PLUGINS_DIR_NAME))
    }

    fun bridgeDir(): File {
        return ensureDir(File(aiLimbsRootDir(), BRIDGE_DIR_NAME))
    }

    fun exportsDir(): File {
        return ensureDir(File(aiLimbsRootDir(), EXPORTS_DIR_NAME))
    }

    fun workspaceDir(): File {
        return ensureDir(File(aiLimbsRootDir(), WORKSPACE_DIR_NAME))
    }

    fun testDir(): File {
        return ensureDir(File(aiLimbsRootDir(), TEST_DIR_NAME))
    }

    fun webSessionDir(): File {
        return ensureDir(File(aiLimbsRootDir(), WEBSESSION_DIR_NAME))
    }

    fun webSessionUserscriptsDir(): File {
        return ensureDir(File(webSessionDir(), USERSCRIPTS_DIR_NAME))
    }

    fun sherpaNcnnModelsDir(context: Context): File {
        return ensureDir(File(context.filesDir, SHERPA_NCNN_MODELS_DIR_NAME))
    }

    fun vectorIndexDir(context: Context): File {
        return ensureDir(File(context.filesDir, VECTOR_INDEX_DIR_NAME))
    }

    fun imagePoolDir(baseDir: File): File {
        return ensureDir(File(baseDir, IMAGE_POOL_DIR_NAME))
    }

    fun mediaPoolDir(baseDir: File): File {
        return ensureDir(File(baseDir, MEDIA_POOL_DIR_NAME))
    }

    fun skillRepoZipPoolDir(baseDir: File): File {
        return ensureDir(File(baseDir, SKILL_REPO_ZIP_POOL_DIR_NAME))
    }

    fun rawSnapshotExcludedFilesTopLevelDirNames(): Set<String> {
        return setOf(
            SHERPA_NCNN_MODELS_DIR_NAME,
            VECTOR_INDEX_DIR_NAME,
            IMAGE_POOL_DIR_NAME,
            MEDIA_POOL_DIR_NAME,
            SKILL_REPO_ZIP_POOL_DIR_NAME,
        )
    }

    fun aiLimbsRootPathSdcard(): String {
        return "/sdcard/Download/${aiLimbsRootDir().name}"
    }

    /** Source compatibility for callers that still use the old helper name. */
    fun operitRootPathSdcard(): String = aiLimbsRootPathSdcard()

    fun legacyOperitRootPathSdcard(): String = "/sdcard/Download/$LEGACY_OPERIT_DIR_NAME"

    fun cleanOnExitPathSdcard(): String {
        return "${aiLimbsRootPathSdcard()}/$CLEAN_ON_EXIT_DIR_NAME"
    }

    fun pluginsPathSdcard(): String {
        return "${aiLimbsRootPathSdcard()}/$PLUGINS_DIR_NAME"
    }

    fun bridgePathSdcard(): String {
        return "${aiLimbsRootPathSdcard()}/$BRIDGE_DIR_NAME"
    }

    fun exportsPathSdcard(): String {
        return "${aiLimbsRootPathSdcard()}/$EXPORTS_DIR_NAME"
    }

    fun workspacePathSdcard(chatId: String): String {
        return "${aiLimbsRootPathSdcard()}/$WORKSPACE_DIR_NAME/$chatId"
    }

    fun testPathSdcard(): String {
        return "${aiLimbsRootPathSdcard()}/$TEST_DIR_NAME"
    }

    fun webSessionUserscriptsPathSdcard(): String {
        return "${aiLimbsRootPathSdcard()}/$WEBSESSION_DIR_NAME/$USERSCRIPTS_DIR_NAME"
    }

    private fun ensureDir(dir: File): File {
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }
}
