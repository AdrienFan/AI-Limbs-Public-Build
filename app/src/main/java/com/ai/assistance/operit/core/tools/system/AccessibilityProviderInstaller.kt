package com.ai.assistance.operit.core.tools.system

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.ai.assistance.operit.data.repository.UIHierarchyManager
import com.ai.assistance.operit.util.AppLogger

/**
 * build67 兼容层。
 *
 * 独立 Accessibility Provider APK 已移除；旧调用点仍可通过这里读取
 * AI Limbs 主 APK 版本，并把“安装”动作重定向到系统无障碍设置。
 */
class AccessibilityProviderInstaller {
    companion object {
        private const val TAG = "AccessibilityProviderInstaller"

        fun getBundledVersion(context: Context): String =
            getHostVersion(context)

        fun getInstalledVersion(context: Context): String =
            getHostVersion(context)

        fun isUpdateNeeded(context: Context): Boolean = false

        fun launchInstall(context: Context) {
            AppLogger.i(TAG, "独立无障碍 Provider 已内置到 AI Limbs，打开系统无障碍设置")
            UIHierarchyManager.launchProviderInstall(context)
        }

        fun clearCache() = Unit

        private fun getHostVersion(context: Context): String {
            return try {
                val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(
                        context.packageName,
                        PackageManager.PackageInfoFlags.of(0)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0)
                }
                info.versionName ?: "AI Limbs"
            } catch (error: Exception) {
                AppLogger.w(TAG, "读取 AI Limbs 版本失败: " + error.message)
                "AI Limbs"
            }
        }
    }
}
