package fuck.andes.ui.screens.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import fuck.andes.ui.components.MiuixDialogActions
import fuck.andes.ui.components.MiuixScaffold
import fuck.andes.ui.model.AgentMemoryAction
import fuck.andes.ui.model.AgentMemoryUiState
import fuck.andes.ui.model.MemoryAtomUi
import fuck.andes.ui.model.MemoryProfileUi
import fuck.andes.ui.model.MemoryScenarioUi
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun AgentMemoryScreen(
    state: AgentMemoryUiState,
    onAction: (AgentMemoryAction) -> Unit,
) {
    var showClearDialog by remember { mutableStateOf(false) }
    var currentTab by remember { mutableIntStateOf(0) }

    MiuixScaffold(
        title = "记忆",
        onBack = { onAction(AgentMemoryAction.NavigateBack) },
    ) { paddingValues, scrollBehavior, sidePadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            TabRow(
                tabs = listOf("MEMORY.md", "原子记忆", "场景记忆", "核心画像"),
                selectedTabIndex = currentTab,
                onTabSelected = { currentTab = it },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (currentTab) {
                    0 -> MemoryFileTab(state = state, onAction = onAction, onShowClearDialog = { showClearDialog = it })
                    1 -> MemoryAtomTab(state = state, onAction = onAction, scrollBehavior = scrollBehavior)
                    2 -> MemoryScenarioTab(state = state, onAction = onAction, scrollBehavior = scrollBehavior)
                    else -> MemoryProfileTab(state = state, onAction = onAction, scrollBehavior = scrollBehavior)
                }
            }
        }
    }

    if (showClearDialog) {
        WindowDialog(
            show = true,
            title = "清空全部记忆？",
            summary = "MEMORY.md 的全部内容将被删除，记忆开关保持当前状态。",
            onDismissRequest = { showClearDialog = false },
        ) {
            MiuixDialogActions(
                confirmText = "清空",
                destructive = true,
                confirmEnabled = !state.isSaving,
                onCancel = { showClearDialog = false },
                onConfirm = {
                    showClearDialog = false
                    onAction(AgentMemoryAction.Clear)
                },
            )
        }
    }

    state.notice?.let { notice ->
        WindowDialog(
            show = true,
            title = "记忆",
            summary = notice,
            onDismissRequest = { onAction(AgentMemoryAction.DismissNotice) },
        ) {
            TextButton(
                text = "知道了",
                onClick = { onAction(AgentMemoryAction.DismissNotice) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** MEMORY.md 编辑器（原功能保持不变）。 */
@Composable
private fun MemoryFileTab(
    state: AgentMemoryUiState,
    onAction: (AgentMemoryAction) -> Unit,
    onShowClearDialog: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .weight(1f, fill = false)
                .overScrollVertical()
                .scrollEndHaptic(),
            overscrollEffect = null,
        ) {
            item(key = "status-title") { SmallTitle("记忆") }
            item(key = "status-card") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    SwitchPreference(
                        title = "启用记忆",
                        summary = "关闭后不注入记忆，也不允许模型读写；已有内容会保留",
                        checked = state.enabled,
                        enabled = !state.isLoading,
                        onCheckedChange = { onAction(AgentMemoryAction.ToggleEnabled(it)) },
                    )
                    SwitchPreference(
                        title = "启用四层记忆",
                        summary = "本地 L0 对话 / L1 原子 / L2 场景 / L3 画像；关闭后模型不可读写，内容保留",
                        checked = state.fourLayerEnabled,
                        enabled = !state.fourLayerLoading,
                        onCheckedChange = { onAction(AgentMemoryAction.ToggleFourLayerEnabled(it)) },
                    )
                    SwitchPreference(
                        title = "MEMORY.md 自动蒸馏同步",
                        summary = "用 bge 语义判定(核心度≥0.75)的画像级核心事实，自动同步写入 MEMORY.md",
                        checked = state.distillMdSync,
                        enabled = !state.fourLayerLoading,
                        onCheckedChange = { onAction(AgentMemoryAction.ToggleDistillMdSync(it)) },
                    )
                    BasicComponent(
                        title = "核心记忆注入预算",
                        summary = "每轮最多注入 ${formatNumber(state.coreBudgetChars)} 字符，详细内容由模型按需读取",
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .imePadding()
                .navigationBarsPadding(),
        ) {
            SmallTitle("MEMORY.md")
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    TextField(
                        value = state.draft,
                        onValueChange = { onAction(AgentMemoryAction.DraftChanged(it)) },
                        label = "# 核心记忆\n- 用户名字：\n- 长期偏好：",
                        useLabelAsPlaceholder = true,
                        enabled = !state.isLoading && !state.isSaving,
                        minLines = 6,
                        maxLines = 12,
                        textStyle = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val overLimit = state.draftBytes > state.maxBytes
                        Text(
                            text = when {
                                overLimit -> "已超过 1 MiB 上限，请删减"
                                state.hasUnsavedChanges -> "未保存的更改"
                                else -> ""
                            },
                            color = if (overLimit) {
                                MiuixTheme.colorScheme.error
                            } else {
                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                            },
                            style = MiuixTheme.textStyles.footnote1,
                        )
                        Text(
                            text = "${formatBytes(state.draftBytes)} / 1 MiB",
                            color = if (overLimit) {
                                MiuixTheme.colorScheme.error
                            } else {
                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                            },
                            style = MiuixTheme.textStyles.footnote1,
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            text = "清空",
                            enabled = !state.isLoading && !state.isSaving && state.draft.isNotEmpty(),
                            onClick = { onShowClearDialog(true) },
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            text = if (state.isSaving) "保存中" else "保存",
                            enabled = state.canSave,
                            onClick = { onAction(AgentMemoryAction.Save) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                }
            }
        }
    }
}

/** L1 原子记忆。 */
@Composable
private fun MemoryAtomTab(
    state: AgentMemoryUiState,
    onAction: (AgentMemoryAction) -> Unit,
    scrollBehavior: top.yukonga.miuix.kmp.basic.ScrollBehavior,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .weight(1f, fill = false)
                .overScrollVertical()
                .scrollEndHaptic()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            overscrollEffect = null,
        ) {
            item(key = "atom-title") {
                SmallTitle("L1 原子记忆（${state.atoms.size} 条）")
            }
            if (state.atoms.isEmpty()) {
                item(key = "atom-empty") {
                    BasicComponent(
                        title = "还没有原子记忆",
                        summary = "模型会在对话中自动沉淀稳定事实，也可以在上方手动添加",
                    )
                }
            } else {
                items(state.atoms.size, key = { index -> state.atoms[index].id }) { index ->
                    val atom = state.atoms[index]
                    AtomRow(atom = atom, onDelete = { onAction(AgentMemoryAction.DeleteAtom(atom.id)) })
                }
            }
        }
        Column(
            modifier = Modifier
                .imePadding()
                .navigationBarsPadding(),
        ) {
            SmallTitle("新增原子记忆")
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    TextField(
                        value = state.atomInput,
                        onValueChange = { onAction(AgentMemoryAction.AtomInputChanged(it)) },
                        label = "内容（一句话陈述一个稳定事实）",
                        useLabelAsPlaceholder = true,
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = state.atomCategory,
                        onValueChange = { onAction(AgentMemoryAction.AtomCategoryChanged(it)) },
                        label = "分类（user / preference / project / work / life）",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(
                        text = "添加原子记忆",
                        enabled = state.atomInput.isNotBlank(),
                        onClick = { onAction(AgentMemoryAction.AddAtom) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }
}

@Composable
private fun AtomRow(atom: MemoryAtomUi, onDelete: () -> Unit) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "[${atom.category}]",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(
                    text = "删除",
                    onClick = onDelete,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = atom.content,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = formatRelativeTime(atom.updatedAt),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/** L2 场景记忆。 */
@Composable
private fun MemoryScenarioTab(
    state: AgentMemoryUiState,
    onAction: (AgentMemoryAction) -> Unit,
    scrollBehavior: top.yukonga.miuix.kmp.basic.ScrollBehavior,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .weight(1f, fill = false)
                .overScrollVertical()
                .scrollEndHaptic()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            overscrollEffect = null,
        ) {
            item(key = "scenario-title") {
                SmallTitle("L2 场景记忆（${state.scenarios.size} 个）")
            }
            if (state.scenarios.isEmpty()) {
                item(key = "scenario-empty") {
                    BasicComponent(
                        title = "还没有场景记忆",
                        summary = "按场景保存背景知识，同名写入会覆盖",
                    )
                }
            } else {
                items(state.scenarios.size, key = { index -> state.scenarios[index].id }) { index ->
                    val scenario = state.scenarios[index]
                    ScenarioRow(
                        scenario = scenario,
                        onDelete = { onAction(AgentMemoryAction.DeleteScenario(scenario.id)) },
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .imePadding()
                .navigationBarsPadding(),
        ) {
            SmallTitle("保存场景")
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    TextField(
                        value = state.scenarioNameInput,
                        onValueChange = { onAction(AgentMemoryAction.ScenarioNameChanged(it)) },
                        label = "场景名称（如：工作项目 Alpha）",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = state.scenarioContentInput,
                        onValueChange = { onAction(AgentMemoryAction.ScenarioContentChanged(it)) },
                        label = "场景背景内容",
                        useLabelAsPlaceholder = true,
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(
                        text = "保存场景",
                        enabled = state.scenarioNameInput.isNotBlank() && state.scenarioContentInput.isNotBlank(),
                        onClick = { onAction(AgentMemoryAction.SaveScenario) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ScenarioRow(scenario: MemoryScenarioUi, onDelete: () -> Unit) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = scenario.name,
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(
                    text = "删除",
                    onClick = onDelete,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = scenario.content,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/** L3 核心画像。 */
@Composable
private fun MemoryProfileTab(
    state: AgentMemoryUiState,
    onAction: (AgentMemoryAction) -> Unit,
    scrollBehavior: top.yukonga.miuix.kmp.basic.ScrollBehavior,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .weight(1f, fill = false)
                .overScrollVertical()
                .scrollEndHaptic()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            overscrollEffect = null,
        ) {
            item(key = "profile-title") {
                SmallTitle("L3 核心画像（${state.profile.size} 项）")
            }
            item(key = "profile-hint") {
                BasicComponent(
                    title = "常驻注入",
                    summary = "画像会随每轮对话注入模型上下文，只保留最重要、最稳定的信息",
                )
            }
            if (state.profile.isEmpty()) {
                item(key = "profile-empty") {
                    BasicComponent(
                        title = "还没有画像",
                        summary = "添加用户名字、身份、偏好、关系等长期信息",
                    )
                }
            } else {
                items(state.profile.size, key = { index -> state.profile[index].key }) { index ->
                    val entry = state.profile[index]
                    ProfileRow(entry = entry, onDelete = { onAction(AgentMemoryAction.DeleteProfile(entry.key)) })
                }
            }
        }
        Column(
            modifier = Modifier
                .imePadding()
                .navigationBarsPadding(),
        ) {
            SmallTitle("新增画像")
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    TextField(
                        value = state.profileKeyInput,
                        onValueChange = { onAction(AgentMemoryAction.ProfileKeyChanged(it)) },
                        label = "键（如：用户名字 / 职业 / 常用语言）",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = state.profileValueInput,
                        onValueChange = { onAction(AgentMemoryAction.ProfileValueChanged(it)) },
                        label = "值",
                        useLabelAsPlaceholder = true,
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(
                        text = "添加画像",
                        enabled = state.profileKeyInput.isNotBlank() && state.profileValueInput.isNotBlank(),
                        onClick = { onAction(AgentMemoryAction.AddProfile) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileRow(entry: MemoryProfileUi, onDelete: () -> Unit) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.key,
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(
                    text = "删除",
                    onClick = onDelete,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = entry.value,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun formatBytes(bytes: Int): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_024 * 1_024 -> "%.1f KiB".format(bytes / 1_024.0)
    else -> "%.2f MiB".format(bytes / (1_024.0 * 1_024.0))
}

private fun formatNumber(value: Int): String = "%,d".format(value)

private fun formatRelativeTime(timestamp: Long): String {
    val elapsed = System.currentTimeMillis() - timestamp
    return when {
        elapsed < 60_000 -> "刚刚"
        elapsed < 3_600_000 -> "${elapsed / 60_000} 分钟前"
        elapsed < 86_400_000 -> "${elapsed / 3_600_000} 小时前"
        else -> "${elapsed / 86_400_000} 天前"
    }
}
