package io.github.mangi.eta.hook.system

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import io.github.mangi.eta.core.ModuleConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ContextualSearchCallerPolicyTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `允许系统桌面与小爱调用导航搜索`() {
        for (name in ModuleConfig.XIAOMI_LAUNCHER_PACKAGES + ModuleConfig.XIAOAI_PACKAGE) {
            install(name, ApplicationInfo.FLAG_SYSTEM)
            assertTrue(allowed(name))
        }
    }

    @Test
    fun `系统应用更新仍可用但开关关闭立即撤销权限`() {
        install(ModuleConfig.XIAOAI_PACKAGE, ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)
        assertTrue(allowed(ModuleConfig.XIAOAI_PACKAGE))
        assertFalse(ContextualSearchCallerPolicy.allowsHyperOsCaller(
            context,
            arrayOf(ModuleConfig.XIAOAI_PACKAGE),
            gestureEnabled = false,
        ))
    }

    @Test
    fun `同名普通安装包和其他系统包不能获得权限`() {
        for (name in ModuleConfig.XIAOMI_LAUNCHER_PACKAGES + ModuleConfig.XIAOAI_PACKAGE) {
            install(name, 0)
            assertFalse(allowed(name))
        }
        install("com.example.other", ApplicationInfo.FLAG_SYSTEM)
        assertFalse(allowed("com.example.other"))
    }

    @Test
    fun `包不存在或调用方列表为空时拒绝`() {
        assertFalse(allowed(ModuleConfig.XIAOAI_PACKAGE))
        assertFalse(ContextualSearchCallerPolicy.allowsHyperOsCaller(context, emptyArray(), true))
    }

    @Test
    fun `共享UID仍要求其中存在可信系统包`() {
        install(ModuleConfig.XIAOAI_PACKAGE, ApplicationInfo.FLAG_SYSTEM)
        assertTrue(ContextualSearchCallerPolicy.allowsHyperOsCaller(
            context,
            arrayOf("com.example.other", ModuleConfig.XIAOAI_PACKAGE),
            true,
        ))
        assertFalse(ContextualSearchCallerPolicy.allowsHyperOsCaller(
            context,
            arrayOf("com.example.other", "com.miui.voiceassist.fake"),
            true,
        ))
    }

    private fun allowed(name: String): Boolean =
        ContextualSearchCallerPolicy.allowsHyperOsCaller(context, arrayOf(name), true)

    private fun install(name: String, flags: Int) {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = name
            applicationInfo = ApplicationInfo().apply {
                packageName = name
                this.flags = flags
            }
        })
    }
}
