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
}
