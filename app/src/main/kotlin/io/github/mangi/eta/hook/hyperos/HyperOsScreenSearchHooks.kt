package io.github.mangi.eta.hook.hyperos

import android.app.Service
import android.content.Intent
import io.github.libxposed.api.XposedModule
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.ModuleLogger
import io.github.mangi.eta.core.safeLogType

internal object HyperOsScreenSearchHooks {
    private const val SERVICE_CLASS = "com.xiaomi.voiceassistant.VoiceService"

    fun install(
        module: XposedModule,
        logger: ModuleLogger,
        classLoader: ClassLoader,
    ): HookInstallation {
        val hooks = HookRegistrar(module, logger, "HyperOsScreenSearch")
        return hooks.install {
            val serviceClass = HookSupport.findClassOrNull(classLoader, SERVICE_CLASS)
            val method = serviceClass?.let {
                HookSupport.findMethod(
                    it,
                    "onStartCommand",
                    Intent::class.java,
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                )
            }
            if (serviceClass == null || !Service::class.java.isAssignableFrom(serviceClass) ||
                method == null || method.declaringClass != serviceClass ||
                method.returnType != Int::class.javaPrimitiveType
            ) {
                missing(
                    "hyperos.screen-search-service",
                    "VoiceService.onStartCommand",
                    "HyperOS: 未找到匹配签名的小爱识屏服务入口",
                )
                return@install
            }
            intercept(
                "hyperos.screen-search-service",
                method,
                "VoiceService.onStartCommand",
            ) { chain ->
                if (!HyperOsSearchTrigger.isEnabled()) return@intercept chain.proceed()
                val intent = chain.getArg(0) as? Intent
                val matches = try {
                    HyperOsScreenSearchRequest.matches(intent)
                } catch (exception: Exception) {
                    hooks.logger.warnThrottled("hyperos_screen_search_invalid_intent") {
                        "HyperOS: 无法读取识屏请求，保留原逻辑，type=${exception.safeLogType()}"
                    }
                    false
                }
                if (!matches) return@intercept chain.proceed()
                val service = chain.getThisObject() as Service
                if (!HyperOsSearchTrigger.trigger(service, hooks.logger)) {
                    hooks.logger.warnThrottled("hyperos_screen_search_dispatch_failed") {
                        "HyperOS: 导航识屏搜索请求未发出，回退小爱识屏"
                    }
                    return@intercept chain.proceed()
                }
                finishHandledStart(service, chain.getArg(2) as Int, hooks.logger)
                hooks.logger.debug { "HyperOS: 导航识屏请求已交给 contextual_search" }
                Service.START_NOT_STICKY
            }
        }
    }

    private fun finishHandledStart(service: Service, startId: Int, logger: ModuleLogger) {
        try {
            // 仅在本次仍是最新启动时清理服务，不能用无条件 stopSelf 丢弃后续请求。
            if (service.stopSelfResult(startId)) {
                service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
            }
        } catch (exception: Exception) {
            // 搜索已经发出，清理失败也不能再执行原识屏入口导致双重唤起。
            logger.warnThrottled("hyperos_screen_search_cleanup_failed") {
                "HyperOS: 识屏服务启动清理失败，type=${exception.safeLogType()}"
            }
        }
    }
}
