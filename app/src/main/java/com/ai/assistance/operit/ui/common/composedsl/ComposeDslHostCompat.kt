package com.ai.assistance.operit.ui.common.composedsl

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.core.net.toFile
import com.ai.assistance.operit.util.AppLogger
import java.io.File

class TopBarTitleContent(val content: @Composable () -> Unit)

val LocalTopBarTitleContent = compositionLocalOf<(TopBarTitleContent?) -> Unit> { {} }
val LocalIsCurrentScreen = compositionLocalOf { true }
val LocalSetScreenSoftInputMode = compositionLocalOf<(Int?) -> Unit> { {} }
val LocalSetUseScreenImePadding = compositionLocalOf<(Boolean) -> Unit> { {} }

fun getSystemFontFamily(systemFontName: String): FontFamily {
    return when (systemFontName.trim().lowercase()) {
        "serif" -> FontFamily.Serif
        "sans-serif", "sans_serif", "sansserif" -> FontFamily.SansSerif
        "monospace" -> FontFamily.Monospace
        "cursive" -> FontFamily.Cursive
        else -> FontFamily.Default
    }
}

fun loadCustomFontFamily(context: Context, fontPath: String): FontFamily? {
    return try {
        val file = if (fontPath.startsWith("file://")) {
            Uri.parse(fontPath).toFile()
        } else {
            File(fontPath)
        }
        if (!file.exists()) {
            AppLogger.w("ComposeDslHost", "Font file does not exist: $fontPath")
            null
        } else {
            FontFamily(Font(file))
        }
    } catch (error: Throwable) {
        AppLogger.w("ComposeDslHost", "Unable to load custom font: ${error.message}")
        null
    }
}
