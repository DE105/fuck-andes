package io.github.mangi.eta.ui.navigation

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class AppRouteSerializationTest {
    @Test
    fun allRoutesRoundTripWithMiuixNavJsonConfiguration() {
        val routes = listOf<AppRoute>(
            AppRoute.Home,
            AppRoute.Chat,
            AppRoute.Browser,
            AppRoute.Terminal,
            AppRoute.Tools,
            AppRoute.Skills,
            AppRoute.Permissions,
            AppRoute.SystemEnhance,
            AppRoute.Settings,
            AppRoute.AppearanceSettings,
            AppRoute.DataBackup,
            AppRoute.Memory,
            AppRoute.LinuxEnvironment,
            AppRoute.SharedFolders,
            AppRoute.Workspace,
            AppRoute.LinuxFiles("alpine"),
            AppRoute.ModelProviders,
            AppRoute.McpServers,
            AppRoute.McpServerDetail("mcp-server"),
            AppRoute.ModelProviderDetail("provider"),
            AppRoute.ModelProviderNew(NewProviderType.OpenAiCompatible),
            AppRoute.ModelProviderNew(NewProviderType.Anthropic),
        )
        val json = Json { ignoreUnknownKeys = true }

        val encoded = json.encodeToString(routes)

        assertEquals(routes, json.decodeFromString<List<AppRoute>>(encoded))
    }
}
