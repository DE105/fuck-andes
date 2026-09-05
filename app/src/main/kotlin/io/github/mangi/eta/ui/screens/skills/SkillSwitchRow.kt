package io.github.mangi.eta.ui.screens.skills

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DesignServices
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.ItemDescriptionDialog
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.model.SkillItemUi
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SkillSwitchRow(
    skill: SkillItemUi,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var showDescription by remember(skill.id) { mutableStateOf(false) }
    val description = skill.description.ifBlank { stringResource(R.string.skills_no_description) }
    BasicComponent(
        startAction = { PreferenceIcon(iconForSkill(skill.id), enabled = enabled) },
        endActions = {
            OverlayIconDropdownMenu(
                modifier = Modifier.align(Alignment.CenterVertically),
                entry = DropdownEntry(
                    items = listOfNotNull(
                        DropdownItem(
                            text = stringResource(R.string.ui_view_description),
                            onClick = { showDescription = true },
                        ),
                        onDelete?.let { delete ->
                            DropdownItem(
                                text = stringResource(R.string.ui_delete_3755f5),
                                enabled = enabled,
                                onClick = { if (enabled) delete() },
                            )
                        },
                    ),
                ),
            ) {
                Icon(
                    imageVector = Icons.Rounded.MoreHoriz,
                    contentDescription = stringResource(R.string.skills_more_named, skill.name),
                    modifier = Modifier.size(20.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Switch(
                checked = skill.enabled,
                onCheckedChange = onToggle,
                enabled = enabled,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        },
    ) {
        Text(
            text = skill.name,
            style = MiuixTheme.textStyles.headline1,
            fontWeight = FontWeight.Medium,
            color = if (enabled) MiuixTheme.colorScheme.onBackground else MiuixTheme.colorScheme.disabledOnSurface,
        )
        Text(
            text = description,
            style = MiuixTheme.textStyles.body2,
            color = if (enabled) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.disabledOnSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
    if (showDescription) {
        ItemDescriptionDialog(
            title = skill.name,
            description = description,
            onDismiss = { showDescription = false },
        )
    }
}

internal fun iconForSkill(skillId: String): ImageVector = when (skillId) {
    "self-improving-agent" -> Icons.Rounded.Refresh
    "skill-creator" -> Icons.Rounded.DesignServices
    else -> Icons.Rounded.Extension
}
