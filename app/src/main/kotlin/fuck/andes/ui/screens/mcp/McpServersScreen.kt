package fuck.andes.ui.screens.mcp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fuck.andes.agent.mcp.McpClientManager
import fuck.andes.agent.mcp.McpConfigStore
import fuck.andes.agent.mcp.McpServerConfig
import fuck.andes.agent.mcp.McpTransport
import fuck.andes.config.Prefs
import fuck.andes.ui.components.MiuixDialogActions
import fuck.andes.ui.components.MiuixScaffoldPage
import fuck.andes.ui.navigation.AppRoute
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun McpServersScreen(
    context: Context,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val servers by McpConfigStore.serversFlow().collectAsState(initial = emptyList())
    var importDraft by remember { mutableStateOf("") }
    var showImportDialog by remember { mutableStateOf(false) }
    val agentPrefs = remember { Prefs.localAgentPreferences() }

    MiuixScaffoldPage(
        title = context.getString(fuck.andes.R.string.ui_mcp_servers_title_bf7aa),
        onBack = onBack,
    ) {
        item(key = "section_toggle") {
            SmallTitle(context.getString(fuck.andes.R.string.ui_tool_a72ef1))
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                MiuixSwitchPref(
                    context = context,
                    prefs = agentPrefs,
                    title = context.getString(fuck.andes.R.string.ui_mcp_enable_tools_501dc),
                    summary = context.getString(fuck.andes.R.string.ui_mcp_enable_tools_summary_01031),
                    key = Prefs.Keys.AGENT_MCP_TOOLS,
                )
            }
        }

        item(key = "section_actions") {
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(
                        text = context.getString(fuck.andes.R.string.ui_mcp_add_server_c5e63),
                        onClick = { onNavigate(AppRoute.McpServerEdit(null)) },
                    )
                    TextButton(
                        text = context.getString(fuck.andes.R.string.ui_mcp_import_json_8a3b2),
                        onClick = {
                            importDraft = ""
                            showImportDialog = true
                        },
                    )
                    TextButton(
                        text = context.getString(fuck.andes.R.string.ui_mcp_export_json_3f173),
                        onClick = {
                            val json = McpConfigStore.exportServersJson(servers)
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText("mcpServers", json),
                            )
                            Toast.makeText(
                                context,
                                context.getString(fuck.andes.R.string.ui_mcp_export_copied_6e413),
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                    )
                }
            }
        }

        item(key = "section_servers") {
            SmallTitle(context.getString(fuck.andes.R.string.ui_mcp_servers_title_bf7aa))
            if (servers.isEmpty()) {
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    BasicComponent(
                        title = context.getString(fuck.andes.R.string.ui_mcp_no_servers_1826e),
                    )
                }
            } else {
                servers.forEach { config ->
                    item(key = "server_${config.id}") {
                        Card(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 12.dp),
                        ) {
                            BasicComponent(
                                title = config.name,
                                summary = buildString {
                                    append(config.transport.label(context))
                                    append(" · ")
                                    append(
                                        context.getString(
                                            if (config.enabled) {
                                                fuck.andes.R.string.ui_mcp_enabled_63197
                                            } else {
                                                fuck.andes.R.string.ui_mcp_disabled_d8fea
                                            },
                                        ),
                                    )
                                },
                                endActions = {
                                    TextButton(
                                        text = context.getString(fuck.andes.R.string.action_edit),
                                        onClick = { onNavigate(AppRoute.McpServerEdit(config.id)) },
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    WindowDialog(
        show = showImportDialog,
        title = context.getString(fuck.andes.R.string.ui_mcp_import_title_2d706),
        summary = context.getString(fuck.andes.R.string.ui_mcp_import_hint_5286e),
        onDismissRequest = { showImportDialog = false },
    ) {
        TextField(
            value = importDraft,
            onValueChange = { importDraft = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        MiuixDialogActions(
            confirmText = context.getString(fuck.andes.R.string.ui_mcp_import_json_8a3b2),
            cancelText = context.getString(fuck.andes.R.string.action_cancel),
            confirmEnabled = importDraft.isNotBlank(),
            onCancel = { showImportDialog = false },
            onConfirm = {
                showImportDialog = false
                coroutineScope.launch {
                    val result = runCatching {
                        val imported = McpConfigStore.parseImportJson(importDraft)
                        val merged = McpConfigStore.servers() + imported
                        McpConfigStore.save(merged.distinctBy(McpServerConfig::id))
                        McpClientManager.closeAll()
                        imported.size
                    }
                    val message = result.fold(
                        onSuccess = { count ->
                            context.getString(
                                fuck.andes.R.string.ui_mcp_import_ok_d89e2,
                                count,
                            )
                        },
                        onFailure = { throwable ->
                            context.getString(
                                fuck.andes.R.string.ui_mcp_import_failed_4d49e,
                                throwable.message ?: throwable.javaClass.simpleName,
                            )
                        },
                    )
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
}

@Composable
private fun McpTransport.label(context: Context): String = when (this) {
    McpTransport.HTTP -> context.getString(fuck.andes.R.string.ui_mcp_transport_http_8d0e6)
    McpTransport.SSE -> context.getString(fuck.andes.R.string.ui_mcp_transport_sse_29496)
    McpTransport.STDIO -> context.getString(fuck.andes.R.string.ui_mcp_transport_stdio_a1915)
}

/** 列表页内的 MCP 开关：读 App 本地配置，切换时使工具目录缓存失效。 */
@Composable
private fun MiuixSwitchPref(
    context: Context,
    prefs: android.content.SharedPreferences?,
    title: String,
    summary: String,
    key: String,
) {
    val coroutineScope = rememberCoroutineScope()
    val default = Prefs.Keys.BOOLEAN_DEFAULTS[key] ?: true
    var checked by remember(prefs, key) {
        mutableStateOf(prefs?.getBoolean(key, default) ?: default)
    }
    top.yukonga.miuix.kmp.preference.SwitchPreference(
        title = title,
        summary = summary,
        checked = checked,
        enabled = prefs != null,
        onCheckedChange = { value ->
            val target = prefs ?: return@SwitchPreference
            if (runCatching { target.edit().putBoolean(key, value).commit() }.getOrDefault(false)) {
                checked = value
                coroutineScope.launch { McpClientManager.closeAll() }
            } else {
                Toast.makeText(
                    context,
                    context.getString(fuck.andes.R.string.settings_write_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        },
    )
}
