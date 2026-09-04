package io.github.mangi.eta.ui.screens.enhance

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.app.description
import io.github.mangi.eta.ui.app.rememberDeviceCapabilities
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.PrefDivider
import io.github.mangi.eta.ui.model.AgentSystemEnhanceAction
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference

@Composable
fun SystemEnhanceScreen(
    onAction: (AgentSystemEnhanceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val capabilities = rememberDeviceCapabilities()
    MiuixScaffoldPage(
        title = stringResource(R.string.capability_enhancements),
        onBack = { onAction(AgentSystemEnhanceAction.NavigateBack) },
        modifier = modifier,
    ) {
        item(key = "access") {
            Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                BasicComponent(title = "Root", summary = capabilities.root.description(context))
                ArrowPreference(
                    title = stringResource(
                        if (capabilities.root.suPresent && !capabilities.root.isGranted) {
                            R.string.capability_root_request
                        } else {
                            R.string.capability_root_refresh
                        },
                    ),
                    enabled = !capabilities.root.isChecking,
                    onClick = {
                        onAction(
                            if (capabilities.root.suPresent && !capabilities.root.isGranted) {
                                AgentSystemEnhanceAction.RequestRoot
                            } else {
                                AgentSystemEnhanceAction.RefreshRoot
                            },
                        )
                    },
                )
                PrefDivider()
                BasicComponent(
                    title = stringResource(R.string.capability_xposed_service),
                    summary = stringResource(
                        if (capabilities.xposedConnected) R.string.capability_xposed_connected
                        else R.string.capability_xposed_disconnected,
                    ),
                )
                BasicComponent(
                    title = stringResource(R.string.capability_xposed_help),
                    summary = stringResource(R.string.capability_xposed_help_summary),
                )
            }
        }
        item(key = "root-title") { SmallTitle(stringResource(R.string.capability_root_features)) }
        item(key = "root-features") {
            Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                BasicComponent(title = stringResource(R.string.capability_root_device), summary = stringResource(R.string.capability_root_device_summary))
                PrefDivider()
                BasicComponent(title = stringResource(R.string.capability_root_data), summary = stringResource(R.string.capability_root_data_summary))
                PrefDivider()
                BasicComponent(title = stringResource(R.string.capability_root_linux), summary = stringResource(R.string.capability_root_linux_summary))
            }
        }
        item(key = "hook-title") { SmallTitle(stringResource(R.string.capability_system_features)) }
        item(key = "hook-features") {
            Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                BasicComponent(title = stringResource(R.string.capability_hook_assistants), summary = stringResource(R.string.capability_hook_assistants_summary))
                PrefDivider()
                BasicComponent(title = stringResource(R.string.capability_hook_google), summary = stringResource(R.string.capability_hook_google_summary))
                PrefDivider()
                BasicComponent(title = stringResource(R.string.capability_hook_accessibility), summary = stringResource(R.string.capability_hook_accessibility_summary))
            }
        }
    }
}
