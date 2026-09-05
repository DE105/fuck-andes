package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.PermissionHealthUiState
import io.github.mangi.eta.ui.model.PermissionStatusUi
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun PermissionHealthCard(
    state: PermissionHealthUiState,
    onOpenPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val issueCount = state.items.count { it.status != PermissionStatusUi.Available }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        onClick = onOpenPermissions,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.ui_permission_health_3048bb),
                    modifier = Modifier.weight(1f),
                    style = MiuixTheme.textStyles.headline1,
                    color = MiuixTheme.colorScheme.onSurfaceContainer,
                )
                Text(
                    text = if (issueCount == 0) {
                        stringResource(R.string.permission_health_ok)
                    } else {
                        pluralStringResource(R.plurals.permission_health_issues, issueCount, issueCount)
                    },
                    style = MiuixTheme.textStyles.body2,
                    color = if (issueCount == 0) {
                        MiuixTheme.colorScheme.onSurfaceVariantActions
                    } else {
                        MiuixTheme.colorScheme.primary
                    },
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            state.items.take(3).forEach { item ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PermissionStatusIcon(item.status)
                    Text(
                        text = item.title,
                        modifier = Modifier.weight(1f),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = statusLabel(item.status),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun PermissionStatusIcon(status: PermissionStatusUi) {
    val tint = when (status) {
        PermissionStatusUi.Available -> MiuixTheme.colorScheme.primary
        PermissionStatusUi.Warning -> MiuixTheme.colorScheme.primary
        PermissionStatusUi.Missing,
        PermissionStatusUi.Disabled -> MiuixTheme.colorScheme.onSurfaceVariantActions
    }
    when (status) {
        PermissionStatusUi.Available -> Icon(
            imageVector = Icons.Rounded.Check,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = tint,
        )
        PermissionStatusUi.Warning -> Icon(
            imageVector = Icons.Rounded.Warning,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = tint,
        )
        PermissionStatusUi.Missing,
        PermissionStatusUi.Disabled -> Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = tint,
        )
    }
}

@Composable
private fun statusLabel(status: PermissionStatusUi): String = stringResource(when (status) {
    PermissionStatusUi.Available -> R.string.permission_status_ok
    PermissionStatusUi.Missing -> R.string.permission_status_missing
    PermissionStatusUi.Warning -> R.string.permission_status_warning
    PermissionStatusUi.Disabled -> R.string.permission_status_disabled
})
