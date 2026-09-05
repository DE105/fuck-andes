@file:android.annotation.SuppressLint("LocalContextGetResourceValueCall")

package io.github.mangi.eta.ui.pages.providers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.repository.ModelRepository
import io.github.mangi.eta.data.repository.RemoteModelFetcher
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.StatusSuccess
import io.github.mangi.eta.ui.model.formatCompactTokenCount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.CheckboxLocation
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private val modelSearchSeparators = Regex("""[^\p{L}\p{N}]+""")
private val editableReasoningEfforts = listOf(
    ReasoningEffort.OFF,
    ReasoningEffort.MINIMAL,
    ReasoningEffort.LOW,
    ReasoningEffort.MEDIUM,
    ReasoningEffort.HIGH,
    ReasoningEffort.XHIGH,
    ReasoningEffort.MAX,
)

private val modelDialogBodyMaxHeight = 520.dp
private val modelDialogChromeHeight = 112.dp

private fun Modifier.modelDialogScrollableBody(): Modifier = layout { measurable, constraints ->
    // 从弹窗实际约束中为标题、间距和操作栏让出空间，避免横屏时底部按钮被内容挤出边界。
    val fallbackMaxHeight = modelDialogBodyMaxHeight.roundToPx()
    val reservedHeight = modelDialogChromeHeight.roundToPx()
    val maxHeight = if (constraints.hasBoundedHeight) {
        (constraints.maxHeight - reservedHeight)
            .coerceAtLeast(1)
            .coerceAtMost(fallbackMaxHeight)
    } else {
        fallbackMaxHeight
    }
    val placeable = measurable.measure(
        constraints.copy(
            minHeight = constraints.minHeight.coerceAtMost(maxHeight),
            maxHeight = maxHeight,
        ),
    )
    layout(placeable.width, placeable.height) {
        placeable.place(0, 0)
    }
}

internal fun contextWindowInputError(
    value: String,
    errorMessage: String = "Context window must be a positive integer",
): String? {
    val normalized = value.trim()
    if (normalized.isEmpty()) return null
    return if (normalized.toIntOrNull()?.let { it > 0 } == true) {
        null
    } else {
        errorMessage
    }
}

internal fun filterProviderModels(models: List<Model>, query: String): List<Model> {
    val queryTokens = query.lowercase().split(modelSearchSeparators).filter(String::isNotBlank)
    return models
        .sortedBy { it.sortOrder }
        .filter { model ->
            if (queryTokens.isEmpty()) {
                true
            } else {
                val searchableFields = listOf(model.displayName, model.modelId).map { field ->
                    field.lowercase().filter(Char::isLetterOrDigit)
                }
                queryTokens.all { token ->
                    searchableFields.any { field -> field.containsCharactersInOrder(token) }
                }
            }
        }
}

private fun String.containsCharactersInOrder(query: String): Boolean {
    var queryIndex = 0
    for (character in this) {
        if (character == query[queryIndex]) {
            queryIndex++
            if (queryIndex == query.length) return true
        }
    }
    return false
}

@Composable
internal fun ProviderModelsTab(
    provider: ProviderSetting,
    scope: CoroutineScope,
    scrollBehavior: ScrollBehavior,
    contentSidePadding: Dp,
) {
    val context = LocalContext.current
    val selectedModelId by RuntimeConfigRepository.selectedModelIdFlow().collectAsState(initial = null)
    var isFetching by remember { mutableStateOf(false) }
    var isMutatingModel by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var editingModel by remember { mutableStateOf<Model?>(null) }
    var isCreatingModel by remember { mutableStateOf(false) }
    var editorError by remember { mutableStateOf<String?>(null) }
    var modelPendingDelete by remember { mutableStateOf<Model?>(null) }
    var selectionMode by remember(provider.id) { mutableStateOf(false) }
    var selectedModelIds by remember(provider.id) { mutableStateOf(setOf<String>()) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var modelSearchQuery by remember(provider.id) { mutableStateOf("") }
    val normalizedModelSearchQuery = modelSearchQuery.trim()
    val filteredModels = remember(provider.models, modelSearchQuery) {
        filterProviderModels(provider.models, modelSearchQuery)
    }

    val selectionBackState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = selectionBackState,
        isBackEnabled = selectionMode,
        onBackCompleted = {
            selectionMode = false
            selectedModelIds = emptySet()
        },
    )

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = contentSidePadding,
                end = contentSidePadding,
            ),
            overscrollEffect = null,
        ) {
            item(key = "actions", contentType = "section") {
                ProviderSection(title = stringResource(R.string.ui_model_management_183414)) {
                    ArrowPreference(
                        title = if (isFetching) context.getString(R.string.page_retrieving_a880c9) else context.getString(R.string.page_automatically_pull_from_remote_f883d0),
                        summary = stringResource(R.string.provider_models_endpoint_summary, provider.baseUrl),
                        enabled = !isFetching && !isMutatingModel,
                        startAction = {
                            PreferenceIcon(
                                icon = Icons.Rounded.CloudDownload,
                                enabled = !isFetching && !isMutatingModel,
                            )
                        },
                        onClick = {
                            scope.launch {
                                isFetching = true
                                message = null
                                try {
                                    val models = RemoteModelFetcher.fetch(provider).getOrElse { throwable ->
                                        message = context.getString(
                                            R.string.provider_error,
                                            throwable.message ?: throwable.javaClass.simpleName,
                                        )
                                        return@launch
                                    }
                                    val chatModels = models.filter(RemoteModelFetcher::isChatCapableModel)
                                    val sync = ModelRepository.syncRemoteModels(provider.id, chatModels)
                                    if (sync.applied) {
                                        RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                                    }
                                    val filteredCount = models.size - chatModels.size
                                    message = if (!sync.applied) {
                                        context.getString(R.string.page_the_remote_end_did_not_return_a_usable_conversation__781487)
                                    } else if (filteredCount > 0) {
                                        context.getString(
                                            R.string.provider_models_fetched_filtered,
                                            chatModels.size,
                                            filteredCount,
                                        )
                                    } else {
                                        context.resources.getQuantityString(
                                            R.plurals.provider_models_fetched,
                                            chatModels.size,
                                            chatModels.size,
                                        )
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (throwable: Throwable) {
                                    message = context.getString(
                                        R.string.provider_error,
                                        throwable.message ?: context.getString(R.string.provider_sync_failed),
                                    )
                                } finally {
                                    isFetching = false
                                }
                            }
                        },
                    )

                    ArrowPreference(
                        title = stringResource(R.string.ui_add_custom_model_a5ddc0),
                        summary = stringResource(R.string.ui_manually_fill_in_the_display_name_and_model_id_077a7b),
                        enabled = !isFetching && !isMutatingModel,
                        startAction = {
                            PreferenceIcon(
                                icon = Icons.Rounded.Add,
                                enabled = !isFetching && !isMutatingModel,
                            )
                        },
                        onClick = {
                            editorError = null
                            isCreatingModel = true
                            editingModel = Model(
                                id = "",
                                modelId = "",
                                displayName = context.getString(R.string.page_custom_model_25be0f),
                            )
                        },
                    )
                    message?.let {
                        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                        Text(
                            text = it,
                            style = MiuixTheme.textStyles.footnote2,
                            color = if (it.startsWith(context.getString(R.string.page_fail_3e3c80))) StatusError else StatusSuccess,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
            }

            item(key = "model_search", contentType = "search") {
                InputField(
                    query = modelSearchQuery,
                    onQueryChange = { modelSearchQuery = it },
                    onSearch = {},
                    expanded = false,
                    onExpandedChange = {},
                    label = stringResource(R.string.ui_search_model_df5586),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(top = 12.dp, bottom = 8.dp),
                )
            }

            val modelListTitle = if (normalizedModelSearchQuery.isBlank()) {
                context.getString(R.string.provider_model_list_count, provider.models.size)
            } else {
                context.getString(
                    R.string.provider_model_list_matches,
                    filteredModels.size,
                    provider.models.size,
                )
            }
            if (provider.models.isEmpty() || filteredModels.isEmpty()) {
                item(key = "models_empty", contentType = "empty") {
                    ProviderSection(
                        title = modelListTitle,
                        modifier = Modifier.padding(bottom = 24.dp),
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (provider.models.isEmpty()) {
                                    context.getString(R.string.page_there_is_no_model_yet_please_pull_it_from_the_remote_ced865)
                                } else {
                                    context.getString(R.string.page_no_matching_model_found_ae7e96)
                                },
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            } else {
                item(key = "models_title", contentType = "section_title") {
                    SmallTitle(modelListTitle)
                }
                itemsIndexed(
                    items = filteredModels,
                    key = { _, model -> "model:${model.id}" },
                    contentType = { _, _ -> "model" },
                ) { index, model ->
                    ModelListGroupItem(
                        isFirst = index == 0,
                        isLast = index == filteredModels.lastIndex,
                    ) {
                        ModelListItem(
                            model = model,
                            enabled = !isFetching && !isMutatingModel,
                            isSelected = model.id == selectedModelId,
                            selectionMode = selectionMode,
                            checked = model.id in selectedModelIds,
                            onToggleChecked = {
                                selectedModelIds = if (model.id in selectedModelIds) {
                                    selectedModelIds - model.id
                                } else {
                                    selectedModelIds + model.id
                                }
                            },
                            onEnterSelection = {
                                selectionMode = true
                                selectedModelIds = setOf(model.id)
                            },
                            onEdit = {
                                editorError = null
                                isCreatingModel = false
                                editingModel = model
                            },
                            onSetCurrent = {
                                scope.launch {
                                    RuntimeConfigRepository.setSelectedModelId(model.id)
                                    RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                                }
                            },
                        )
                    }
                }
                item(key = "models_section_gap", contentType = "spacer") {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }

            item(key = "bottom_spacer", contentType = "spacer") {
                // 多选操作栏悬浮在底部时，预留高度避免遮挡最后一个列表项；其余情况与大圆角屏幕下沿保持间距
                Spacer(
                    modifier = Modifier
                        .height(if (selectionMode) 88.dp else 24.dp)
                        .navigationBarsPadding(),
                )
            }
        }

        AnimatedVisibility(
            visible = selectionMode,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            ModelSelectionBar(
                selectedCount = selectedModelIds.size,
                totalCount = provider.models.size,
                enabled = !isFetching && !isMutatingModel,
                onToggleAll = {
                    selectedModelIds = if (selectedModelIds.size == provider.models.size) {
                        emptySet()
                    } else {
                        provider.models.mapTo(mutableSetOf()) { it.id }
                    }
                },
                onDelete = { showBatchDeleteDialog = true },
                onExit = {
                    selectionMode = false
                    selectedModelIds = emptySet()
                },
            )
        }
    }

    editingModel?.let { model ->
        ModelEditDialog(
            model = model,
            isNew = isCreatingModel,
            isSaving = isMutatingModel,
            error = editorError,
            onDismiss = {
                if (!isMutatingModel) editingModel = null
            },
            onSubmit = { updated ->
                if (isMutatingModel) return@ModelEditDialog
                scope.launch {
                    isMutatingModel = true
                    editorError = null
                    try {
                        val saved = ModelRepository.saveModel(provider.id, updated)
                        RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                        editingModel = null
                        message = context.getString(R.string.provider_model_saved, saved.displayName)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (throwable: Throwable) {
                        editorError = throwable.message ?: context.getString(R.string.page_save_failed_40525a)
                    } finally {
                        isMutatingModel = false
                    }
                }
            },
            onDelete = if (isCreatingModel) null else {
                {
                    modelPendingDelete = model
                    editingModel = null
                }
            },
        )
    }

    modelPendingDelete?.let { model ->
        OverlayDialog(
            show = true,
            title = stringResource(R.string.ui_delete_model_cf24da),
            summary = stringResource(R.string.provider_model_delete_summary, model.displayName),
            onDismissRequest = { if (!isMutatingModel) modelPendingDelete = null },
        ) {
            MiuixDialogActions(
                confirmText = if (isMutatingModel) context.getString(R.string.page_deleting_6f941d) else context.getString(R.string.page_delete_3755f5),
                cancelEnabled = !isMutatingModel,
                confirmEnabled = !isMutatingModel,
                destructive = true,
                onCancel = { modelPendingDelete = null },
                onConfirm = {
                    scope.launch {
                        isMutatingModel = true
                        try {
                            ModelRepository.deleteModel(provider.id, model.id)
                            RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                            message = context.getString(R.string.provider_model_deleted, model.displayName)
                            modelPendingDelete = null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (throwable: Throwable) {
                            message = context.getString(
                                R.string.provider_error,
                                throwable.message ?: context.getString(R.string.provider_delete_failed),
                            )
                            modelPendingDelete = null
                        } finally {
                            isMutatingModel = false
                        }
                    }
                },
            )
        }
    }

    if (showBatchDeleteDialog) {
        OverlayDialog(
            show = true,
            title = stringResource(R.string.ui_delete_model_cf24da),
            summary = pluralStringResource(
                R.plurals.provider_selected_delete_summary,
                selectedModelIds.size,
                selectedModelIds.size,
            ),
            onDismissRequest = { if (!isMutatingModel) showBatchDeleteDialog = false },
        ) {
            MiuixDialogActions(
                confirmText = if (isMutatingModel) context.getString(R.string.page_deleting_6f941d) else context.getString(R.string.page_delete_3755f5),
                cancelEnabled = !isMutatingModel,
                confirmEnabled = !isMutatingModel,
                destructive = true,
                onCancel = { showBatchDeleteDialog = false },
                onConfirm = {
                    scope.launch {
                        val deletedCount = selectedModelIds.size
                        isMutatingModel = true
                        try {
                            ModelRepository.deleteModels(provider.id, selectedModelIds)
                            RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                            message = context.resources.getQuantityString(
                                R.plurals.provider_models_deleted,
                                deletedCount,
                                deletedCount,
                            )
                            showBatchDeleteDialog = false
                            selectionMode = false
                            selectedModelIds = emptySet()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (throwable: Throwable) {
                            message = context.getString(
                                R.string.provider_error,
                                throwable.message ?: context.getString(R.string.provider_delete_failed),
                            )
                            showBatchDeleteDialog = false
                        } finally {
                            isMutatingModel = false
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun ModelListGroupItem(
    isFirst: Boolean,
    isLast: Boolean,
    content: @Composable () -> Unit,
) {
    val surfaceColor = MiuixTheme.colorScheme.surfaceContainer
    val contentColor = MiuixTheme.colorScheme.onSurfaceContainer
    val cornerRadius = CardDefaults.CornerRadius
    val surfaceModifier = if (isFirst || isLast) {
        Modifier.squircleSurface(
            color = surfaceColor,
            topStart = if (isFirst) cornerRadius else 0.dp,
            topEnd = if (isFirst) cornerRadius else 0.dp,
            bottomEnd = if (isLast) cornerRadius else 0.dp,
            bottomStart = if (isLast) cornerRadius else 0.dp,
        )
    } else {
        Modifier.background(surfaceColor)
    }

    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .then(surfaceModifier),
        ) {
            content()
        }
    }
}

/** 多选模式底部悬浮操作栏：退出在左，已选数量其次，全选与删除在右；删除沿用统一破坏性配色。 */
@Composable
private fun ModelSelectionBar(
    selectedCount: Int,
    totalCount: Int,
    enabled: Boolean,
    onToggleAll: () -> Unit,
    onDelete: () -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onExit, enabled = enabled) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.ui_exit_multiple_selection_c194fd),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            }
            Text(
                text = pluralStringResource(
                    R.plurals.provider_models_selected,
                    selectedCount,
                    selectedCount,
                ),
                style = MiuixTheme.textStyles.body2,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = if (selectedCount == totalCount) context.getString(R.string.page_select_none_ba20eb) else context.getString(R.string.page_select_all_3e44b2),
                enabled = enabled,
                onClick = onToggleAll,
            )
            TextButton(
                text = stringResource(R.string.ui_delete_3755f5),
                enabled = selectedCount > 0 && enabled,
                colors = ButtonDefaults.textButtonColorsPrimary(
                    color = MiuixTheme.colorScheme.error,
                    textColor = MiuixTheme.colorScheme.onError,
                ),
                onClick = onDelete,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModelListItem(
    model: Model,
    enabled: Boolean,
    isSelected: Boolean,
    selectionMode: Boolean,
    checked: Boolean,
    onToggleChecked: () -> Unit,
    onEnterSelection: () -> Unit,
    onEdit: () -> Unit,
    onSetCurrent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                enabled = enabled,
                onClick = if (selectionMode) onToggleChecked else onSetCurrent,
                onLongClick = {
                    if (selectionMode) onToggleChecked() else onEnterSelection()
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.displayName,
                style = MiuixTheme.textStyles.headline1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = model.modelId,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 6.dp),
            ) {
                capabilityTags(model).forEach { tag ->
                    TagChip(text = tag)
                }
                if (isSelected) {
                    TagChip(text = stringResource(R.string.ui_current_25e74d), tone = TagChipTone.Emphasized)
                }
            }
        }
        if (selectionMode) {
            Checkbox(
                state = if (checked) ToggleableState.On else ToggleableState.Off,
                onClick = onToggleChecked,
                enabled = enabled,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onEdit, enabled = enabled) {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = stringResource(R.string.ui_edit_model_parameters_ba4864),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                }
                IconButton(onClick = onSetCurrent, enabled = enabled) {
                    Icon(
                        imageVector = if (isSelected) Icons.Rounded.Check
                            else Icons.Rounded.RadioButtonUnchecked,
                        contentDescription = if (isSelected) context.getString(R.string.page_current_model_a0af8f) else context.getString(R.string.page_set_as_current_model_183d7d),
                        tint = if (isSelected) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantActions
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelEditDialog(
    model: Model,
    isNew: Boolean,
    isSaving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (Model) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val context = LocalContext.current
    var displayName by remember(model.id, isNew) { mutableStateOf(model.displayName) }
    var modelId by remember(model.id, isNew) { mutableStateOf(model.modelId) }
    var contextWindowOverrideText by remember(model.id, isNew) {
        mutableStateOf(model.contextWindowOverride?.toString().orEmpty())
    }
    var reasoningOverrideActive by remember(model.id, isNew) {
        mutableStateOf(
            model.reasoningOverride != null || model.reasoningCapabilitiesOverride != null
        )
    }
    var reasoningEnabled by remember(model.id, isNew) {
        mutableStateOf(model.supportsReasoning)
    }
    var selectedReasoningEfforts by remember(model.id, isNew) {
        mutableStateOf(
            model.effectiveReasoningCapabilities
                ?.selectableEfforts
                ?.toSet()
                .orEmpty() + ReasoningEffort.DEFAULT
        )
    }
    val contextError = contextWindowInputError(
        contextWindowOverrideText,
        context.getString(R.string.page_the_context_length_must_be_a_positive_integer_06ca7a),
    )

    fun resetAutomaticReasoning() {
        reasoningOverrideActive = false
        reasoningEnabled = model.reasoning == true
        selectedReasoningEfforts = model.reasoningCapabilities
            ?.selectableEfforts
            ?.toSet()
            .orEmpty() + ReasoningEffort.DEFAULT
    }

    fun updated(): Model = model.copy(
        displayName = displayName.trim(),
        modelId = modelId.trim(),
        contextWindowOverride = contextWindowOverrideText.trim()
            .takeIf(String::isNotEmpty)
            ?.toInt(),
        reasoningOverride = reasoningEnabled.takeIf { reasoningOverrideActive },
        reasoningCapabilitiesOverride = if (reasoningOverrideActive && reasoningEnabled) {
            val canDisable = ReasoningEffort.OFF in selectedReasoningEfforts
            (model.effectiveReasoningCapabilities ?: ModelReasoningCapabilities()).copy(
                supportedEfforts = editableReasoningEfforts.filter { effort ->
                    effort != ReasoningEffort.OFF && effort in selectedReasoningEfforts
                },
                defaultEffort = model.effectiveReasoningCapabilities
                    ?.defaultEffort
                    ?.takeIf { it in selectedReasoningEfforts },
                defaultEnabled = true,
                mandatory = !canDisable,
                canDisable = canDisable,
            )
        } else {
            null
        },
    )

    OverlayDialog(
        show = true,
        title = if (isNew) context.getString(R.string.page_add_model_532a64) else context.getString(R.string.page_edit_model_29e31e),
        onDismissRequest = { if (!isSaving) onDismiss() },
    ) {
        Column {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .modelDialogScrollableBody()
                    .scrollEndHaptic()
                    .verticalScroll(rememberScrollState()),
            ) {
                TextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = stringResource(R.string.ui_display_name_ed16be),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(12.dp))
                TextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = "Model ID",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(12.dp))
                TextField(
                    value = contextWindowOverrideText,
                    onValueChange = { contextWindowOverrideText = it },
                    label = stringResource(R.string.ui_context_length_tokens_227860),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = when {
                            contextWindowOverrideText.isNotBlank() -> context.getString(R.string.page_overwritten_will_take_precedence_over_remote_metadat_59934d)
                            model.contextWindow != null ->
                                stringResource(
                                    R.string.provider_auto_context,
                                    formatCompactTokenCount(model.contextWindow),
                                )
                            else -> context.getString(R.string.page_automatic_no_context_cap_was_provided_by_the_remote__db027f)
                        },
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.weight(1f),
                    )
                    if (contextWindowOverrideText.isNotBlank()) {
                        TextButton(
                            text = stringResource(R.string.ui_restore_automatic_8d4e1e),
                            enabled = !isSaving,
                            onClick = { contextWindowOverrideText = "" },
                        )
                    }
                }
                contextError?.let { validationError ->
                    Text(
                        text = validationError,
                        style = MiuixTheme.textStyles.footnote2,
                        color = StatusError,
                    )
                }
                Text(
                    text = stringResource(R.string.ui_this_value_is_used_for_session_clipping_and_context__c3f9e7),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                Card(modifier = Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = reasoningEnabled,
                        onCheckedChange = { enabled ->
                            reasoningOverrideActive = true
                            reasoningEnabled = enabled
                        },
                        title = stringResource(R.string.ui_support_thinking_5b9e4c),
                        summary = if (reasoningOverrideActive) {
                            context.getString(R.string.page_covered_model_automatic_capabilities_3fa7d4)
                        } else {
                            stringResource(
                                if (model.reasoning == true) {
                                    R.string.provider_auto_reasoning_supported
                                } else {
                                    R.string.provider_auto_reasoning_unknown
                                },
                            )
                        },
                        enabled = !isSaving,
                    )
                    if (reasoningEnabled) {
                        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                        CheckboxPreference(
                            title = ReasoningEffort.DEFAULT.displayName,
                            summary = stringResource(R.string.ui_determined_by_model_or_provider_06c326),
                            checked = true,
                            onCheckedChange = null,
                            checkboxLocation = CheckboxLocation.End,
                            enabled = false,
                        )
                        editableReasoningEfforts.forEach { effort ->
                            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                            CheckboxPreference(
                                title = effort.displayName,
                                summary = if (effort == ReasoningEffort.OFF) {
                                    context.getString(R.string.page_allow_thinking_to_be_turned_off_during_conversations_5a32a9)
                                } else {
                                    null
                                },
                                checked = effort in selectedReasoningEfforts,
                                onCheckedChange = { checked ->
                                    reasoningOverrideActive = true
                                    selectedReasoningEfforts = if (checked) {
                                        selectedReasoningEfforts + effort
                                    } else {
                                        selectedReasoningEfforts - effort
                                    }
                                },
                                checkboxLocation = CheckboxLocation.End,
                                enabled = !isSaving,
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.ui_only_check_the_ranges_actually_supported_by_the_mode_2c343d),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 8.dp),
                )
                error?.let { message ->
                    Text(
                        text = message,
                        style = MiuixTheme.textStyles.footnote2,
                        color = StatusError,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (reasoningOverrideActive || onDelete != null) {
                    Row(
                        modifier = Modifier.padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (reasoningOverrideActive) {
                            Text(
                                text = stringResource(R.string.ui_restore_automatic_8d4e1e),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable(
                                        enabled = !isSaving,
                                        onClick = ::resetAutomaticReasoning,
                                    )
                                    .padding(vertical = 4.dp),
                            )
                        }
                        onDelete?.let { delete ->
                            Text(
                                text = stringResource(R.string.ui_delete_model_cf24da),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.error,
                                modifier = Modifier
                                    .clickable(enabled = !isSaving, onClick = delete)
                                    .padding(vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
        MiuixDialogActions(
            confirmText = if (isSaving) context.getString(R.string.page_saving_d70d42) else context.getString(R.string.page_save_fadf24),
            confirmEnabled = !isSaving &&
                displayName.isNotBlank() &&
                modelId.isNotBlank() &&
                contextError == null,
            cancelEnabled = !isSaving,
            onCancel = onDismiss,
            onConfirm = { onSubmit(updated()) },
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun capabilityTags(model: Model): List<String> {
    val context = LocalContext.current
    return buildList {
    add(
        model.effectiveContextWindow?.let { contextWindow ->
            stringResource(
                R.string.provider_context_tag,
                formatCompactTokenCount(contextWindow),
            )
        } ?: context.getString(R.string.page_context_unknown_b6ae7b)
    )
    if (model.supportsReasoning) add(context.getString(R.string.page_support_thinking_5b9e4c))
    }
}
