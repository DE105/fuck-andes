package fuck.andes.ui.screens.mcp

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
import fuck.andes.R
import fuck.andes.agent.mcp.McpClientManager
import fuck.andes.agent.mcp.McpConfigStore
import fuck.andes.agent.mcp.McpServerConfig
import fuck.andes.agent.mcp.McpTransport
import fuck.andes.ui.components.MiuixDialogActions
import fuck.andes.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun McpServerEditScreen(
    context: Context,
    serverId: String?,
    onBack: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val servers by McpConfigStore.serversFlow().collectAsState(initial = emptyList())
    val existing = servers.firstOrNull { it.id == serverId }

    var name by remember(existing) { mutableStateOf(existing?.name ?: "") }
    var transport by remember(existing) { mutableStateOf(existing?.transport ?: McpTransport.HTTP) }
    var url by remember(existing) { mutableStateOf(existing?.url ?: "") }
    var command by remember(existing) { mutableStateOf(existing?.command ?: "") }
    var argsText by remember(existing) {
        mutableStateOf(existing?.args?.joinToString("\n") ?: "")
    }
    var bearerToken by remember(existing) { mutableStateOf(existing?.bearerToken ?: "") }
    var headersText by remember(existing) {
        mutableStateOf(
            existing?.headers?.entries?.joinToString("\n") { "${it.key}: ${it.value}" } ?: "",
        )
    }
    var envText by remember(existing) {
        mutableStateOf(
            existing?.env?.entries?.joinToString("\n") { "${it.key}: ${it.value}" } ?: "",
        )
    }
    var timeout by remember(existing) {
        mutableStateOf((existing?.timeoutSeconds ?: 60L).toString())
    }
    var enabled by remember(existing) { mutableStateOf(existing?.enabled ?: true) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    fun parseKeyValues(text: String): Map<String, String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split(":", limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank()) {
                    parts[0].trim() to parts[1].trim()
                } else {
                    null
                }
            }
            .toMap()

    fun argsList(): List<String> =
        argsText.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()

    fun save() {
        if (name.isBlank()) {
            Toast.makeText(
                context,
                context.getString(R.string.ui_mcp_name_required_f2d0b),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val endpointMissing = when (transport) {
            McpTransport.HTTP, McpTransport.SSE -> url.isBlank()
            McpTransport.STDIO -> command.isBlank()
        }
        if (endpointMissing) {
            Toast.makeText(
                context,
                context.getString(R.string.ui_mcp_endpoint_required_e0b8a),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val config = McpServerConfig(
            id = existing?.id ?: McpConfigStore.newId(name.trim()),
            name = name.trim(),
            transport = transport,
            url = url.trim(),
            command = command.trim(),
            args = argsList(),
            env = parseKeyValues(envText),
            headers = parseKeyValues(headersText),
            bearerToken = bearerToken.trim(),
            enabled = enabled,
            timeoutSeconds = timeout.toLongOrNull()?.coerceIn(5L, 600L) ?: 60L,
        )
        coroutineScope.launch {
            McpConfigStore.upsert(config)
            McpClientManager.closeAll()
            onBack()
        }
    }

    fun delete() {
        val id = existing?.id ?: return
        coroutineScope.launch {
            McpConfigStore.remove(id)
            McpClientManager.closeAll()
            onBack()
        }
    }

    MiuixScaffoldPage(
        title = existing?.name ?: context.getString(R.string.ui_mcp_add_server_c5e63),
        onBack = onBack,
    ) {
        item(key = "identity") {
            SmallTitle(context.getString(R.string.ui_mcp_server_name_9245e))
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = context.getString(R.string.ui_mcp_server_name_9245e),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
                SwitchPreference(
                    title = context.getString(
                        if (enabled) R.string.ui_mcp_enabled_63197 else R.string.ui_mcp_disabled_d8fea,
                    ),
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                )
            }
        }

        item(key = "transport") {
            SmallTitle(context.getString(R.string.ui_mcp_server_transport_21d6a))
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                McpTransport.entries.forEach { option ->
                    val selected = option == transport
                    BasicComponent(
                        title = option.label(context),
                        endActions = {
                            TextButton(
                                text = context.getString(
                                    if (selected) R.string.status_authorized else R.string.ui_mcp_transport_select_2b70d,
                                ),
                                onClick = {
                                    if (!selected) {
                                        transport = option
                                        if (option == McpTransport.STDIO) url = ""
                                        if (option != McpTransport.STDIO) command = ""
                                    }
                                },
                            )
                        },
                    )
                }
                if (transport == McpTransport.HTTP || transport == McpTransport.SSE) {
                    TextField(
                        value = url,
                        onValueChange = { url = it },
                        label = context.getString(R.string.ui_mcp_server_url_8f959),
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    )
                } else {
                    TextField(
                        value = command,
                        onValueChange = { command = it },
                        label = context.getString(R.string.ui_mcp_server_command_e38ad),
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    )
                    TextField(
                        value = argsText,
                        onValueChange = { argsText = it },
                        label = context.getString(R.string.ui_mcp_server_args_b9579),
                        useLabelAsPlaceholder = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    )
                }
            }
        }

        item(key = "auth") {
            SmallTitle(context.getString(R.string.ui_mcp_server_headers_21011))
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                if (transport == McpTransport.HTTP || transport == McpTransport.SSE) {
                    TextField(
                        value = bearerToken,
                        onValueChange = { bearerToken = it },
                        label = context.getString(R.string.ui_mcp_server_bearer_token_d17c1),
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    )
                }
                if (transport == McpTransport.HTTP || transport == McpTransport.SSE) {
                    TextField(
                        value = headersText,
                        onValueChange = { headersText = it },
                        label = context.getString(R.string.ui_mcp_server_headers_21011),
                        useLabelAsPlaceholder = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    )
                }
                if (transport == McpTransport.STDIO) {
                    TextField(
                        value = envText,
                        onValueChange = { envText = it },
                        label = context.getString(R.string.ui_mcp_server_env_f0c8a),
                        useLabelAsPlaceholder = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    )
                }
                TextField(
                    value = timeout,
                    onValueChange = { timeout = it },
                    label = context.getString(R.string.ui_mcp_server_timeout_22975),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
            }
        }

        item(key = "actions") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = context.getString(R.string.ui_mcp_save_76816),
                    onClick = ::save,
                )
                if (existing != null) {
                    TextButton(
                        text = context.getString(R.string.ui_mcp_delete_ae900),
                        onClick = { showDeleteDialog = true },
                    )
                }
            }
        }
    }

    WindowDialog(
        show = showDeleteDialog,
        title = context.getString(R.string.ui_mcp_delete_confirm_a1da3),
        summary = existing?.name.orEmpty(),
        onDismissRequest = { showDeleteDialog = false },
    ) {
        MiuixDialogActions(
            confirmText = context.getString(R.string.ui_mcp_delete_ae900),
            cancelText = context.getString(R.string.action_cancel),
            onCancel = { showDeleteDialog = false },
            onConfirm = ::delete,
        )
    }
}

@Composable
private fun McpTransport.label(context: Context): String = when (this) {
    McpTransport.HTTP -> context.getString(R.string.ui_mcp_transport_http_8d0e6)
    McpTransport.SSE -> context.getString(R.string.ui_mcp_transport_sse_29496)
    McpTransport.STDIO -> context.getString(R.string.ui_mcp_transport_stdio_a1915)
}
