package io.github.mangi.eta.ui.screens.enhance

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.app.description
import io.github.mangi.eta.ui.app.rememberDeviceCapabilities
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.model.AgentSystemEnhanceAction
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton

@Composable
fun SystemEnhanceScreen(
    onAction: (AgentSystemEnhanceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val capabilities = rememberDeviceCapabilities()
    val canRequestRoot = capabilities.root.suPresent && !capabilities.root.isGranted
    MiuixScaffoldPage(
        title = stringResource(R.string.capability_enhancements),
        onBack = { onAction(AgentSystemEnhanceAction.NavigateBack) },
        modifier = modifier,
    ) {
        item(key = "access") {
            Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                BasicComponent(
                    title = "Root",
                    summary = capabilities.root.description(context),
                    startAction = { PreferenceIcon(Icons.Rounded.Key) },
                    endActions = {
                        TextButton(
                            text = stringResource(
                                if (canRequestRoot) R.string.capability_root_request
                                else R.string.capability_root_refresh,
                            ),
                            enabled = !capabilities.root.isChecking,
                            onClick = {
                                onAction(
                                    if (canRequestRoot) AgentSystemEnhanceAction.RequestRoot
                                    else AgentSystemEnhanceAction.RefreshRoot,
                                )
                            },
                        )
                    },
                )
                BasicComponent(
                    title = stringResource(R.string.capability_xposed_service),
                    summary = stringResource(
                        if (capabilities.xposedConnected) R.string.capability_xposed_connected
                        else R.string.capability_xposed_disconnected,
                    ),
                    startAction = { PreferenceIcon(Icons.Rounded.AccountTree) },
                )
            }
        }
        item(key = "framework-help") {
            Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                BasicComponent(
                    title = stringResource(R.string.capability_xposed_help),
                    summary = stringResource(R.string.capability_xposed_help_summary),
                    startAction = { PreferenceIcon(Icons.Rounded.Info) },
                )
            }
        }
        item(key = "root-title") { SmallTitle(stringResource(R.string.capability_root_features)) }
        item(key = "root-features") {
            Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                BasicComponent(
                    title = stringResource(R.string.capability_root_device),
                    summary = stringResource(R.string.capability_root_device_summary),
                    startAction = { PreferenceIcon(Icons.Rounded.AdminPanelSettings) },
                )
                BasicComponent(
                    title = stringResource(R.string.capability_root_data),
                    summary = stringResource(R.string.capability_root_data_summary),
                    startAction = { PreferenceIcon(Icons.Rounded.Lock) },
                )
                BasicComponent(
                    title = stringResource(R.string.capability_root_linux),
                    summary = stringResource(R.string.capability_root_linux_summary),
                    startAction = { PreferenceIcon(Icons.Rounded.Terminal) },
                )
            }
        }
        item(key = "hook-title") { SmallTitle(stringResource(R.string.capability_system_features)) }
        item(key = "hook-features") {
            Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                BasicComponent(
                    title = stringResource(R.string.capability_hook_assistants),
                    summary = stringResource(R.string.capability_hook_assistants_summary),
                    startAction = { PreferenceIcon(Icons.Rounded.AutoAwesome) },
                )
                BasicComponent(
                    title = stringResource(R.string.capability_hook_google),
                    summary = stringResource(R.string.capability_hook_google_summary),
                    startAction = { PreferenceIcon(Icons.AutoMirrored.Rounded.ManageSearch) },
                )
                BasicComponent(
                    title = stringResource(R.string.capability_hook_accessibility),
                    summary = stringResource(R.string.capability_hook_accessibility_summary),
                    startAction = { PreferenceIcon(Icons.Rounded.VerifiedUser) },
                )
            }
        }
    }
}
