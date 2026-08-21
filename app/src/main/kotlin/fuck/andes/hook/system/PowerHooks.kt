package fuck.andes.hook.system

import fuck.andes.core.HookSupport
import fuck.andes.core.HookInstallation
import fuck.andes.core.HookRegistrar
import fuck.andes.core.ModuleConfig
import fuck.andes.core.ModuleLogger
import fuck.andes.core.safeLogType
import android.app.ActivityOptions
import android.app.KeyguardManager

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Message
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import fuck.andes.config.PowerAssistantTarget
import fuck.andes.config.Prefs
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field

internal object PowerHooks {
    private const val OEM_ASSISTANT_HAPTIC_EFFECT_ID = 0
    private const val OEM_ASSISTANT_HAPTIC_REASON = "Speech - Long Press"

    @Volatile
    private var lastInterceptUptime = 0L
    @Volatile
    private var lastPowerPressUptime = 0L

    @Volatile
    private var isConsumingPowerPress = false

    @Volatile
    private var suppressSleepUntilUptime = 0L

    @Volatile
    private var cachedPowerKeyHandledField: Field? = null

    @Volatile
    private var cachedDoublePressBehaviorField: Field? = null


    private enum class LaunchResult {
        LAUNCHED,
        ACTIVITY_FALLBACK_REQUIRED,
        NOT_HANDLED
    }

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "Power")
        return hooks.install {
            // 当前机型实测证明 OplusSpeechHandler 是必要路径，目标在热路径即时读取。
            hookOplusSpeechHandler(hooks, classLoader)
            hookInterceptKeyBeforeQueueing(hooks, classLoader)
            hookPowerManagerService(hooks, classLoader)
        }
    }

    private fun hookPowerManagerService(
        hooks: HookRegistrar,
        classLoader: ClassLoader
    ) {
        val logger = hooks.logger
        val pmsClass = HookSupport.findClassOrNull(classLoader, "com.android.server.power.PowerManagerService")
            ?: return

        val methods = HookSupport.findDeclaredMethods(pmsClass, makeAccessible = true) {
            it.name == "goToSleep" || it.name == "goToSleepInternal"
        }
        for (method in methods) {
            hooks.intercept(
                id = "system.power-service-${method.name.lowercase()}",
                executable = method,
                description = "PowerManagerService.${method.name}"
            ) { chain ->
                if (SystemClock.uptimeMillis() < suppressSleepUntilUptime) {
                    logger.debug { "PowerManagerService.${method.name}: 正在抑制双击唤起 Wallet 后的误关屏" }
                    return@intercept null
                }
                chain.proceed()
            }
        }
    }

    private fun hookInterceptKeyBeforeQueueing(
        hooks: HookRegistrar,
        classLoader: ClassLoader
    ) {
        val logger = hooks.logger
        val classesToSearch = listOfNotNull(
            HookSupport.findClassOrNull(classLoader, ModuleConfig.PHONE_WINDOW_MANAGER_CLASS),
            HookSupport.findClassOrNull(classLoader, "com.android.server.policy.PhoneWindowManagerExtImpl")
        )

        var hookCount = 0
        for (clazz in classesToSearch) {
            val methods = HookSupport.findDeclaredMethods(clazz, makeAccessible = true) {
                it.name == "interceptKeyBeforeQueueing" || it.name == "interceptKeyBeforeDispatching"
            }
            for (method in methods) {
                hookCount++
                hooks.intercept(
                    id = "system.power-key-${clazz.simpleName.lowercase()}-${method.name.lowercase()}",
                    executable = method,
                    description = "${clazz.simpleName}.${method.name}"
                ) { chain ->
                    val event = chain.args.firstOrNull { it is KeyEvent } as? KeyEvent
                    if (event != null && event.keyCode == KeyEvent.KEYCODE_POWER) {
                        if (Prefs.isEnabled(Prefs.Keys.POWER_KEY_DOUBLE_PRESS_WALLET)) {
                            val thisObj = chain.getThisObject()
                            val pwm = if (thisObj != null) resolvePhoneWindowManager(thisObj) ?: thisObj else null
                            if (pwm != null) {
                                val result = handlePowerKeyEvent(logger, pwm, event, "${clazz.simpleName}.${method.name}")
                                if (result == 0) {
                                    return@intercept 0
                                }
                            }
                        }
                    }
                    chain.proceed()
                }
            }
        }

        if (hookCount == 0) {
            hooks.missing(
                id = "system.power-key-intercept",
                description = "PhoneWindowManager.interceptKeyBeforeQueueing",
                detail = "缺少 interceptKeyBeforeQueueing 方法"
            )
        }
    }

    private fun hookOplusSpeechHandler(
        hooks: HookRegistrar,
        classLoader: ClassLoader
    ) {
        val logger = hooks.logger
        val handlerClass = HookSupport.findClassOrNull(classLoader, ModuleConfig.OP_LUS_SPEECH_HANDLER_CLASS)
        val handleMessageMethod = handlerClass?.let {
            HookSupport.findMethod(it, "handleMessage", Message::class.java)
        }
        if (handleMessageMethod == null) {
            hooks.missing(
                id = "system.power-assist-message",
                description = "OplusSpeechHandler.handleMessage",
                detail = "未找到 OplusSpeechHandler.handleMessage(Message)"
            )
            return
        }

        hooks.intercept(
            id = "system.power-assist-message",
            executable = handleMessageMethod,
            description = "PhoneWindowManagerExtImpl\$OplusSpeechHandler.handleMessage"
        ) { chain ->
            val message = chain.getArg(0) as? Message
            if (message?.what != ModuleConfig.OP_LUS_ASSIST_MESSAGE_WHAT) {
                return@intercept chain.proceed()
            }
            lastPowerPressUptime = 0L
            isConsumingPowerPress = false
            suppressSleepUntilUptime = SystemClock.uptimeMillis() + 1500L

            val pwm = resolvePhoneWindowManager(chain.getThisObject())
            if (pwm != null) {
                setPowerKeyHandled(pwm, true)
            }

            val target = Prefs.powerAssistantTarget()
            val binding = assistantBindingFor(target)
            if (binding == null) {
                return@intercept chain.proceed()
            }

            val handler = chain.getThisObject() as? Handler
            if (pwm == null) {
                logger.warnThrottled("oplus_speech_missing_pwm") {
                    "OplusSpeechHandler 未能解析 PhoneWindowManager，回退原逻辑"
                }
                return@intercept chain.proceed()
            }

            when (tryLaunchAssistant(
                target = target,
                binding = binding,
                logger = logger,
                phoneWindowManager = pwm,
                source = "OplusSpeechHandler"
            )) {
                LaunchResult.LAUNCHED -> null
                LaunchResult.ACTIVITY_FALLBACK_REQUIRED -> {
                    val activityStarted = tryStartAssistantActivityFallback(
                        target = target,
                        binding = binding,
                        logger = logger,
                        phoneWindowManager = pwm,
                        source = "OplusSpeechHandler"
                    )
                    // Activity 兜底能处理本次触发，但仍需后台修复首选 voiceinteraction 路径。
                    scheduleBackgroundRecovery(
                        handler = handler,
                        logger = logger,
                        phoneWindowManager = pwm,
                        source = "OplusSpeechHandler"
                    )
                    if (activityStarted) {
                        null
                    } else {
                        // 当前触发不等待后台修复；所有快速路径失败后立即回退小布。
                        chain.proceed()
                    }
                }
                LaunchResult.NOT_HANDLED -> chain.proceed()
            }
        }
    }

    private fun tryLaunchAssistant(
        target: PowerAssistantTarget,
        binding: AssistantBinding,
        logger: ModuleLogger,
        phoneWindowManager: Any,
        source: String
    ): LaunchResult {
        val context = HookSupport.getFieldValue(phoneWindowManager, "mContext") as? Context
        if (context == null) {
            logger.warnThrottled("${source}_missing_context") {
                "$source 缺少 mContext，回退原逻辑"
            }
            return LaunchResult.NOT_HANDLED
        }

        if (!HookSupport.isPackageInstalled(context, binding.packageName)) {
            logger.warnThrottled("${source}_${target.persistedValue}_missing") {
                "$source: ${binding.displayName} 未安装，回退原逻辑"
            }
            return LaunchResult.NOT_HANDLED
        }

        val now = SystemClock.uptimeMillis()
        if (now - lastInterceptUptime <= ModuleConfig.INTERCEPT_DEDUP_WINDOW_MS) {
            logger.debug { "$source: 命中去重窗口，直接吞掉重复触发" }
            return LaunchResult.LAUNCHED
        }

        if (AssistantManager.showAssistantSession(
                context = context,
                target = target,
                logger = logger,
                source = source,
                logFailures = false
            )) {
            finalizeSuccessfulLaunch(logger, phoneWindowManager, source, now)
            logger.debug { "$source: 已通过 voiceinteraction 启动 ${binding.displayName}" }
            return LaunchResult.LAUNCHED
        }

        return LaunchResult.ACTIVITY_FALLBACK_REQUIRED
    }

    private fun tryStartAssistantActivityFallback(
        target: PowerAssistantTarget,
        binding: AssistantBinding,
        logger: ModuleLogger,
        phoneWindowManager: Any,
        source: String
    ): Boolean {
        val context = HookSupport.getFieldValue(phoneWindowManager, "mContext") as? Context
            ?: return false
        val now = SystemClock.uptimeMillis()
        return when (target) {
            PowerAssistantTarget.OEM -> false
            PowerAssistantTarget.GEMINI -> startAssistantActivity(
                context = context,
                binding = binding,
                logger = logger,
                phoneWindowManager = phoneWindowManager,
                source = source,
                now = now,
                action = Intent.ACTION_ASSIST,
            ) || startAssistantActivity(
                context = context,
                binding = binding,
                logger = logger,
                phoneWindowManager = phoneWindowManager,
                source = source,
                now = now,
                action = Intent.ACTION_VOICE_COMMAND,
            )
            PowerAssistantTarget.ETA -> {
                if (!AssistantManager.isAssistantConfigured(context, target)) {
                    false
                } else {
                    startAssistantActivity(
                        context = context,
                        binding = binding,
                        logger = logger,
                        phoneWindowManager = phoneWindowManager,
                        source = source,
                        now = now,
                        action = Intent.ACTION_ASSIST,
                    )
                }
            }
        }
    }

    private fun startAssistantActivity(
        context: Context,
        binding: AssistantBinding,
        logger: ModuleLogger,
        phoneWindowManager: Any,
        source: String,
        now: Long,
        action: String
    ): Boolean {
        val intent = Intent(action).apply {
            setPackage(binding.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val resolves = runCatching { HookSupport.resolvesActivity(context, intent) }
            .getOrElse { throwable ->
                logger.warnThrottled("${source}_${action}_resolve_failed") {
                    "$source: 查询 ${binding.displayName} $action 入口失败，" +
                        "type=${throwable.safeLogType()}"
                }
                false
            }
        if (!resolves) {
            logger.warnThrottled("${source}_${action}_missing") {
                "$source: ${binding.displayName} 未暴露 $action，回退原逻辑"
            }
            return false
        }

        return runCatching {
            context.startActivity(intent)
            finalizeSuccessfulLaunch(logger, phoneWindowManager, source, now)
            logger.debug { "$source: 已通过 $action 启动 ${binding.displayName}" }
            true
        }.getOrElse { throwable ->
            logger.warnThrottled("${source}_${action}_failed") {
                "$source: $action 启动失败，回退原逻辑，type=${throwable.safeLogType()}"
            }
            false
        }
    }

    private fun finalizeSuccessfulLaunch(
        logger: ModuleLogger,
        phoneWindowManager: Any,
        source: String,
        now: Long
    ) {
        markLaunchSuccess(now)
        maybePerformAssistantHapticFeedback(logger, phoneWindowManager, source)
    }

    private fun maybePerformAssistantHapticFeedback(
        logger: ModuleLogger,
        phoneWindowManager: Any,
        source: String
    ) {
        if (invokeOplusAssistantHapticFeedback(phoneWindowManager)) {
            logger.debug { "$source: 已补发 Oplus 原生助理震感" }
            return
        }

        logger.warnThrottled("${source}_assistant_haptic_missing") {
            "$source: 未找到 Oplus 原生长按助理震感入口"
        }
    }

    private fun invokeOplusAssistantHapticFeedback(phoneWindowManager: Any): Boolean {
        val wrapper = HookSupport.invokeNoArgs(phoneWindowManager, "getWrapper") ?: return false
        val wrapperMethod = HookSupport.findMethod(
            wrapper.javaClass,
            "performHapticFeedback",
            Int::class.javaPrimitiveType!!,
            String::class.java
        ) ?: return false
        return runCatching {
            wrapperMethod.invoke(wrapper, OEM_ASSISTANT_HAPTIC_EFFECT_ID, OEM_ASSISTANT_HAPTIC_REASON)
            true
        }.getOrDefault(false)
    }

    private fun scheduleBackgroundRecovery(
        handler: Handler?,
        logger: ModuleLogger,
        phoneWindowManager: Any,
        source: String
    ) {
        if (!Prefs.isEnabled(Prefs.Keys.ASSISTANT_AUTO_CONFIG) ||
            Prefs.powerAssistantTarget() == PowerAssistantTarget.OEM
        ) {
            return
        }
        if (handler == null) {
            logger.warnThrottled("${source}_recovery_missing_handler") {
                "$source: 无法取得 OplusSpeechHandler 实例，跳过后台配置修复"
            }
            return
        }

        val context = HookSupport.getFieldValue(phoneWindowManager, "mContext") as? Context
        if (context == null) {
            logger.warnThrottled("${source}_recovery_missing_context") {
                "$source: 无法取得 mContext，跳过后台配置修复"
            }
            return
        }

        val scheduled = AssistantManager.scheduleAssistantRecovery(
            context = context,
            logger = logger,
            handler = handler,
            forceRefresh = true,
        )
        if (!scheduled) {
            logger.warnThrottled("${source}_configuration_schedule_failed") {
                "$source: 默认助理后台修复无法入队"
            }
        } else {
            logger.warnThrottled("${source}_assistant_recovery_pending") {
                "$source: voiceinteraction 失败，已在后台修复默认助理配置"
            }
        }
    }

    private fun markLaunchSuccess(now: Long) {
        lastInterceptUptime = now
    }

    private fun resolvePhoneWindowManager(handlerInstance: Any): Any? {
        val owner = HookSupport.getFieldValue(handlerInstance, "this$0") ?: return null
        HookSupport.findField(owner.javaClass, "mPhoneWindowManager")?.let { field ->
            return runCatching { field.get(owner) }.getOrNull()
        }

        var current: Class<*>? = owner.javaClass
        while (current != null) {
            current.declaredFields.forEach { field ->
                if (field.type.name == ModuleConfig.PHONE_WINDOW_MANAGER_CLASS) {
                    field.isAccessible = true
                    return runCatching { field.get(owner) }.getOrNull()
                }
            }
            current = current.superclass
        }
        return null
    }

    private fun handlePowerKeyEvent(
        logger: ModuleLogger,
        phoneWindowManager: Any,
        event: KeyEvent,
        source: String
    ): Int {
        ensureDoublePressBehaviorEnabled(phoneWindowManager)

        val now = SystemClock.uptimeMillis()

        if (event.action == KeyEvent.ACTION_DOWN) {
            val timeSinceLast = now - lastPowerPressUptime

            if (lastPowerPressUptime > 0L && timeSinceLast in 50L..420L) {
                // 双击电源键第 2 次 DOWN
                lastPowerPressUptime = 0L
                isConsumingPowerPress = true
                suppressSleepUntilUptime = SystemClock.uptimeMillis() + 1500L
                setPowerKeyHandled(phoneWindowManager, true)

                // 唤起目标钱包
                tryLaunchWallet(logger, phoneWindowManager, source)
                return 0 // 拦截第 2 次 DOWN
            } else {
                // 第 1 次 DOWN：放行让 ColorOS 接收 DOWN 事件
                lastPowerPressUptime = now
                isConsumingPowerPress = false
                setPowerKeyHandled(phoneWindowManager, false)

                return 1 // 放行第 1 次 DOWN
            }
        } else if (event.action == KeyEvent.ACTION_UP) {
            if (isConsumingPowerPress) {
                // 双击第 2 次 UP
                isConsumingPowerPress = false
                return 0 // 拦截第 2 次 UP
            }
            // 第 1 次 UP：放行让 ColorOS 接收 UP 事件
            return 1
        }
        return 1
    }

    private fun setPowerKeyHandled(phoneWindowManager: Any, handled: Boolean) {
        runCatching {
            var field = cachedPowerKeyHandledField
            if (field == null) {
                field = HookSupport.findField(phoneWindowManager.javaClass, "mPowerKeyHandled")
                cachedPowerKeyHandledField = field
            }
            field?.setBoolean(phoneWindowManager, handled)
        }
    }

    private fun ensureDoublePressBehaviorEnabled(phoneWindowManager: Any) {
        runCatching {
            var field = cachedDoublePressBehaviorField
            if (field == null) {
                field = HookSupport.findField(phoneWindowManager.javaClass, "mDoublePressOnPowerBehavior")
                cachedDoublePressBehaviorField = field
            }
            if (field != null) {
                val currentVal = field.get(phoneWindowManager) as? Int ?: 0
                if (currentVal == 0) {
                    field.set(phoneWindowManager, 1) // 1 = DOUBLE_PRESS_POWER_CAMERA
                }
            }
        }
    }

    private fun tryLaunchWallet(
        logger: ModuleLogger,
        phoneWindowManager: Any,
        source: String
    ): Boolean {
        val context = HookSupport.getFieldValue(phoneWindowManager, "mContext") as? Context
            ?: return false

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isLocked = keyguardManager?.isKeyguardLocked == true
        val now = SystemClock.uptimeMillis()

        if (powerManager?.isInteractive == false) {
            runCatching {
                val wakeUpMethod = powerManager.javaClass.methods.firstOrNull { it.name == "wakeUp" }
                wakeUpMethod?.invoke(powerManager, now)
            }
        }

        val target = Prefs.getString(Prefs.Keys.POWER_KEY_WALLET_TARGET, Prefs.Keys.WALLET_TARGET_GOOGLE)
        val intent: Intent

        if (target == Prefs.Keys.WALLET_TARGET_COLOROS) {
            val candidates = listOf(
                "com.finshell.wallet",
                "com.coloros.wallet",
                "com.oneplus.card",
                "com.nearme.wallet"
            )
            val walletPackage = candidates.firstOrNull { HookSupport.isPackageInstalled(context, it) }
                ?: "com.finshell.wallet"

            intent = Intent("coloros.wallet.intent.action.OPEN").apply {
                setPackage(walletPackage)
                addCategory(Intent.CATEGORY_DEFAULT)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            }

            if (!HookSupport.resolvesActivity(context, intent)) {
                intent.action = "finshell.wallet.intent.action.OPEN"
                if (!HookSupport.resolvesActivity(context, intent)) {
                    context.packageManager.getLaunchIntentForPackage(walletPackage)?.let { launchIntent ->
                        intent.action = launchIntent.action
                        intent.component = launchIntent.component
                    }
                }
            }

            if (isLocked) {
                intent.addFlags(0x00800000 or 0x00200000) // DISMISS_KEYGUARD | SHOW_WHEN_LOCKED
            }
        } else {
            val walletPackage = "com.google.android.apps.walletnfcrel"
            intent = Intent("com.google.android.apps.wallet.main.QUICKDRAW").apply {
                setPackage(walletPackage)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            }

            if (isLocked) {
                intent.addFlags(0x00800000 or 0x00200000)
            }

            if (!HookSupport.resolvesActivity(context, intent)) {
                intent.action = "com.google.android.apps.wallet.globalactions.START"
                if (!HookSupport.resolvesActivity(context, intent)) {
                    intent.action = Intent.ACTION_VIEW
                    context.packageManager.getLaunchIntentForPackage(walletPackage)?.let { launchIntent ->
                        intent.component = launchIntent.component
                    }
                }
                intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
                if (isLocked) {
                    intent.addFlags(0x00800000 or 0x00200000)
                }
            }
        }

        val options = ActivityOptions.makeBasic()
        if (isLocked) {
            runCatching { HookSupport.invokeNoArgs(options, "setDismissKeyguard") }
        }

        return runCatching {
            context.startActivity(intent, options.toBundle())
            logger.debug { "$source: 已成功拉起钱包刷卡页 ($target)" }
            true
        }.getOrElse { throwable ->
            logger.warnThrottled("${source}_wallet_launch_failed") {
                "$source: 无法启动钱包 ($target)，type=${throwable.safeLogType()}"
            }
            false
        }
    }
}
