package io.github.mangi.eta.hook.xiaoai

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.R
import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.ModuleLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.hook.EtaInjectedStrings
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal class XiaoAiStreamRenderer(
    private val context: Context,
    private val logger: ModuleLogger,
    private val classLoader: ClassLoader,
    private val dialogId: String,
    private val floatManagerProvider: () -> Any?,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val card = AtomicReference<Any?>()
    private val cancelled = AtomicBoolean(false)
    private val streamedText = StringBuilder()
    private val pendingText = AtomicReference<String?>(null)
    private val flushPosted = AtomicBoolean(false)
    private val cardRetryCount = AtomicInteger()

    fun onEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.ModelRetryScheduled -> render(event.displayMessage)

            is AgentEvent.RunStarted,
            is AgentEvent.ProviderRequestStarted,
            is AgentEvent.ProviderResponseStarted -> render(
                text(R.string.injected_reasoning, "Eta is reasoning…"),
            )

            is AgentEvent.AssistantBlockStart -> {
                if (event.kind == AgentEvent.AssistantBlockKind.TEXT) {
                    synchronized(streamedText) { streamedText.setLength(0) }
                }
            }

            is AgentEvent.AssistantBlockDelta -> {
                if (event.kind == AgentEvent.AssistantBlockKind.TEXT) {
                    val text = synchronized(streamedText) {
                        streamedText.append(event.delta).toString()
                    }
                    render(text)
                }
            }

            is AgentEvent.ToolStarted -> {
                if (synchronized(streamedText) { streamedText.isEmpty() }) {
                    render(text(R.string.injected_executing, "Eta is working…"))
                }
            }

            else -> Unit
        }
    }

    fun complete(content: String) {
        if (cancelled.get()) return
        val finalText = content.trim().ifBlank {
            text(R.string.injected_completed, "Eta completed this task")
        }
        render(finalText, immediate = true)
        mainHandler.post {
            if (!cancelled.get()) speak(finalText)
        }
    }

    fun fail() {
        if (cancelled.get()) return
        render(
            text(
                R.string.injected_failed,
                "Eta could not complete the task. Try again later",
            ),
            immediate = true,
        )
    }

    fun cancel(loader: ClassLoader = classLoader) {
        if (!cancelled.compareAndSet(false, true)) return
        pendingText.set(null)
        mainHandler.post {
            runCatching {
                val player = ttsPlayer(loader) ?: return@runCatching
                HookSupport.findMethod(player.javaClass, "stopPlay")?.invoke(player)
            }
        }
    }

    private fun render(text: String, immediate: Boolean = false) {
        if (cancelled.get() || text.isBlank()) return
        pendingText.set(text)
        if (immediate) {
            mainHandler.post { flush() }
            return
        }
        if (flushPosted.compareAndSet(false, true)) {
            mainHandler.postDelayed(::flush, STREAM_FLUSH_DELAY_MILLIS)
        }
    }

    private fun text(resourceId: Int, englishFallback: String): String =
        EtaInjectedStrings.get(context, resourceId, englishFallback)

    private fun flush() {
        flushPosted.set(false)
        if (cancelled.get()) return
        val text = pendingText.getAndSet(null) ?: return
        val targetCard = card.get() ?: createCard(text)
        if (targetCard == null) {
            pendingText.compareAndSet(null, text)
            if (cardRetryCount.incrementAndGet() <= MAX_CARD_RETRIES) {
                mainHandler.postDelayed(::flush, CARD_RETRY_DELAY_MILLIS)
            }
            return
        }
        cardRetryCount.set(0)
        if (card.get() === targetCard) {
            invokeCompatible(targetCard, "updateCardText", text)
        }
    }

    private fun createCard(initialText: String): Any? {
        if (cancelled.get()) return null
        return try {
            val cardClass = Class.forName(
                XiaoAiHooks.FLOW_TOAST_CARD_CLASS,
                false,
                classLoader,
            )
            val created = cardClass.getConstructor(String::class.java).newInstance(initialText)
            invokeCompatible(created, "setDialogId", dialogId)
            val floatManager = floatManagerProvider()
            if (floatManager == null) {
                logger.warnThrottled("xiaoai_card_sink_unavailable") {
                    "超级小爱结果卡片管理器暂不可用"
                }
                return null
            }
            invokeCompatible(floatManager, "addCard", created)
            card.compareAndSet(null, created)
            card.get()
        } catch (exception: Exception) {
            logger.warnThrottled("xiaoai_card_create_failed") {
                "超级小爱结果卡创建失败: type=${exception.safeLogType()}"
            }
            null
        }
    }

    private fun speak(text: String) {
        try {
            val player = ttsPlayer(classLoader) ?: return
            HookSupport.findMethod(player.javaClass, "speakTts", String::class.java)
                ?.invoke(player, text)
        } catch (exception: Exception) {
            logger.warnThrottled("xiaoai_tts_failed") {
                "超级小爱 Eta 结果朗读失败: type=${exception.safeLogType()}"
            }
        }
    }

    private fun ttsPlayer(loader: ClassLoader): Any? {
        val playerClass = Class.forName(XiaoAiHooks.TTS_PLAYER_CLASS, false, loader)
        val singletonField = playerClass.declaredFields.firstOrNull { field ->
            Modifier.isStatic(field.modifiers) && field.type == playerClass
        } ?: return null
        singletonField.isAccessible = true
        return singletonField.get(null)
    }

    private fun invokeCompatible(target: Any, methodName: String, vararg args: Any?): Any? {
        val method = findCompatibleMethod(target.javaClass, methodName, args) ?: return null
        return method.invoke(target, *args)
    }

    private fun findCompatibleMethod(
        clazz: Class<*>,
        methodName: String,
        args: Array<out Any?>,
    ): Method? {
        var current: Class<*>? = clazz
        while (current != null) {
            val method = HookSupport.findDeclaredMethods(current, makeAccessible = true) { candidate ->
                candidate.name == methodName &&
                    candidate.parameterTypes.size == args.size &&
                    candidate.parameterTypes.zip(args).all { (type, arg) ->
                        arg == null || type.wrapPrimitive().isAssignableFrom(arg.javaClass)
                    }
            }.firstOrNull()
            if (method != null) return method
            current = current.superclass
        }
        return null
    }

    private fun Class<*>.wrapPrimitive(): Class<*> =
        when (this) {
            Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
            Int::class.javaPrimitiveType -> Int::class.javaObjectType
            Long::class.javaPrimitiveType -> Long::class.javaObjectType
            Float::class.javaPrimitiveType -> Float::class.javaObjectType
            Double::class.javaPrimitiveType -> Double::class.javaObjectType
            else -> this
        }

    private companion object {
        const val STREAM_FLUSH_DELAY_MILLIS = 80L
        const val CARD_RETRY_DELAY_MILLIS = 250L
        const val MAX_CARD_RETRIES = 6
    }
}
