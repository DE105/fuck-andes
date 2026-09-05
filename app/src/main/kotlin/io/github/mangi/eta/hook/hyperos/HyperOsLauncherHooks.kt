package io.github.mangi.eta.hook.hyperos

import android.content.Context
import android.view.MotionEvent
import io.github.libxposed.api.XposedModule
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.ModuleLogger
import java.lang.reflect.Modifier

internal object HyperOsLauncherHooks {
    fun install(module: XposedModule, logger: ModuleLogger, loader: ClassLoader): HookInstallation {
        val hooks = HookRegistrar(module, logger, "HyperOsLauncher")
        return hooks.install {
            // 同一桌面可能保留多个版本的辅助类；只安装一个可用入口，避免嵌套调用重复触发。
            if (installGestureManager(hooks, loader)) return@install
            if (installOmni(hooks, loader)) return@install
            if (installEventHelper(hooks, loader)) return@install
            HyperOsLegacyGesture.install(hooks, loader)
        }
    }

    private fun installGestureManager(hooks: HookRegistrar, loader: ClassLoader): Boolean {
        val type = HookSupport.findClassOrNull(loader, "com.miui.home.recents.gesture.NavStubGestureEventManager")
        val method = type?.let { HookSupport.findMethod(it, "handleLongPressEvent") }
        val application = HookSupport.findClassOrNull(loader, "com.miui.home.launcher.Application")
        val getInstance = application?.let { HookSupport.findMethod(it, "getInstance") }
        if (method?.returnType != Void.TYPE || getInstance == null ||
            !Modifier.isStatic(getInstance.modifiers) || !Context::class.java.isAssignableFrom(getInstance.returnType)
        ) {
            hooks.missing("hyperos.gesture-manager", "NavStubGestureEventManager.handleLongPressEvent", "HyperOS: 手势管理器入口或 Application.getInstance 不匹配")
            return false
        }
        return hooks.intercept("hyperos.gesture-manager", method, "NavStubGestureEventManager.handleLongPressEvent") { chain ->
            if (!HyperOsSearchTrigger.isEnabled()) return@intercept chain.proceed()
            if (HyperOsSearchTrigger.trigger(getInstance.invoke(null) as? Context, hooks.logger)) null
            else chain.proceed()
        } != null
    }

    private fun installOmni(hooks: HookRegistrar, loader: ClassLoader): Boolean {
        val type = HookSupport.findClassOrNull(loader, "com.miui.home.recents.cts.CircleToSearchHelper")
        val method = type?.let {
            HookSupport.findMethod(it, "invokeOmni", Context::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
        }
        if (method == null || method.returnType !in setOf(Void.TYPE, Boolean::class.javaPrimitiveType)) {
            hooks.missing("hyperos.invoke-omni", "CircleToSearchHelper.invokeOmni", "HyperOS: invokeOmni 签名不匹配")
            return false
        }
        return hooks.intercept("hyperos.invoke-omni", method, "CircleToSearchHelper.invokeOmni") { chain ->
            if (HyperOsSearchTrigger.trigger(chain.getArg(0) as? Context, hooks.logger)) {
                if (method.returnType == Void.TYPE) null else true
            } else chain.proceed()
        } != null
    }

    private fun installEventHelper(hooks: HookRegistrar, loader: ClassLoader): Boolean {
        val type = HookSupport.findClassOrNull(loader, "com.miui.home.recents.cts.NavBarEventHelper")
        val method = type?.let { HookSupport.findMethod(it, "onLongPress", MotionEvent::class.java) }
        val context = type?.let { HookSupport.findField(it, "mContext") }
        if (method?.returnType != Void.TYPE || context == null || !Context::class.java.isAssignableFrom(context.type)) {
            hooks.missing("hyperos.navbar-event", "NavBarEventHelper.onLongPress", "HyperOS: 导航条事件入口或 mContext 不匹配")
            return false
        }
        return hooks.intercept("hyperos.navbar-event", method, "NavBarEventHelper.onLongPress") { chain ->
            if (HyperOsSearchTrigger.trigger(context.get(chain.getThisObject()) as? Context, hooks.logger)) null
            else chain.proceed()
        } != null
    }
}
