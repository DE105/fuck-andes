package io.github.mangi.eta.agent.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import io.github.mangi.eta.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

// Miuix 未提供语义 success 色，沿用项目既有值；失败色走主题 error
private val SuccessColor = Color(0xFF34C759)

private const val SupplementExitDelayMs = 380L

@Composable
private fun phaseAccent(phase: AgentOverlayPhase): Color = when (phase) {
    AgentOverlayPhase.RUNNING -> MiuixTheme.colorScheme.primary
    AgentOverlayPhase.PAUSED -> Color(0xFFFF9F0A)
    AgentOverlayPhase.FINISHED -> SuccessColor
    AgentOverlayPhase.FAILED -> MiuixTheme.colorScheme.error
}

// 彩虹光圈颜色（青/黄/橙/粉循环）
private val RainbowColors = listOf(
    Color(0xFFB0F2FF),
    Color(0xFFFAFAA3),
    Color(0xFFFFB472),
    Color(0xFFFB8DFF),
    Color(0xFFB0F2FF),
    Color(0xFFFB8DFF),
    Color(0xFFFFB472),
    Color(0xFFFAFAA3),
    Color(0xFFB0F2FF),
)

/**
 * 屏幕四边氛围光窗口：全屏触摸穿透（FLAG_NOT_TOUCHABLE），不挡操作。
 * 窗口类型 TYPE_ACCESSIBILITY_OVERLAY，截图时被 takeScreenshotOfWindow 过滤，对 Agent 透明。
 * - RUNNING：半透明黑底压暗 + 彩虹色旋转 SweepGradient 光圈。
 * - PAUSED / FINISHED / FAILED：不绘制。
 */
@Composable
internal fun AgentOverlayGlow(state: AgentOverlayState) {
    val phase = state.phase
    if (phase != AgentOverlayPhase.RUNNING) return

    val dimAlpha = 0.31f
    val transition = rememberInfiniteTransition(label = "glow")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(5000), RepeatMode.Restart),
        label = "rotation",
    )

    Box(
        modifier = Modifier.fillMaxSize().drawBehind {
            // 半透明黑底压暗
            drawRect(color = Color.Black.copy(alpha = dimAlpha))

            // 彩虹光圈：SweepGradient 描边 + 模糊，全屏 RectF，旋转
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val strokePx = 40f
            val colorsArgb = RainbowColors.map { it.toArgb() }
            val positions = floatArrayOf(
                0f, 0.13f, 0.257f, 0.37f, 0.505f, 0.634f, 0.744f, 0.87f, 1f
            )
            drawIntoCanvas { canvas ->
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = strokePx
                    maskFilter = android.graphics.BlurMaskFilter(
                        strokePx,
                        android.graphics.BlurMaskFilter.Blur.NORMAL,
                    )
                }
                val shader = android.graphics.SweepGradient(cx, cy, colorsArgb.toIntArray(), positions)
                val matrix = android.graphics.Matrix()
                matrix.setRotate(rotation, cx, cy)
                shader.setLocalMatrix(matrix)
                paint.shader = shader
                val rect = android.graphics.RectF(0f, 0f, w, h)
                canvas.nativeCanvas.drawRoundRect(rect, 30f, 30f, paint)
            }
        }
    )
}

/**
 * 助手光球窗口：始终显示在屏幕右侧中下，点击展开/收起小气泡。
 * 独立小窗口（WRAP_CONTENT），不遮挡页面操作。
 */
@Composable
internal fun AgentOverlayOrb(
    state: AgentOverlayState,
    onToggleCollapse: () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        visible = true
    }

    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(
            initialScale = 0.5f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        ) + fadeIn(animationSpec = tween(durationMillis = 200)),
        exit = scaleOut(
            targetScale = 0.5f,
            animationSpec = tween(durationMillis = 150)
        ) + fadeOut(animationSpec = tween(durationMillis = 150)),
    ) {
        // 点击直接交给 Service 侧 toggle，不在 Compose 协程作用域里做延迟动作，
        // 避免 scope 取消导致浮层残留。
        CollapsedAgentOrb(state = state, onExpand = onToggleCollapse)
    }
}

@Composable
private fun CollapsedAgentOrb(state: AgentOverlayState, onExpand: () -> Unit) {
    AssistantOrb(phase = state.phase, onClick = onExpand)
}

/**
 * 助手光球：外层径向光晕 + 实心球体 + 高光点。
 * 运行中光晕呼吸，暂停/完成/失败静止，颜色随阶段变化。
 */
@Composable
private fun AssistantOrb(
    phase: AgentOverlayPhase,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val accent = phaseAccent(phase)
    val pulsing = phase == AgentOverlayPhase.RUNNING
    val transition = rememberInfiniteTransition(label = "orb")
    val pulse by transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "pulse",
    )
    val haloAlpha = if (pulsing) pulse else 0.85f
    val tapModifier = if (onClick != null) Modifier.clickable { onClick() } else Modifier
    Box(
        modifier = modifier
            .then(tapModifier)
            .size(56.dp)
            .drawBehind {
                val outer = size.minDimension
                val center = Offset(outer / 2f, outer / 2f)
                // 外光晕
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.5f * haloAlpha), Color.Transparent),
                        center = center,
                        radius = outer / 2f,
                    )
                )
                // 球体
                val ballRadius = outer * 0.3f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accent, accent.copy(alpha = 0.8f)),
                        center = Offset(center.x - ballRadius * 0.3f, center.y - ballRadius * 0.3f),
                        radius = ballRadius,
                    ),
                    radius = ballRadius,
                    center = center,
                )
                // 高光
                drawCircle(
                    color = Color.White.copy(alpha = 0.55f),
                    radius = ballRadius * 0.3f,
                    center = Offset(center.x - ballRadius * 0.32f, center.y - ballRadius * 0.38f),
                )
            }
    )
}

/**
 * 运行时小气泡窗口：WRAP_CONTENT，跟随光球，窗口外触摸穿透。
 * 一句话状态 + 动作按钮 + 可展开补充输入。结束时由 Service 撤掉、改显结果卡片。
 */
@Composable
internal fun AgentOverlayBubble(
    state: AgentOverlayState,
    onCollapse: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onSupplementModeChange: (Boolean) -> Unit,
    onSupplement: (String) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        visible = true
    }

    var supplementMode by remember { mutableStateOf(false) }
    var supplementText by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    fun enterSupplementMode() {
        onSupplementModeChange(true)
        supplementMode = true
    }

    fun exitSupplementMode() {
        onSupplementModeChange(false)
        supplementMode = false
        supplementText = ""
    }

    fun closeSupplementMode() {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        scope.launch {
            delay(80)
            exitSupplementMode()
        }
    }

    fun submitSupplement() {
        val text = supplementText.trim()
        if (text.isBlank()) return
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        scope.launch {
            delay(80)
            exitSupplementMode()
            onSupplement(text)
        }
    }

    val accent = phaseAccent(state.phase)
    val statusText = state.status.localizedText()
    val statusColor = if (state.phase == AgentOverlayPhase.RUNNING) accent
    else Color(0xFFFF9F0A)

    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(
            initialScale = 0.5f,
            transformOrigin = TransformOrigin(1f, 0.5f),
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        ) + fadeIn(animationSpec = tween(durationMillis = 180)),
        exit = scaleOut(
            targetScale = 0.5f,
            transformOrigin = TransformOrigin(1f, 0.5f),
            animationSpec = tween(durationMillis = 150)
        ) + fadeOut(animationSpec = tween(durationMillis = 150)),
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 136.dp),
            cornerRadius = 16.dp,
            insideMargin = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            colors = CardDefaults.defaultColors(
                color = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.8f)
            ),
        ) {
            // 一句话状态
            Text(
                text = statusText,
                color = statusColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                lineHeight = 16.sp,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(6.dp))

            AnimatedVisibility(
                visible = supplementMode,
                enter = expandVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium,
                    )
                ) + fadeIn(animationSpec = tween(durationMillis = 140)),
                exit = shrinkVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium,
                    )
                ) + fadeOut(animationSpec = tween(durationMillis = 100)),
            ) {
                SupplementInput(
                    value = supplementText,
                    onValueChange = { supplementText = it },
                    onCancel = ::closeSupplementMode,
                    onSend = ::submitSupplement,
                )
            }

            AnimatedVisibility(
                visible = !supplementMode,
                enter = expandVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium,
                    )
                ) + fadeIn(animationSpec = tween(durationMillis = 140)),
                exit = shrinkVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium,
                    )
                ) + fadeOut(animationSpec = tween(durationMillis = 100)),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                ) {
                    IconButton(
                        onClick = ::enterSupplementMode,
                        minWidth = 28.dp,
                        minHeight = 28.dp,
                        cornerRadius = 14.dp
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = stringResource(R.string.overlay_supplement),
                            modifier = Modifier.size(14.dp),
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                    if (state.phase == AgentOverlayPhase.RUNNING) {
                        IconButton(
                            onClick = onPause,
                            minWidth = 28.dp,
                            minHeight = 28.dp,
                            cornerRadius = 14.dp
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Pause,
                                contentDescription = stringResource(R.string.overlay_pause),
                                modifier = Modifier.size(14.dp),
                                tint = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    } else if (state.phase == AgentOverlayPhase.PAUSED) {
                        IconButton(
                            onClick = onResume,
                            minWidth = 28.dp,
                            minHeight = 28.dp,
                            cornerRadius = 14.dp,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = stringResource(R.string.overlay_resume),
                                modifier = Modifier.size(14.dp),
                                tint = MiuixTheme.colorScheme.primary,
                            )
                        }
                    }
                    IconButton(
                        onClick = onStop,
                        minWidth = 28.dp,
                        minHeight = 28.dp,
                        cornerRadius = 14.dp,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Stop,
                            contentDescription = stringResource(R.string.action_stop),
                            modifier = Modifier.size(14.dp),
                            tint = MiuixTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SupplementInput(
    value: String,
    onValueChange: (String) -> Unit,
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val textColor = MiuixTheme.colorScheme.onSurface
    val fieldBg = MiuixTheme.colorScheme.surfaceContainer

    LaunchedEffect(Unit) {
        delay(180)
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp, max = 112.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(fieldBg)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.TopStart,
        ) {
            if (value.isBlank()) {
                Text(
                    text = stringResource(R.string.overlay_supplement_hint),
                    color = textColor.copy(alpha = 0.45f),
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                textStyle = TextStyle(
                    color = textColor,
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                maxLines = 4,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                text = stringResource(R.string.action_cancel),
                onClick = onCancel,
                minWidth = 44.dp,
                minHeight = 32.dp,
                insideMargin = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(
                text = stringResource(R.string.overlay_send),
                onClick = onSend,
                enabled = value.isNotBlank(),
                minWidth = 44.dp,
                minHeight = 32.dp,
                insideMargin = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/**
 * 结束时半屏结果卡片窗口：Markdown 渲染完整结果，可滚动，底部对齐。
 * 窗口本身已由 Service 定为半屏尺寸，此处填满窗口。
 */
@Composable
internal fun AgentResultCard(
    state: AgentOverlayState,
    onClose: () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        visible = true
    }

    val isFailed = state.phase == AgentOverlayPhase.FAILED
    val dotColor = phaseAccent(state.phase)
    val statusText = state.status.localizedText()
    val statusLabel = stringResource(
        if (isFailed) R.string.overlay_substatus_failed else R.string.overlay_substatus_finished,
    )
    val content = state.detailText.ifBlank { statusText }
    val textColor = MiuixTheme.colorScheme.onSurface

    Box(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 20.dp),
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                )
            ) + fadeIn(animationSpec = tween(durationMillis = 200)),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(durationMillis = 180),
            ) + fadeOut(animationSpec = tween(durationMillis = 180)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .shadow(8.dp, RoundedCornerShape(CardDefaults.CornerRadius)),
                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // 状态行降级为圆点 + 灰色小字，关闭用幽灵图标，视觉重心留给内容
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(dotColor),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = statusLabel,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontSize = 13.sp,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        // 关闭直接交给 Service，不经 Compose 协程延迟
                        IconButton(
                            onClick = onClose,
                            backgroundColor = Color.Transparent,
                            minWidth = 32.dp,
                            minHeight = 32.dp,
                            cornerRadius = 16.dp,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.action_close),
                                modifier = Modifier.size(16.dp),
                                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Markdown 结果，可滚动
                    val markdownState = rememberMarkdownState(content = content, retainState = true)
                    val typography = markdownTypography(
                        h1 = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = textColor),
                        h2 = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = textColor),
                        h3 = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = textColor),
                        text = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, color = textColor),
                        paragraph = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, color = textColor),
                        ordered = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, color = textColor),
                        bullet = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, color = textColor),
                        list = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, color = textColor),
                        code = TextStyle(
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            color = textColor,
                        ),
                    )
                    Markdown(
                        markdownState = markdownState,
                        typography = typography,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState()),
                        loading = {
                            Text(text = content, color = textColor, fontSize = 16.sp, modifier = it)
                        },
                        error = {
                            Text(text = content, color = textColor, fontSize = 16.sp, modifier = it)
                        },
                    )
                }
            }
        }
    }
}
