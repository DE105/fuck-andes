package fuck.andes.config

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

/**
 * 模块配置中枢。
 *
 * - Hook 进程（system_server / SystemUI / Google / 系统助手等）在模块加载时调用
 *   [attachRemote]，缓存框架提供的只读 [SharedPreferences]；之后所有拦截回调用 [isEnabled]
 *   读取当前进程持有的 remote preferences。
 * - Eta Runtime 自己消费的开关保存在 App 私有配置中，不依赖 Xposed Service。
 * - Hook 消费的开关通过 [remotePreferencesForUi] 写入 RemotePreferences；
 *   XposedService 未就绪时不提供本地假 fallback。
 *
 * 基于 libxposed API 102 的 [io.github.libxposed.api.XposedInterface.getRemotePreferences]
 * 与 service 102 的 [XposedService.getRemotePreferences]，两端共用同一 group。
 */
internal object Prefs {

    /** 远程配置组名，UI 写入与 Hook 读取必须一致。 */
    const val GROUP = "fuck_andes_prefs"

    private const val LOCAL_AGENT_GROUP = "eta_agent_preferences"

    /** 所有功能开关 key。默认值按功能风险独立定义。 */
    object Keys {
        const val POWER_KEY_ASSISTANT_TARGET = "power_key_assistant_target"
        // 兼容旧版布尔协议；新 UI 不再写入，缺少三态配置时 true 仍表示 Gemini。
        const val POWER_KEY_TAKEOVER = "power_key_takeover"
        const val POWER_KEY_DOUBLE_PRESS_WALLET = "power_key_double_press_wallet"
        const val POWER_KEY_WALLET_TARGET = "power_key_wallet_target"
        const val WALLET_TARGET_GOOGLE = "google_wallet"
        const val WALLET_TARGET_COLOROS = "coloros_wallet"

        const val ASSISTANT_AUTO_CONFIG = "assistant_auto_config"
        const val HOTWORD_SELF_HEAL = "hotword_self_heal"
        const val GESTURE_BAR_CIRCLE_TO_SEARCH = "gesture_bar_circle_to_search"
        const val DOUBLE_FINGER_CIRCLE_TO_SEARCH = "double_finger_circle_to_search"
        const val LOCKSCREEN_VOICE_COMMAND = "lockscreen_voice_command"
        const val SCREEN_ON_VOICE_COMMAND = "screen_on_voice_command"
        const val AGENT_CUSTOM_MODEL = "agent_custom_model"
        const val AGENT_REQUIRE_PREFIX = "agent_require_prefix"
        const val AGENT_TERMINAL_TOOLS = "agent_terminal_tools"
        const val AGENT_BROWSER_TOOLS = "agent_browser_tools"
        const val AGENT_DEVICE_DIRECT_TOOLS = "agent_device_direct_tools"
        const val AGENT_DEVICE_SENSITIVE_READ_TOOLS = "agent_device_sensitive_read_tools"
        const val AGENT_DEVICE_SENSITIVE_ACTION_TOOLS = "agent_device_sensitive_action_tools"
        const val AGENT_THINKING_ENABLED = "agent_thinking_enabled"
        const val AGENT_RUNTIME_CONFIG_JSON = "agent_runtime_config_json"

        /** 全部布尔开关及其默认值。 */
        val BOOLEAN_DEFAULTS: Map<String, Boolean> = mapOf(
            POWER_KEY_TAKEOVER to false,
            POWER_KEY_DOUBLE_PRESS_WALLET to false,
            ASSISTANT_AUTO_CONFIG to false,
            HOTWORD_SELF_HEAL to false,
            GESTURE_BAR_CIRCLE_TO_SEARCH to true,
            DOUBLE_FINGER_CIRCLE_TO_SEARCH to false,
            LOCKSCREEN_VOICE_COMMAND to false,
            SCREEN_ON_VOICE_COMMAND to false,
            AGENT_CUSTOM_MODEL to true,
            AGENT_REQUIRE_PREFIX to false,
            AGENT_TERMINAL_TOOLS to true,
            AGENT_BROWSER_TOOLS to true,
            AGENT_DEVICE_DIRECT_TOOLS to true,
            AGENT_DEVICE_SENSITIVE_READ_TOOLS to true,
            AGENT_DEVICE_SENSITIVE_ACTION_TOOLS to true,
            AGENT_THINKING_ENABLED to true
        )

        /** 由 Eta Runtime 最终裁决、不要求 Xposed 框架在线的开关。 */
        val LOCAL_AGENT_KEYS: Set<String> = setOf(
            AGENT_TERMINAL_TOOLS,
            AGENT_BROWSER_TOOLS,
            AGENT_DEVICE_DIRECT_TOOLS,
            AGENT_DEVICE_SENSITIVE_READ_TOOLS,
            AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
            AGENT_THINKING_ENABLED,
        )
    }

    /** Hook 进程缓存的只读 remote preferences，由 ModuleMain 在 onModuleLoaded 注入。 */
    @Volatile
    private var remote: SharedPreferences? = null

    @Volatile
    private var localAgent: SharedPreferences? = null

    /** App 进程调用：初始化不依赖 Xposed Service 的 Agent 配置。 */
    fun initLocal(context: Context) {
        if (localAgent == null) {
            synchronized(this) {
                if (localAgent == null) {
                    localAgent = context.applicationContext.getSharedPreferences(
                        LOCAL_AGENT_GROUP,
                        Context.MODE_PRIVATE,
                    )
                }
            }
        }
    }

    /** Hook 进程调用：缓存框架提供的只读 SharedPreferences。 */
    fun attachRemote(prefs: SharedPreferences?) {
        remote = prefs
    }

    /** Hook 进程监听框架下发的配置变化；listener 必须由调用方在进程生命周期内强引用。 */
    fun registerRemoteListener(listener: SharedPreferences.OnSharedPreferenceChangeListener): Boolean {
        val preferences = remote ?: return false
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return true
    }

    /**
     * 读取布尔开关。remote 不可用（框架未注入或调用失败）时回退各功能自己的默认值；
     * 默认值与设置页展示保持一致。
     */
    fun isEnabled(key: String): Boolean {
        val default = Keys.BOOLEAN_DEFAULTS[key] ?: true
        val preferences = if (key in Keys.LOCAL_AGENT_KEYS) localAgent ?: remote else remote
        return preferences?.getBoolean(key, default) ?: default
    }

    fun getString(key: String): String {
        return remote?.getString(key, "") ?: ""
    }

    fun getString(key: String, defaultValue: String): String {
        val preferences = if (key in Keys.LOCAL_AGENT_KEYS) localAgent ?: remote else remote
        return preferences?.getString(key, defaultValue) ?: defaultValue
    }

    fun powerAssistantTarget(): PowerAssistantTarget = powerAssistantTarget(remote)

    fun powerAssistantTarget(preferences: SharedPreferences?): PowerAssistantTarget {
        val persistedValue = runCatching {
            preferences?.getString(Keys.POWER_KEY_ASSISTANT_TARGET, null)
        }.getOrNull()
        val legacyDefault = Keys.BOOLEAN_DEFAULTS.getValue(Keys.POWER_KEY_TAKEOVER)
        val legacyTakeover = runCatching {
            preferences?.getBoolean(Keys.POWER_KEY_TAKEOVER, legacyDefault)
        }.getOrNull() ?: legacyDefault
        return PowerAssistantTarget.resolve(persistedValue, legacyTakeover)
    }

    /**
     * UI 进程获取可写的 RemotePreferences。
     *
     * [XposedService.getRemotePreferences] 的 commit 会同步等待 binder 提交到 LSPosed
     * 数据库，失败返回 false；service 未就绪时返回 null，让 UI 保持不可写。
     */
    fun remotePreferencesForUi(service: XposedService?): SharedPreferences? =
        runCatching { service?.getRemotePreferences(GROUP) }.getOrNull()

    /** Eta 设置页与 Runtime 使用的本地 Agent 配置，不依赖 LSPosed。 */
    fun localAgentPreferences(): SharedPreferences? = localAgent

    /**
     * 首次升级优先把已有 RemotePreferences 值迁入本地；之后本地值是事实源，并在框架
     * 可用时回写远端，让仍在目标进程中组装请求的 Hook 入口拿到一致的初始配置。
     */
    fun reconcileAgentPreferences(service: XposedService?) {
        val local = localAgent ?: return
        val remotePreferences = remotePreferencesForUi(service) ?: return
        val localEditor = local.edit()
        val remoteEditor = remotePreferences.edit()
        var updateLocal = false
        var updateRemote = false

        Keys.LOCAL_AGENT_KEYS.forEach { key ->
            val default = Keys.BOOLEAN_DEFAULTS.getValue(key)
            when {
                local.contains(key) -> {
                    remoteEditor.putBoolean(key, local.getBoolean(key, default))
                    updateRemote = true
                }
                remotePreferences.contains(key) -> {
                    localEditor.putBoolean(key, remotePreferences.getBoolean(key, default))
                    updateLocal = true
                }
            }
        }
        if (updateLocal) localEditor.commit()
        if (updateRemote) runCatching { remoteEditor.commit() }
    }
}
