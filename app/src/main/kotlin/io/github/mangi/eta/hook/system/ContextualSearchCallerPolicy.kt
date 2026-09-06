package io.github.mangi.eta.hook.system

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import io.github.mangi.eta.core.ModuleConfig

internal object ContextualSearchCallerPolicy {
    fun allowsHyperOsCaller(
        context: Context,
        callingPackages: Array<String>,
        gestureEnabled: Boolean,
    ): Boolean = gestureEnabled && callingPackages.any { packageName ->
        (packageName in ModuleConfig.XIAOMI_LAUNCHER_PACKAGES || packageName == ModuleConfig.XIAOAI_PACKAGE) &&
            isSystemPackage(context, packageName)
    }

    private fun isSystemPackage(context: Context, packageName: String): Boolean = try {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
