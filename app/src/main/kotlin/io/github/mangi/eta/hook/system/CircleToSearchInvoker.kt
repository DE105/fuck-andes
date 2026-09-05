package io.github.mangi.eta.hook.system

import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.core.ModuleLogger

import android.content.Context
import android.content.Intent
import android.os.IBinder
import java.lang.reflect.Method

internal object CircleToSearchInvoker {

    @Volatile
    private var getServiceMethod: Method? = null

    @Volatile
    private var asInterfaceMethod: Method? = null

    @Volatile
    private var startContextualSearchMethod: StartContextualSearchMethod? = null

    fun isAvailable(
        context: Context,
        logger: ModuleLogger,
        source: String,
        fallbackMessage: String
    ): Boolean {
        if (!HookSupport.isPackageInstalled(context, ModuleConfig.GOOGLE_PACKAGE)) {
            logger.warnThrottled("${source}_cts_google_missing") {
                "$source: Google App 未安装，$fallbackMessage"
            }
            return false
        }

        val intent = Intent(ModuleConfig.CONTEXTUAL_SEARCH_ACTION).setPackage(ModuleConfig.GOOGLE_PACKAGE)
        if (!HookSupport.resolvesActivity(context, intent)) {
            logger.warnThrottled("${source}_cts_entry_missing") {
                "$source: Google App 未暴露 Contextual Search 入口，$fallbackMessage"
            }
            return false
        }

        val binder = getContextualSearchBinder() ?: run {
            logger.warnThrottled("${source}_cts_service_missing") {
                "$source: contextual_search service 不可用，$fallbackMessage"
            }
            return false
        }

        return binder.isBinderAlive
    }

    fun trigger(
        logger: ModuleLogger,
        source: String,
        entryPoint: Int = ModuleConfig.CIRCLE_TO_SEARCH_ENTRYPOINT,
    ): Boolean {
        val binder = getContextualSearchBinder() ?: return false
        return runCatching {
            // 直接调用系统 binder，避免再走 OEM OCR/识屏分发链。
            val asInterface = resolveAsInterfaceMethod() ?: return@runCatching false
            val startContextualSearch = resolveStartContextualSearchMethod() ?: return@runCatching false
            val service = asInterface.invoke(null, binder) ?: return@runCatching false
            if (startContextualSearch.hasConfigParameter) {
                startContextualSearch.method.invoke(
                    service,
                    entryPoint,
                    null
                )
            } else {
                startContextualSearch.method.invoke(service, entryPoint)
            }
            logger.debug { "$source: 已触发 Circle to Search" }
            true
        }.getOrElse { throwable ->
            logger.errorThrottled(
                key = "${source}_cts_trigger_failed",
                throwable = throwable
            ) { "$source: 触发 Circle to Search 失败" }
            false
        }
    }

    private fun getContextualSearchBinder(): IBinder? =
        runCatching {
            resolveGetServiceMethod()?.invoke(null, ModuleConfig.CONTEXTUAL_SEARCH_SERVICE) as? IBinder
        }.getOrNull()

    private fun resolveGetServiceMethod(): Method? {
        getServiceMethod?.let { return it }
        return runCatching {
            Class.forName("android.os.ServiceManager")
                .getDeclaredMethod("getService", String::class.java)
                .apply { isAccessible = true }
        }.getOrNull()?.also { getServiceMethod = it }
    }

    private fun resolveAsInterfaceMethod(): Method? {
        asInterfaceMethod?.let { return it }
        return runCatching {
            Class.forName("android.app.contextualsearch.IContextualSearchManager\$Stub")
                .getDeclaredMethod("asInterface", IBinder::class.java)
                .apply { isAccessible = true }
        }.getOrNull()?.also { asInterfaceMethod = it }
    }

    private fun resolveStartContextualSearchMethod(): StartContextualSearchMethod? {
        startContextualSearchMethod?.let { return it }
        val serviceClass = runCatching {
            Class.forName("android.app.contextualsearch.IContextualSearchManager")
        }.getOrNull() ?: return null

        val withConfig = runCatching {
            val configClass = Class.forName("android.app.contextualsearch.ContextualSearchConfig")
            serviceClass.getDeclaredMethod(
                "startContextualSearch",
                Int::class.javaPrimitiveType!!,
                configClass
            ).apply { isAccessible = true }
        }.getOrNull()
        if (withConfig != null) {
            return StartContextualSearchMethod(withConfig, hasConfigParameter = true)
                .also { startContextualSearchMethod = it }
        }

        return runCatching {
            serviceClass.getDeclaredMethod(
                "startContextualSearch",
                Int::class.javaPrimitiveType!!
            ).apply { isAccessible = true }
        }.getOrNull()?.let { method ->
            StartContextualSearchMethod(method, hasConfigParameter = false)
        }?.also { startContextualSearchMethod = it }
    }

    private data class StartContextualSearchMethod(
        val method: Method,
        val hasConfigParameter: Boolean
    )
}
