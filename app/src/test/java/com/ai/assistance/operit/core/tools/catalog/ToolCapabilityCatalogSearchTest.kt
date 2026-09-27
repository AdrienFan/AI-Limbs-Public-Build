package com.ai.assistance.operit.core.tools.catalog

import com.ai.assistance.operit.data.model.ToolParameterSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCapabilityCatalogSearchTest {
    private fun startAppEntry(
        sourceKind: ToolCatalogSourceKind = ToolCatalogSourceKind.INTERNAL,
        metadata: List<String> = listOf("native", "Start an app.", "app package name")
    ) = ToolCatalogEntry(
        targetToolName = if (sourceKind == ToolCatalogSourceKind.PACKAGE) "system_tools:start_app" else "start_app",
        displayName = if (sourceKind == ToolCatalogSourceKind.PACKAGE) "system_tools:start_app" else "start_app",
        description = "启动应用。",
        parameterHints = listOf("package_name [string, required]: 应用包名"),
        sourceKind = sourceKind,
        keywords = if (sourceKind == ToolCatalogSourceKind.PACKAGE) listOf("system_tools", "package") else emptyList(),
        parameters = listOf(
            ToolParameterSchema(
                name = "package_name",
                type = "string",
                description = "应用包名",
                required = true
            )
        ),
        searchMetadata = metadata
    )

    private fun rubyEntry() = ToolCatalogEntry(
        targetToolName = "code_runner:run_ruby",
        displayName = "code_runner:run_ruby",
        description = "运行自定义 Ruby 脚本",
        parameterHints = listOf("script [string, required]: 要执行的 Ruby 脚本内容"),
        sourceKind = ToolCatalogSourceKind.PACKAGE,
        keywords = listOf("code_runner", "package"),
        parameters = listOf(
            ToolParameterSchema(
                name = "script",
                type = "string",
                description = "要执行的 Ruby 脚本内容",
                required = true
            )
        ),
        searchMetadata = listOf("toolpkg")
    )

    @Test
    fun stopWordBy_doesNotMatchRubySubstring() {
        val result = ToolCapabilityCatalog.searchDetailed(
            listOf(rubyEntry(), startAppEntry()),
            "native start installed Android application by package name",
            5
        )
        assertEquals("start_app", result.matches.first().entry.targetToolName)
        assertFalse(result.matches.any { it.entry.targetToolName.contains("run_ruby") })
    }

    @Test
    fun genericPackageKeyword_doesNotQualifyLongQuery() {
        val result = ToolCapabilityCatalog.searchDetailed(
            listOf(rubyEntry()),
            "native start installed Android application package name",
            5
        )
        assertTrue(result.matches.isEmpty())
        assertTrue(result.lowConfidence)
    }

    @Test
    fun providerMetadata_prefersNativeStartApp() {
        val native = startAppEntry(metadata = listOf("native", "start app", "app package name"))
        val toolpkg = startAppEntry(
            sourceKind = ToolCatalogSourceKind.PACKAGE,
            metadata = listOf("toolpkg", "start app", "app package name")
        )
        val result = ToolCapabilityCatalog.searchDetailed(
            listOf(toolpkg, native),
            "native start app",
            5
        )
        assertEquals("start_app", result.matches.first().entry.targetToolName)
        assertFalse(result.lowConfidence)
    }

    @Test
    fun alternateLanguageMetadata_recallsEnglishIntent() {
        val entry = startAppEntry(
            metadata = listOf("native", "Start an app.", "app package name")
        )
        val result = ToolCapabilityCatalog.searchDetailed(
            listOf(entry),
            "start android app package name",
            5
        )
        assertEquals("start_app", result.matches.first().entry.targetToolName)
    }

    @Test
    fun exactToolId_remainsStrongIdentityMatch() {
        val result = ToolCapabilityCatalog.searchDetailed(
            listOf(rubyEntry(), startAppEntry()),
            "start_app",
            5
        )
        assertEquals("start_app", result.matches.first().entry.targetToolName)
        assertTrue(result.matches.first().strongIdentityMatch)
        assertFalse(result.lowConfidence)
    }

    @Test
    fun partialLongQuery_isMarkedLowConfidence() {
        val weakEntry = ToolCatalogEntry(
            targetToolName = "android_helper",
            displayName = "android_helper",
            description = "Android utility",
            parameterHints = emptyList(),
            sourceKind = ToolCatalogSourceKind.PACKAGE,
            keywords = listOf("package")
        )
        val result = ToolCapabilityCatalog.searchDetailed(
            listOf(weakEntry),
            "android package install application launcher service",
            5
        )
        assertTrue(result.matches.isNotEmpty())
        assertTrue(result.lowConfidence)
    }
    private fun ubuntuStartEntry() = ToolCatalogEntry(
        targetToolName = "plugin.ubuntu.start",
        displayName = "启动 Ubuntu",
        description = "启动 Ubuntu 子系统本地 Runtime。",
        parameterHints = emptyList(),
        sourceKind = ToolCatalogSourceKind.PACKAGE,
        keywords = listOf("Ubuntu", "Linux", "启动", "runtime"),
        sourceName = "plugin:ai_limbs.system_environment.ubuntu",
        searchMetadata = listOf("ubuntu subsystem", "system environment")
    )

    @Test
    fun fuzzyTypo_recallsUbuntuCapability() {
        val result = ToolCapabilityCatalog.searchDetailed(
            listOf(rubyEntry(), ubuntuStartEntry()),
            "ubntu",
            8
        )
        assertEquals("plugin.ubuntu.start", result.matches.first().entry.targetToolName)
        assertFalse(result.lowConfidence)
    }

    @Test
    fun fuzzyTransposition_recallsUbuntuCapability() {
        val result = ToolCapabilityCatalog.searchDetailed(
            listOf(ubuntuStartEntry()),
            "ubutnu",
            8
        )
        assertEquals("plugin.ubuntu.start", result.matches.first().entry.targetToolName)
    }

    @Test
    fun providerName_participatesInSearch() {
        val result = ToolCapabilityCatalog.searchDetailed(
            listOf(ubuntuStartEntry()),
            "system environmnt",
            8
        )
        assertEquals("plugin.ubuntu.start", result.matches.first().entry.targetToolName)
    }

    private fun regressionEntry(index: Int) = ToolCatalogEntry(
        targetToolName = "plugin.test.unknown_catalog.capability_$index",
        displayName = "画室回归能力 $index",
        description = "画室图片处理回归能力 $index",
        parameterHints = emptyList(),
        sourceKind = ToolCatalogSourceKind.PACKAGE,
        keywords = listOf("画室", "图片", "基线限额"),
        sourceName = "plugin:plugin.test.unknown_catalog",
        searchMetadata = listOf("unknown plugin regression")
    )

    @Test
    fun chineseIntent_recallsConcreteCapability() {
        val create = regressionEntry(1).copy(
            displayName = "新建画室工程",
            description = "新建图片工程并创建透明画布",
            keywords = listOf("新建", "图片", "画布", "透明")
        )
        val delete = regressionEntry(2).copy(
            displayName = "删除画室工程",
            description = "删除已有图片工程",
            keywords = listOf("删除", "图片")
        )
        val result = ToolCapabilityCatalog.searchDetailed(
            listOf(delete, create),
            "新建透明图片",
            8
        )
        assertEquals(create.targetToolName, result.matches.first().entry.targetToolName)
        assertFalse(result.lowConfidence)
    }

    @Test
    fun resultLimit_isClampedToCatalogBounds() {
        val catalog = (1..25).map(::regressionEntry)
        val capped = ToolCapabilityCatalog.searchDetailed(catalog, "基线限额", 99)
        val floored = ToolCapabilityCatalog.searchDetailed(catalog, "基线限额", 0)
        assertEquals(20, capped.matches.size)
        assertEquals(1, floored.matches.size)
    }


}
