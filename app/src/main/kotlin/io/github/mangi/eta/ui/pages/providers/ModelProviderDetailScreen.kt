@file:android.annotation.SuppressLint("LocalContextGetResourceValueCall")

package io.github.mangi.eta.ui.pages.providers
import io.github.mangi.eta.R
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.withId
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RemoteModelFetcher
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixPageBottomSpacer
import io.github.mangi.eta.ui.components.MiuixScaffold
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.StatusSuccess
import io.github.mangi.eta.ui.layout.horizontalCutoutPadding
import io.github.mangi.eta.ui.navigation.NewProviderType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowSpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

internal data class ProviderConfigDraft(
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val systemPrompt: String,
    val isEnabled: Boolean,
    val endpointMode: String,
    val hostedWebSearchEnabled: Boolean,
    val anthropicVersion: String,
) {
    companion object {
        fun from(provider: ProviderSetting): ProviderConfigDraft = ProviderConfigDraft(
            name = provider.name,
            baseUrl = provider.baseUrl,
            apiKey = provider.apiKey,
            systemPrompt = provider.systemPrompt.orEmpty(),
            isEnabled = provider.isEnabled,
            endpointMode = when (provider) {
                is OpenAiCompatibleProviderSetting -> provider.endpointMode
                is CustomProviderSetting -> provider.endpointMode
                is AnthropicProviderSetting -> ""
            },
            hostedWebSearchEnabled = provider.hostedWebSearchEnabled,
            anthropicVersion = (provider as? AnthropicProviderSetting)?.anthropicVersion
                ?: AnthropicProviderSetting.DEFAULT_ANTHROPIC_VERSION,
        )
    }
}

internal val ProviderConfigDraftSaver = mapSaver(
    save = { draft ->
        mapOf(
            "name" to draft.name,
            "baseUrl" to draft.baseUrl,
            "apiKey" to draft.apiKey,
            "systemPrompt" to draft.systemPrompt,
            "isEnabled" to draft.isEnabled,
            "endpointMode" to draft.endpointMode,
            "hostedWebSearchEnabled" to draft.hostedWebSearchEnabled,
            "anthropicVersion" to draft.anthropicVersion,
        )
    },
    restore = { state ->
        ProviderConfigDraft(
            name = state.getValue("name") as String,
            baseUrl = state.getValue("baseUrl") as String,
            apiKey = state.getValue("apiKey") as String,
            systemPrompt = state.getValue("systemPrompt") as String,
            isEnabled = state.getValue("isEnabled") as Boolean,
            endpointMode = state.getValue("endpointMode") as String,
            hostedWebSearchEnabled = state.getValue("hostedWebSearchEnabled") as Boolean,
            anthropicVersion = state.getValue("anthropicVersion") as String,
        )
    },
)

@Composable
internal fun ModelProviderDetailScreen(
    providerId: String? = null,
    newType: NewProviderType? = null,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    var createdId by remember { mutableStateOf<String?>(null) }
    val effectiveId = providerId ?: createdId
    val provider = remember(providers, effectiveId) {
        effectiveId?.let { id -> providers.firstOrNull { it.id == id } }
    }
    val draft = remember(newType) {
        when (newType) {
            NewProviderType.OpenAiCompatible -> CustomProviderSetting(
                id = "",
                name = "",
                baseUrl = "",
                endpointMode = OpenAiEndpointMode.CHAT_COMPLETIONS,
            )
            NewProviderType.Anthropic -> AnthropicProviderSetting(
                id = "",
                name = "",
                baseUrl = "https://api.anthropic.com",
            )
            null -> null
        }
    }

    LaunchedEffect(Unit) {
        RuntimeConfigRepository.ensureDefaults(EtaApp.serviceInstance)
    }

    if (provider == null && draft == null) {
        MiuixScaffoldPage(
            title = stringResource(R.string.route_provider_details),
            onBack = onBack,
        ) {
            item(key = "missing_provider") {
                Column(
                    modifier = Modifier.fillParentMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.ui_provider_does_not_exist_83cee6))
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(text = stringResource(R.string.ui_return_11d024), onClick = onBack)
                }
            }
        }
        return
    }

    val initial = provider ?: draft!!
    val isNew = provider == null
    var currentTab by remember { mutableIntStateOf(0) }
    var configDraft by rememberSaveable(
        initial.id,
        stateSaver = ProviderConfigDraftSaver,
    ) {
        mutableStateOf(ProviderConfigDraft.from(initial))
    }
    val title = if (isNew) context.getString(R.string.page_create_new_provider_36cab9) else initial.name

    MiuixScaffold(title = title, onBack = onBack) { paddingValues, scrollBehavior, sidePadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .horizontalCutoutPadding()
                .padding(top = paddingValues.calculateTopPadding()),
        ) {
            if (!isNew) {
                TabRow(
                    tabs = listOf(context.getString(R.string.page_configuration_d7d7ce), context.getString(R.string.page_model_98fd0c)),
                    selectedTabIndex = currentTab,
                    onTabSelected = { currentTab = it },
                    modifier = Modifier.padding(
                        horizontal = sidePadding + 12.dp,
                        vertical = 8.dp,
                    ),
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (currentTab) {
                    0 -> ProviderConfigTab(
                        provider = initial,
                        draft = configDraft,
                        onDraftChange = { configDraft = it },
                        scope = scope,
                        isNew = isNew,
                        scrollBehavior = scrollBehavior,
                        contentSidePadding = sidePadding,
                        onCreated = { id -> createdId = id },
                        onDeleted = onBack,
                    )
                    1 -> if (!isNew) {
                        ProviderModelsTab(
                            provider = initial,
                            scope = scope,
                            scrollBehavior = scrollBehavior,
                            contentSidePadding = sidePadding,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderConfigTab(
    provider: ProviderSetting,
    draft: ProviderConfigDraft,
    onDraftChange: (ProviderConfigDraft) -> Unit,
    scope: CoroutineScope,
    isNew: Boolean,
    scrollBehavior: ScrollBehavior,
    contentSidePadding: Dp,
    onCreated: (String) -> Unit,
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current
    var apiKeyVisible by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var testStatus by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var isWorking by remember { mutableStateOf(false) }
    var creationCommitted by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            // 低层 MiuixScaffold 只负责把顶栏 Insets 传给调用方，输入法 Insets 由列表自行消费。
            .imePadding()
            .scrollEndHaptic()
            .overScrollVertical()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentPadding = PaddingValues(
            start = contentSidePadding,
            end = contentSidePadding,
        ),
        overscrollEffect = null,
    ) {
        item(key = "connection") {
            ProviderSection(title = stringResource(R.string.ui_connection_configuration_7d057b)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    TextField(
                        value = draft.name,
                        onValueChange = { onDraftChange(draft.copy(name = it)) },
                        label = stringResource(R.string.ui_name_1be7ae),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextField(
                        value = draft.baseUrl,
                        onValueChange = { onDraftChange(draft.copy(baseUrl = it)) },
                        label = "Base URL",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextField(
                        value = draft.apiKey,
                        onValueChange = { onDraftChange(draft.copy(apiKey = it)) },
                        label = "API Key",
                        singleLine = true,
                        visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                                Icon(
                                    painter = painterResource(
                                        if (apiKeyVisible) LucideR.drawable.lucide_ic_eye else LucideR.drawable.lucide_ic_eye_off,
                                    ),
                                    contentDescription = if (apiKeyVisible) context.getString(R.string.page_hide_bb0e7e) else context.getString(R.string.page_show_71b677),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (provider is AnthropicProviderSetting) {
                        Spacer(modifier = Modifier.height(12.dp))
                        TextField(
                            value = draft.anthropicVersion,
                            onValueChange = { onDraftChange(draft.copy(anthropicVersion = it)) },
                            label = "anthropic-version",
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                if (provider !is AnthropicProviderSetting) {
                    HorizontalDivider()
                    WindowSpinnerPreference(
                        items = listOf(
                            DropdownItem(text = "Chat Completions API"),
                            DropdownItem(text = "Responses API"),
                        ),
                        selectedIndex = if (draft.endpointMode == OpenAiEndpointMode.RESPONSES) 1 else 0,
                        title = stringResource(R.string.ui_endpoint_mode_3c8546),
                        summary = if (draft.endpointMode == OpenAiEndpointMode.RESPONSES) {
                            context.getString(R.string.page_using_typed_items_with_semantic_streaming_events_f9c906)
                        } else {
                            context.getString(R.string.page_use_standard_chat_completions_ee4b1a)
                        },
                        onSelectedIndexChange = { selectedIndex ->
                            onDraftChange(
                                draft.copy(
                                    endpointMode = if (selectedIndex == 1) {
                                        OpenAiEndpointMode.RESPONSES
                                    } else {
                                        OpenAiEndpointMode.CHAT_COMPLETIONS
                                    },
                                ),
                            )
                        },
                    )
                    if (draft.endpointMode == OpenAiEndpointMode.RESPONSES) {
                        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                        SwitchPreference(
                            title = stringResource(R.string.ui_server_side_web_search_ddb8e0),
                            summary = stringResource(R.string.ui_allows_the_model_to_call_web_searches_provided_by_th_2f752f),
                            checked = draft.hostedWebSearchEnabled,
                            onCheckedChange = {
                                onDraftChange(draft.copy(hostedWebSearchEnabled = it))
                            },
                        )
                    }
                }
                HorizontalDivider()
                BasicComponent(
                    title = stringResource(R.string.ui_test_connection_10b7d8),
                    summary = testStatus,
                    enabled = !isWorking,
                    onClick = {
                        val validationError = validateProviderDraft(context, draft)
                        if (validationError != null) {
                            testStatus = context.getString(R.string.provider_error, validationError)
                            return@BasicComponent
                        }
                        scope.launch {
                            isWorking = true
                            testStatus = context.getString(R.string.page_testing_f43705)
                            try {
                                testStatus = testConnection(
                                    context,
                                    buildUpdatedProvider(
                                        source = provider,
                                        name = draft.name,
                                        baseUrl = draft.baseUrl,
                                        apiKey = draft.apiKey,
                                        systemPrompt = draft.systemPrompt,
                                        isEnabled = draft.isEnabled,
                                        endpointMode = draft.endpointMode,
                                        hostedWebSearchEnabled = draft.hostedWebSearchEnabled,
                                        anthropicVersion = draft.anthropicVersion,
                                    )
                                )
                            } finally {
                                isWorking = false
                            }
                        }
                    },
                )
            }
        }

        item(key = "preferences_and_prompt") {
            ProviderSection(title = stringResource(R.string.ui_preferences_and_strategies_2abd3c)) {
                SwitchPreference(
                    title = stringResource(R.string.ui_enable_this_provider_683a76),
                    checked = draft.isEnabled,
                    onCheckedChange = { onDraftChange(draft.copy(isEnabled = it)) }
                )
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                Column(modifier = Modifier.padding(16.dp)) {
                    TextField(
                        value = draft.systemPrompt,
                        onValueChange = { onDraftChange(draft.copy(systemPrompt = it)) },
                        label = stringResource(R.string.ui_system_prompt_word_193981),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        singleLine = false,
                    )
                    Text(
                        text = stringResource(R.string.ui_leave_blank_to_use_the_default_mobile_agent_prompt_w_21e7c8),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        item(key = "actions") {
            // 操作分层：主按钮实心独占，次要操作降级为文字按钮，与弹窗按钮语言一致
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TextButton(
                    text = when {
                        isWorking -> context.getString(R.string.page_saving_d70d42)
                        creationCommitted -> context.getString(R.string.page_created_62cfc5)
                        isNew -> context.getString(R.string.page_create_fcbd09)
                        else -> context.getString(R.string.page_save_configuration_817af1)
                    },
                    enabled = !isWorking && !creationCommitted,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        val validationError = validateProviderDraft(context, draft)
                        if (validationError != null) {
                            status = context.getString(R.string.provider_error, validationError)
                            return@TextButton
                        }
                        scope.launch {
                            isWorking = true
                            val built = buildUpdatedProvider(
                                source = provider,
                                name = draft.name,
                                baseUrl = draft.baseUrl,
                                apiKey = draft.apiKey,
                                systemPrompt = draft.systemPrompt,
                                isEnabled = draft.isEnabled,
                                endpointMode = draft.endpointMode,
                                hostedWebSearchEnabled = draft.hostedWebSearchEnabled,
                                anthropicVersion = draft.anthropicVersion,
                            )
                            try {
                                if (isNew) {
                                    val added = ProviderRepository.addProvider(
                                        built.withId(ProviderRepository.newId())
                                    )
                                    if (added.isEnabled) {
                                        RuntimeConfigRepository.setSelectedProviderId(added.id)
                                    }
                                    RuntimeConfigRepository.syncToRemotePreferences(
                                        EtaApp.serviceInstance
                                    )
                                    status = context.getString(R.string.capability_provider_created)
                                    creationCommitted = true
                                    onCreated(added.id)
                                } else {
                                    ProviderRepository.updateProvider(built)
                                    if (built.isEnabled) {
                                        RuntimeConfigRepository.setSelectedProviderId(built.id)
                                    }
                                    RuntimeConfigRepository.syncToRemotePreferences(
                                        EtaApp.serviceInstance
                                    )
                                    status = when {
                                        !built.isEnabled -> context.getString(R.string.page_saved_provider_not_enabled_7afa54)
                                        else -> context.getString(R.string.capability_provider_saved)
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (throwable: Throwable) {
                                status = context.getString(
                                    R.string.provider_error,
                                    throwable.message ?: context.getString(R.string.provider_save_failed),
                                )
                            } finally {
                                isWorking = false
                            }
                        }
                    },
                )
                status?.let { message ->
                    Text(
                        text = message,
                        style = MiuixTheme.textStyles.footnote2,
                        color = if (message.startsWith(context.getString(R.string.page_fail_3e3c80))) StatusError else StatusSuccess,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        if (!isNew) {
            item(key = "danger_zone") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(top = 12.dp),
                    showIndication = true,
                    onClick = if (isWorking) {
                        null
                    } else {
                        {
                            if (provider.isBuiltIn) showResetDialog = true else showDeleteDialog = true
                        }
                    },
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (provider.isBuiltIn) context.getString(R.string.page_reset_built_in_configuration_35b6ec) else context.getString(R.string.page_remove_provider_9f848f),
                            fontSize = MiuixTheme.textStyles.headline1.fontSize,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        item(key = "bottom_spacer") {
            MiuixPageBottomSpacer()
        }
    }

    if (showDeleteDialog) {
        OverlayDialog(
            show = true,
            title = stringResource(R.string.ui_remove_provider_9f848f),
            summary = stringResource(R.string.provider_delete_summary, provider.name),
            onDismissRequest = { if (!isWorking) showDeleteDialog = false },
        ) {
            MiuixDialogActions(
                confirmText = if (isWorking) context.getString(R.string.page_deleting_6f941d) else context.getString(R.string.page_delete_3755f5),
                cancelEnabled = !isWorking,
                confirmEnabled = !isWorking,
                destructive = true,
                onCancel = { showDeleteDialog = false },
                onConfirm = {
                    scope.launch {
                        isWorking = true
                        try {
                            ProviderRepository.deleteProvider(provider.id)
                            RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                            showDeleteDialog = false
                            onDeleted()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (throwable: Throwable) {
                            status = context.getString(
                                R.string.provider_error,
                                throwable.message ?: context.getString(R.string.provider_delete_failed),
                            )
                            showDeleteDialog = false
                        } finally {
                            isWorking = false
                        }
                    }
                },
            )
        }
    }

    if (showResetDialog) {
        OverlayDialog(
            show = true,
            title = stringResource(R.string.ui_reset_built_in_configuration_35b6ec),
            summary = stringResource(R.string.provider_reset_summary, provider.name),
            onDismissRequest = { if (!isWorking) showResetDialog = false },
        ) {
            MiuixDialogActions(
                confirmText = if (isWorking) context.getString(R.string.page_resetting_616090) else context.getString(R.string.page_reset_3d8134),
                cancelEnabled = !isWorking,
                confirmEnabled = !isWorking,
                onCancel = { showResetDialog = false },
                onConfirm = {
                    scope.launch {
                        isWorking = true
                        try {
                            ProviderRepository.resetBuiltIn(provider.id)
                            RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                            status = context.getString(R.string.page_reset_a0cc65)
                            showResetDialog = false
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (throwable: Throwable) {
                            status = context.getString(
                                R.string.provider_error,
                                throwable.message ?: context.getString(R.string.provider_reset_failed),
                            )
                            showResetDialog = false
                        } finally {
                            isWorking = false
                        }
                    }
                },
            )
        }
    }
}

private fun buildUpdatedProvider(
    source: ProviderSetting,
    name: String,
    baseUrl: String,
    apiKey: String,
    systemPrompt: String,
    isEnabled: Boolean,
    endpointMode: String,
    hostedWebSearchEnabled: Boolean,
    anthropicVersion: String,
): ProviderSetting {
    val prompt = systemPrompt.trim().takeIf { it.isNotBlank() }
    return when (source) {
        is OpenAiCompatibleProviderSetting -> source.copy(
            name = name.trim(),
            baseUrl = baseUrl.trim(),
            apiKey = apiKey.trim(),
            systemPrompt = prompt,
            isEnabled = isEnabled,
            endpointMode = endpointMode,
            hostedWebSearchEnabled = hostedWebSearchEnabled,
        )
        is CustomProviderSetting -> source.copy(
            name = name.trim(),
            baseUrl = baseUrl.trim(),
            apiKey = apiKey.trim(),
            systemPrompt = prompt,
            isEnabled = isEnabled,
            endpointMode = endpointMode,
            hostedWebSearchEnabled = hostedWebSearchEnabled,
        )
        is AnthropicProviderSetting -> source.copy(
            name = name.trim(),
            baseUrl = baseUrl.trim(),
            apiKey = apiKey.trim(),
            systemPrompt = prompt,
            isEnabled = isEnabled,
            anthropicVersion = anthropicVersion.trim().ifBlank { AnthropicProviderSetting.DEFAULT_ANTHROPIC_VERSION },
        )
    }
}

private fun validateProviderDraft(context: android.content.Context, draft: ProviderConfigDraft): String? {
    if (draft.name.isBlank()) return context.getString(R.string.page_name_cannot_be_empty_ca8984)
    val uri = runCatching { java.net.URI(draft.baseUrl.trim()) }.getOrNull()
    if (uri == null || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
        return context.getString(R.string.page_base_url_must_be_a_valid_http_s_address_0e7d58)
    }
    return null
}

private suspend fun testConnection(
    context: android.content.Context,
    provider: ProviderSetting,
): String =
    RemoteModelFetcher.fetch(provider)
        .map { context.resources.getQuantityString(R.plurals.provider_models_fetched, it.size, it.size) }
        .getOrElse { throwable ->
            context.getString(
                R.string.provider_error,
                throwable.message ?: throwable.javaClass.simpleName,
            )
        }
