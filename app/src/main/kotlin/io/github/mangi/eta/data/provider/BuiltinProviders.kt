package io.github.mangi.eta.data.provider

import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ProviderSourceTypes

internal object BuiltinProviders {
    const val DEFAULT_SYSTEM_PROMPT =
        "你是 Eta，运行在 Android 设备上的 AI 助手。你可以回答问题、与用户交流，也可以通过当前可用的工具了解设备情况并执行操作。" +
            "回答使用用户的语言，简洁、直接、自然。"

    const val OPENAI_ID = "builtin-openai"
    const val ANTHROPIC_ID = "builtin-anthropic"
    const val BAILIAN_ID = "builtin-dashscope"
    const val DEEPSEEK_ID = "builtin-deepseek"
    const val KIMI_ID = "builtin-kimi"
    const val MIMO_ID = "builtin-mimo"
    const val MINIMAX_ID = "builtin-minimax"
    const val STEPFUN_ID = "builtin-stepfun"
    const val SILICONFLOW_ID = "builtin-siliconflow"
    const val OPENROUTER_ID = "builtin-openrouter"

    val PROVIDERS: List<ProviderSetting> = listOf(
        OpenAiCompatibleProviderSetting(
            id = OPENAI_ID,
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            sourceType = ProviderSourceTypes.OPENAI,
            isBuiltIn = true,
            sortOrder = 0,
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
            endpointMode = OpenAiEndpointMode.RESPONSES,
        ),
        AnthropicProviderSetting(
            id = ANTHROPIC_ID,
            name = "Anthropic",
            baseUrl = "https://api.anthropic.com",
            sourceType = ProviderSourceTypes.ANTHROPIC,
            isBuiltIn = true,
            sortOrder = 1,
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
        ),
        OpenAiCompatibleProviderSetting(
            id = BAILIAN_ID,
            name = "阿里百炼",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            sourceType = ProviderSourceTypes.BAILIAN,
            isBuiltIn = true,
            sortOrder = 2,
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
        ),
        OpenAiCompatibleProviderSetting(
            id = DEEPSEEK_ID,
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com",
            sourceType = ProviderSourceTypes.DEEPSEEK,
            isBuiltIn = true,
            sortOrder = 3,
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
        ),
        OpenAiCompatibleProviderSetting(
            id = KIMI_ID,
            name = "Kimi",
            baseUrl = "https://api.moonshot.cn/v1",
            sourceType = ProviderSourceTypes.MOONSHOT,
            isBuiltIn = true,
            sortOrder = 4,
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
        ),
        OpenAiCompatibleProviderSetting(
            id = MIMO_ID,
            name = "MiMo",
            baseUrl = "https://api.xiaomimimo.com/v1",
            sourceType = ProviderSourceTypes.MIMO,
            isBuiltIn = true,
            sortOrder = 5,
            systemPrompt = DEFAULT_SYSTEM_PROMPT
        ),
        OpenAiCompatibleProviderSetting(
            id = MINIMAX_ID,
            name = "MiniMax",
            baseUrl = "https://api.minimaxi.com/v1",
            sourceType = ProviderSourceTypes.MINIMAX,
            isBuiltIn = true,
            sortOrder = 6,
            systemPrompt = DEFAULT_SYSTEM_PROMPT
        ),
        OpenAiCompatibleProviderSetting(
            id = STEPFUN_ID,
            name = "StepFun",
            baseUrl = "https://api.stepfun.com/v1",
            sourceType = ProviderSourceTypes.STEPFUN,
            isBuiltIn = true,
            sortOrder = 7,
            systemPrompt = DEFAULT_SYSTEM_PROMPT
        ),
        OpenAiCompatibleProviderSetting(
            id = SILICONFLOW_ID,
            name = "硅基流动",
            baseUrl = "https://api.siliconflow.cn/v1",
            sourceType = ProviderSourceTypes.SILICONFLOW,
            isBuiltIn = true,
            sortOrder = 8,
            systemPrompt = DEFAULT_SYSTEM_PROMPT
        ),
        OpenAiCompatibleProviderSetting(
            id = OPENROUTER_ID,
            name = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            sourceType = ProviderSourceTypes.OPENROUTER,
            isBuiltIn = true,
            sortOrder = 9,
            systemPrompt = DEFAULT_SYSTEM_PROMPT
        )
    )

    fun providerById(id: String): ProviderSetting? =
        PROVIDERS.firstOrNull { it.id == id }
}
