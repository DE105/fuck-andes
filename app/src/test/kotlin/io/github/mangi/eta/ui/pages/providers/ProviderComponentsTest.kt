package io.github.mangi.eta.ui.pages.providers

import androidx.compose.runtime.saveable.SaverScope
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.provider.BuiltinProviders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderComponentsTest {
    @Test
    fun providerDraftSaverPreservesUnsavedConfiguration() {
        val draft = ProviderConfigDraft(
            name = "临时提供商",
            baseUrl = "https://api.example.com/v1",
            apiKey = "temporary-key",
            systemPrompt = "临时提示词",
            isEnabled = false,
            endpointMode = "responses",
            hostedWebSearchEnabled = true,
            anthropicVersion = "2023-06-01",
        )

        val saved = with(ProviderConfigDraftSaver) {
            SaverScope { true }.save(draft)
        }

        assertEquals(draft, ProviderConfigDraftSaver.restore(requireNotNull(saved)))
    }

    @Test
    fun validatesOptionalPositiveContextWindowOverride() {
        assertEquals(null, contextWindowInputError(""))
        assertEquals(null, contextWindowInputError(" 256000 "))
        assertEquals("Context window must be a positive integer", contextWindowInputError("0"))
        assertEquals("Context window must be a positive integer", contextWindowInputError("999999999999"))
    }

    @Test
    fun knownSourcesMapToDistinctBrandLogos() {
        val expected = mapOf(
            ProviderSourceTypes.OPENAI to R.drawable.provider_logo_openai,
            ProviderSourceTypes.ANTHROPIC to R.drawable.provider_logo_anthropic,
            ProviderSourceTypes.BAILIAN to R.drawable.provider_logo_bailian,
            ProviderSourceTypes.DEEPSEEK to R.drawable.provider_logo_deepseek,
            ProviderSourceTypes.MOONSHOT to R.drawable.provider_logo_kimi,
            ProviderSourceTypes.MIMO to R.drawable.provider_logo_mimo,
            ProviderSourceTypes.MINIMAX to R.drawable.provider_logo_minimax,
            ProviderSourceTypes.STEPFUN to R.drawable.provider_logo_stepfun,
            ProviderSourceTypes.SILICONFLOW to R.drawable.provider_logo_siliconflow,
            ProviderSourceTypes.OPENROUTER to R.drawable.provider_logo_openrouter,
        )

        expected.forEach { (sourceType, logo) ->
            assertEquals(logo, providerBrandLogoRes(sourceType))
        }
        assertEquals(expected.size, expected.values.toSet().size)
    }

    @Test
    fun everyBuiltInProviderHasABrandLogo() {
        val logos = BuiltinProviders.PROVIDERS.map(::providerBrandLogoRes)

        assertEquals(BuiltinProviders.PROVIDERS.size, logos.filterNotNull().size)
        assertEquals(BuiltinProviders.PROVIDERS.size, logos.filterNotNull().toSet().size)
    }

    @Test
    fun customProviderUsesRecognizedBaseUrlAndUnknownSourceFallsBack() {
        val recognized = CustomProviderSetting(
            id = "custom-deepseek",
            name = "DeepSeek 副本",
            baseUrl = "https://api.deepseek.com/v1",
        )
        val unknown = CustomProviderSetting(
            id = "custom-unknown",
            name = "自定义",
            baseUrl = "https://api.example.com/v1",
        )

        assertEquals(R.drawable.provider_logo_deepseek, providerBrandLogoRes(recognized))
        assertNull(providerBrandLogoRes(unknown))
    }

    @Test
    fun modelSearchSupportsCaseInsensitiveFuzzyTokensAcrossNameAndId() {
        val models = listOf(
            model(id = "1", modelId = "openai/gpt-5", displayName = "GPT 5", sortOrder = 2),
            model(id = "2", modelId = "deepseek/deepseek-v3", displayName = "DeepSeek V3", sortOrder = 1),
            model(
                id = "3",
                modelId = "liquid/lfm-2.5-2.6b:free",
                displayName = "LiquidAI: LFM2.5-2.6B (free)",
                sortOrder = 3,
            ),
        )

        assertEquals(listOf("1"), filterProviderModels(models, "  gPt  ").map { it.id })
        assertEquals(listOf("2"), filterProviderModels(models, "  DEEPSEEK-V3  ").map { it.id })
        assertEquals(listOf("2"), filterProviderModels(models, "deep v3").map { it.id })
        assertEquals(listOf("2"), filterProviderModels(models, "dsv3").map { it.id })
        assertEquals(listOf("2"), filterProviderModels(models, "deep___v3").map { it.id })
        assertEquals(listOf("3"), filterProviderModels(models, "liquid 2.6b").map { it.id })
        assertEquals(emptyList<Model>(), filterProviderModels(models, "3vds"))
    }

    @Test
    fun modelSearchKeepsSortOrderForBlankQueryAndReturnsEmptyForNoMatch() {
        val models = listOf(
            model(id = "later", modelId = "model-b", displayName = "Model B", sortOrder = 20),
            model(id = "first", modelId = "model-a", displayName = "Model A", sortOrder = 10),
        )

        assertEquals(listOf("first", "later"), filterProviderModels(models, "   ").map { it.id })
        assertEquals(listOf("first", "later"), filterProviderModels(models, "---").map { it.id })
        assertEquals(emptyList<Model>(), filterProviderModels(models, "missing"))
    }

    private fun model(
        id: String,
        modelId: String,
        displayName: String,
        sortOrder: Int,
    ) = Model(
        id = id,
        modelId = modelId,
        displayName = displayName,
        sortOrder = sortOrder,
    )
}
