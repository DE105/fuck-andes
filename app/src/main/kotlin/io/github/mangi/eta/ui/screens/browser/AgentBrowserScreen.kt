package io.github.mangi.eta.ui.screens.browser

import android.content.Intent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AdsClick
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.browser.AgentBrowserSession
import io.github.mangi.eta.agent.browser.BrowserSessionSnapshot
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.StatusError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * Agent 与用户共享的浏览器会话。
 *
 * 浏览器通常在后台由模型驱动；进入本页后挂载的是同一个 WebView，用户可以直接接管，
 * 不会新建一份与 Agent 状态脱节的预览。
 */
@Composable
internal fun AgentBrowserScreen(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val noExternalAppMessage = stringResource(R.string.browser_no_external_app)
    val snapshot by AgentBrowserSession.snapshots.collectAsState()
    var address by remember { mutableStateOf("") }
    var addressFocused by remember { mutableStateOf(false) }
    var actionPending by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }

    LaunchedEffect(context.applicationContext) {
        AgentBrowserSession.initialize(context.applicationContext)
    }
    LaunchedEffect(snapshot.displayUrl, addressFocused) {
        if (!addressFocused) {
            address = snapshot.displayUrl
        }
    }

    fun launchBrowserAction(action: () -> Unit) {
        if (actionPending) return
        actionPending = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
            } finally {
                actionPending = false
            }
        }
    }

    fun navigate() {
        if (actionPending) return
        val target = if (address == snapshot.displayUrl) {
            snapshot.url
        } else {
            address.trim()
        }
        if (target.isBlank()) return
        focusManager.clearFocus()
        keyboard?.hide()
        launchBrowserAction {
            AgentBrowserSession.navigateFromUser(context.applicationContext, target)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface)
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .imePadding()
            .navigationBarsPadding(),
    ) {
        TextField(
            value = address,
            onValueChange = {
                address = it
            },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { state -> addressFocused = state.isFocused },
            label = stringResource(R.string.ui_url_or_domain_name_3ee97a),
            useLabelAsPlaceholder = true,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { navigate() }),
            leadingIcon = {
                Icon(
                    imageVector = if (snapshot.url.startsWith("https://")) {
                        Icons.Rounded.Lock
                    } else {
                        Icons.Rounded.Language
                    },
                    contentDescription = null,
                    modifier = Modifier.padding(start = 12.dp).size(18.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            },
            trailingIcon = {
                IconButton(
                    onClick = ::navigate,
                    enabled = address.isNotBlank() && !actionPending,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .alpha(if (address.isNotBlank() && !actionPending) 1f else 0.34f),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = stringResource(R.string.ui_access_7f5641),
                        modifier = Modifier.size(19.dp),
                        tint = MiuixTheme.colorScheme.onSurface,
                    )
                }
            },
        )

        Spacer(modifier = Modifier.height(10.dp))
        BrowserStatusBanner(snapshot)

        BrowserWindow(
            snapshot = snapshot,
            actionPending = actionPending,
            onBack = { launchBrowserAction { AgentBrowserSession.goBackFromUser() } },
            onForward = { launchBrowserAction { AgentBrowserSession.goForwardFromUser() } },
            onRefresh = {
                if (snapshot.isLoading) {
                    scope.launch(Dispatchers.IO) {
                        AgentBrowserSession.stopFromUser()
                    }
                } else {
                    launchBrowserAction {
                        AgentBrowserSession.reloadFromUser()
                    }
                }
            },
            onOpenExternal = {
                val currentUrl = snapshot.url.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                if (currentUrl != null) {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, currentUrl.toUri()))
                    }.onFailure {
                        Toast.makeText(context, noExternalAppMessage, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onReset = { showResetDialog = true },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }

    if (showResetDialog) {
        WindowDialog(
            show = true,
            title = stringResource(R.string.ui_reset_browser_session_791b36),
            summary = stringResource(R.string.ui_this_will_close_the_current_page_and_clear_eta_brows_1cd331),
            onDismissRequest = { showResetDialog = false },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.browser_reset),
                confirmEnabled = !actionPending,
                onCancel = { showResetDialog = false },
                onConfirm = {
                    showResetDialog = false
                    address = ""
                    launchBrowserAction { AgentBrowserSession.resetFromUser() }
                },
            )
        }
    }
}

/**
 * 统一的浏览器窗口：工具栏、进度条与网页内容收进同一张卡片，
 * 进度条悬浮在内容顶部，加载时不再挤压布局。
 */
@Composable
private fun BrowserWindow(
    snapshot: BrowserSessionSnapshot,
    actionPending: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefresh: () -> Unit,
    onOpenExternal: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        insideMargin = PaddingValues(0.dp),
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.surfaceContainer,
            contentColor = MiuixTheme.colorScheme.onSurface,
        ),
    ) {
        BrowserToolbar(
            snapshot = snapshot,
            actionPending = actionPending,
            onBack = onBack,
            onForward = onForward,
            onRefresh = onRefresh,
            onOpenExternal = onOpenExternal,
            onReset = onReset,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.45f)),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                // 不能用 squircleClip：shader 遮罩会强制离屏合成，WebView 每帧重绘导致闪烁。
                // 普通 clip 走 clipToOutline，硬件裁剪对 WebView 安全。
                .clip(
                    RoundedCornerShape(
                        bottomStart = CardDefaults.CornerRadius,
                        bottomEnd = CardDefaults.CornerRadius,
                    )
                ),
        ) {
            BrowserWebViewHost(modifier = Modifier.fillMaxSize())

            BrowserLoadingProgress(snapshot)

            BrowserStateOverlay(
                snapshot = snapshot,
                onRetry = onRefresh,
            )
        }
    }
}

@Composable
private fun BrowserToolbar(
    snapshot: BrowserSessionSnapshot,
    actionPending: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefresh: () -> Unit,
    onOpenExternal: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrowserControlButton(
            icon = Icons.AutoMirrored.Rounded.ArrowBack,
            description = stringResource(R.string.browser_back),
            enabled = snapshot.canGoBack && !actionPending,
            onClick = onBack,
        )
        BrowserControlButton(
            icon = Icons.AutoMirrored.Rounded.ArrowForward,
            description = stringResource(R.string.browser_forward),
            enabled = snapshot.canGoForward && !actionPending,
            onClick = onForward,
        )
        BrowserControlButton(
            icon = if (snapshot.isLoading) {
                Icons.Rounded.Close
            } else {
                Icons.Rounded.Refresh
            },
            description = if (snapshot.isLoading) stringResource(R.string.browser_stop_loading) else stringResource(R.string.browser_refresh),
            enabled = snapshot.available && (snapshot.isLoading || !actionPending),
            onClick = onRefresh,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        ) {
            Text(
                text = snapshot.title.ifBlank { stringResource(R.string.browser_title) },
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (snapshot.host.isNotBlank()) {
                Text(
                    text = snapshot.host,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                )
            }
        }

        BrowserControlButton(
            icon = Icons.AutoMirrored.Rounded.OpenInNew,
            description = stringResource(R.string.browser_open_external),
            enabled = snapshot.available,
            onClick = onOpenExternal,
        )
        BrowserControlButton(
            icon = Icons.Rounded.Delete,
            description = stringResource(R.string.browser_reset_session),
            enabled = snapshot.available && !actionPending,
            onClick = onReset,
        )
    }
}

@Composable
private fun BrowserControlButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.34f)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                if (!enabled) disabled()
            },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MiuixTheme.colorScheme.onSurface,
        )
    }
}

private enum class BrowserOverlay {
    None,
    Empty,
    Loading,
    Failed,
}

/**
 * 加载进度条悬浮在网页顶部，不占布局；提取到 BoxScope 扩展中，避免与外层
 * ColumnScope 的 AnimatedVisibility 重载冲突。
 */
@Composable
private fun BoxScope.BrowserLoadingProgress(snapshot: BrowserSessionSnapshot) {
    AnimatedVisibility(
        visible = snapshot.isLoading && snapshot.available,
        modifier = Modifier.align(Alignment.TopCenter),
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        LinearProgressIndicator(
            progress = snapshot.progress
                .takeIf { it in 1..99 }
                ?.let { it / 100f },
            modifier = Modifier.fillMaxWidth(),
            height = 2.5.dp,
        )
    }
}

/**
 * 内容状态浮层。已有提交页面时导航/刷新保持旧页面可见，只显示顶部进度条，
 * 避免每次加载都用占位页盖住当前内容造成闪烁。
 */
@Composable
private fun BoxScope.BrowserStateOverlay(
    snapshot: BrowserSessionSnapshot,
    onRetry: () -> Unit,
) {
    val overlay = when {
        !snapshot.available -> BrowserOverlay.Empty
        !snapshot.hasCommittedPage && snapshot.error != null -> BrowserOverlay.Failed
        !snapshot.hasCommittedPage -> BrowserOverlay.Loading
        else -> BrowserOverlay.None
    }
    Crossfade(
        targetState = overlay,
        label = "browser_overlay",
        modifier = Modifier.fillMaxSize(),
    ) { state ->
        when (state) {
            BrowserOverlay.Empty -> BrowserEmptyState(modifier = Modifier.fillMaxSize())
            BrowserOverlay.Loading -> BrowserLoadingState(
                host = snapshot.host,
                modifier = Modifier.fillMaxSize(),
            )
            BrowserOverlay.Failed -> BrowserFailedState(
                error = snapshot.error,
                onRetry = onRetry,
                modifier = Modifier.fillMaxSize(),
            )
            BrowserOverlay.None -> Unit
        }
    }
}

@Composable
private fun ColumnScope.BrowserStatusBanner(snapshot: BrowserSessionSnapshot) {
    val message = when {
        snapshot.error != null -> snapshot.error
        snapshot.isUserControlling && snapshot.available ->
            stringResource(R.string.browser_user_controlling)
        else -> null
    }
    val color = when {
        snapshot.error != null -> StatusError
        else -> MiuixTheme.colorScheme.primary
    }
    val icon = if (snapshot.error != null) {
        Icons.Rounded.GppMaybe
    } else {
        Icons.Rounded.AdsClick
    }

    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
            insideMargin = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            colors = CardDefaults.defaultColors(
                color = color.copy(alpha = 0.10f),
                contentColor = MiuixTheme.colorScheme.onSurface,
            ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = color,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = message.orEmpty(),
                    modifier = Modifier.weight(1f),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun BrowserOverlayIcon(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(64.dp)
            .squircleSurface(
                color = tint.copy(alpha = 0.10f),
                cornerRadius = 20.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = tint,
        )
    }
}

/**
 * 占位状态覆盖在 WebView 之上，拦截触摸，避免用户点到尚未完成渲染的页面。
 */
private fun Modifier.consumeTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { change ->
                change.consume()
            }
        }
    }
}

@Composable
private fun BrowserEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .consumeTouches()
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BrowserOverlayIcon(
            icon = Icons.Rounded.Language,
            tint = MiuixTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.ui_the_browser_has_not_opened_the_web_page_yet_31e095),
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.ui_enter_the_url_in_the_address_bar_or_let_the_agent_br_e2ae90),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun BrowserLoadingState(
    host: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .consumeTouches()
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        InfiniteProgressIndicator(
            color = MiuixTheme.colorScheme.primary,
            size = 34.dp,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (host.isBlank()) stringResource(R.string.browser_opening) else stringResource(R.string.browser_opening_host, host),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
        )
    }
}

@Composable
private fun BrowserFailedState(
    error: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .consumeTouches()
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BrowserOverlayIcon(
            icon = Icons.Rounded.GppMaybe,
            tint = StatusError,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.ui_the_webpage_cannot_be_opened_3db06d),
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
        )
        if (!error.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = error,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        TextButton(
            text = stringResource(R.string.ui_reload_5982c4),
            onClick = onRetry,
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
}

@Composable
private fun BrowserWebViewHost(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val backgroundColor = MiuixTheme.colorScheme.surfaceContainer.toArgb()
    val container = remember(context) {
        FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(backgroundColor)
        }
    }
    DisposableEffect(container, context) {
        AgentBrowserSession.attachTo(container, context)
        onDispose { AgentBrowserSession.detachFrom(container) }
    }
    AndroidView(
        factory = { container },
        update = { view -> view.setBackgroundColor(backgroundColor) },
        modifier = modifier,
    )
}
