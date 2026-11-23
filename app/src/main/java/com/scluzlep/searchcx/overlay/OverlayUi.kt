package com.scluzlep.searchcx.overlay

import android.graphics.Bitmap
import android.view.MotionEvent
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.scluzlep.searchcx.data.CollapsedBallStyle
import com.scluzlep.searchcx.data.DomContextSource
import com.scluzlep.searchcx.data.ProviderProfile
import com.scluzlep.searchcx.ui.MarkdownMessageView

data class ChatUiMessage(
    val id: Long,
    val role: String,
    val content: String,
    val contextBadge: String = "",
    val reasoning: String = "",
    val imagePreview: Bitmap? = null,
    val isStreaming: Boolean = false
)

data class BuiltPrompt(
    val visibleUserText: String,
    val contextBadge: String,
    val apiPrompt: String
)

/**
 * Pure JVM helper that separates short user-facing bubble text + context badge
 * from the full API prompt containing raw page DOM context.
 */
object OverlayPromptBuilder {
    const val CONTEXT_BADGE_DOM = "DOM HTML"
    const val CONTEXT_BADGE_QUESTIONS = "题目内容"
    const val CONTEXT_BADGE_SELECTION = "页面选区"
    const val CONTEXT_BADGE_CLIPBOARD = "剪贴板文本"
    const val DEFAULT_EMPTY_DOM_LABEL = "解析网页内容"
    const val DEFAULT_IMAGE_ONLY_LABEL = "解析截图内容"
    const val DEFAULT_QUICK_ASK_PROMPT = "请直接解析以下内容，给出清晰步骤与最终结论："

    private val IMAGE_PLACEHOLDER_REGEX = Regex("""\[图片\d*\]""")

    fun quoteBadgeForStatus(domStatus: String): String = when (domStatus.trim()) {
        "已粘贴" -> CONTEXT_BADGE_CLIPBOARD
        "已抓取 · 题目" -> CONTEXT_BADGE_QUESTIONS
        "已抓取 · 选区" -> CONTEXT_BADGE_SELECTION
        "已抓取 · 正文" -> CONTEXT_BADGE_DOM
        else -> CONTEXT_BADGE_DOM
    }

    fun formatQuoteBadge(
        baseLabel: String,
        questionCount: Int = 0,
        imageCount: Int = 0
    ): String {
        val parts = ArrayList<String>(3)
        parts.add(baseLabel.trim().ifEmpty { CONTEXT_BADGE_DOM })
        if (questionCount > 0) parts.add("$questionCount 题")
        if (imageCount > 0) parts.add("$imageCount 图")
        return parts.joinToString(" · ")
    }

    fun sanitizeForTikuSearch(text: String): String {
        return text.replace(IMAGE_PLACEHOLDER_REGEX, " ")
            .replace(Regex("""[ \t]+"""), " ")
            .trim()
    }

    fun buildManual(
        rawInput: String,
        rawContextText: String,
        hasImage: Boolean,
        quickAskPrompt: String = DEFAULT_QUICK_ASK_PROMPT,
        contextLabel: String = CONTEXT_BADGE_DOM
    ): BuiltPrompt? {
        val input = rawInput.trim()
        val ctxText = rawContextText.trim()
        val askPrompt = quickAskPrompt.trim().ifEmpty { DEFAULT_QUICK_ASK_PROMPT }
        val badge = contextLabel.trim().ifEmpty { CONTEXT_BADGE_DOM }

        return when {
            input.isNotEmpty() && ctxText.isNotEmpty() -> BuiltPrompt(
                visibleUserText = input,
                contextBadge = badge,
                apiPrompt = "$input\n\n[页面上下文]\n$ctxText"
            )
            input.isNotEmpty() -> BuiltPrompt(
                visibleUserText = input,
                contextBadge = "",
                apiPrompt = input
            )
            ctxText.isNotEmpty() -> BuiltPrompt(
                visibleUserText = DEFAULT_EMPTY_DOM_LABEL,
                contextBadge = badge,
                apiPrompt = "$askPrompt\n\n[页面上下文]\n$ctxText"
            )
            hasImage -> BuiltPrompt(
                visibleUserText = DEFAULT_IMAGE_ONLY_LABEL,
                contextBadge = "",
                apiPrompt = askPrompt
            )
            else -> null
        }
    }

    fun buildQuickAction(
        displayLabel: String,
        instruction: String,
        rawContextText: String,
        hasImage: Boolean,
        contextLabel: String = CONTEXT_BADGE_DOM
    ): BuiltPrompt? {
        val ctxText = rawContextText.trim()
        if (ctxText.isEmpty() && !hasImage) return null

        val instr = instruction.trim()
        val label = displayLabel.trim().ifEmpty {
            if (ctxText.isNotEmpty()) DEFAULT_EMPTY_DOM_LABEL else instr.ifEmpty { DEFAULT_IMAGE_ONLY_LABEL }
        }
        val apiPrompt = when {
            instr.isNotEmpty() && ctxText.isNotEmpty() -> "$instr\n\n[页面上下文]\n$ctxText"
            instr.isNotEmpty() -> instr
            else -> ctxText
        }
        val badge = if (ctxText.isNotEmpty()) {
            contextLabel.trim().ifEmpty { CONTEXT_BADGE_DOM }
        } else {
            ""
        }
        return BuiltPrompt(
            visibleUserText = label,
            contextBadge = badge,
            apiPrompt = apiPrompt
        )
    }
}

data class DomImagePreviewItem(
    val index: Int,
    val bitmap: Bitmap?,
    val isFormula: Boolean,
    val label: String,
    val included: Boolean = true
)

data class DomQuestionChipItem(
    val index: Int,
    val label: String,
    val preview: String,
    val selected: Boolean
)

data class OverlayUiState(
    val profiles: List<ProviderProfile> = emptyList(),
    val activeProfileId: String = "",
    val activeProfileName: String = "OpenAI",
    val activeModel: String = "gpt-4o-mini",
    val domStatus: String = "等待采集",
    val contextMeta: String = "",
    val domQuestionCount: Int = 0,
    val domImageCount: Int = 0,
    val warningText: String = "",
    val contextText: String = "",
    val attachedImage: Bitmap? = null,
    val messages: List<ChatUiMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val draftPrompt: String = "",
    val panelOpacity: Float = 0.96f,
    val isTallMode: Boolean = false,
    val showOpacityBar: Boolean = false,
    val ballIdleRetracted: Boolean = false,
    val ballDockRight: Boolean = true,
    val ballHasUnreadReply: Boolean = false,
    val collapsedBallStyle: CollapsedBallStyle = CollapsedBallStyle.BUTTON,
    val isContextPreviewExpanded: Boolean = false,
    val activeContextSource: DomContextSource? = null,
    val availableContextSources: List<DomContextSource> = emptyList(),
    val questionChips: List<DomQuestionChipItem> = emptyList(),
    val domImagePreviews: List<DomImagePreviewItem> = emptyList(),
    val hasObfuscatedFont: Boolean = false
)

private val ACCENT_BLUE = Color(0xFF3482FF)
private val ACCENT_SOFT = Color(0xFFEAF2FF)
private val CARD_BG = Color(0xFFFFFFFF)
private val SURFACE_GRAY = Color(0xFFF2F2F7)
private val TEXT_PRIMARY = Color(0xFF111827)
private val TEXT_SECONDARY = Color(0xFF6B7280)
private val BORDER_SUBTLE = Color(0xFFE5E7EB)

/**
 * 大学搜题酱-inspired edge-docked collapsed entry: either a fixed tiny white shadowed dot
 * or a compact textless circular icon button, expanding on tap and capturing on long press.
 */
@Composable
fun FloatingBallComposable(
    state: OverlayUiState
) {
    val retracted = state.ballIdleRetracted && !state.isGenerating
    when (state.collapsedBallStyle) {
        CollapsedBallStyle.MINI_DOT -> {
            val alpha by animateFloatAsState(if (retracted) 0.62f else 0.96f, label = "miniDotAlpha")
            val edgeShift by animateDpAsState(
                targetValue = when {
                    !retracted -> 0.dp
                    state.ballDockRight -> 5.dp
                    else -> (-5).dp
                },
                label = "miniDotShift"
            )
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .offset(x = edgeShift)
                    .alpha(alpha),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .shadow(
                            elevation = 6.dp,
                            shape = CircleShape,
                            ambientColor = Color(0x66000000),
                            spotColor = Color(0x88000000),
                            clip = false
                        )
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(0.6.dp, Color(0x24000000), CircleShape)
                )
            }
        }
        CollapsedBallStyle.BUTTON -> {
            val alpha by animateFloatAsState(if (retracted) 0.52f else 0.97f, label = "ballAlpha")
            val edgeShift by animateDpAsState(
                targetValue = when {
                    !retracted -> 0.dp
                    state.ballDockRight -> 14.dp
                    else -> (-14).dp
                },
                label = "ballShift"
            )

            Box(
                modifier = Modifier
                    .padding(6.dp)
                    .offset(x = edgeShift)
                    .alpha(alpha)
            ) {
                Box(
                    modifier = Modifier.size(42.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .shadow(10.dp, CircleShape, clip = false)
                            .clip(CircleShape)
                            .background(if (state.isGenerating) ACCENT_BLUE else Color(0xFAFFFFFF))
                            .border(
                                width = 1.dp,
                                color = if (state.isGenerating) ACCENT_BLUE else Color(0xFFDCE3EE),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        val iconColor = if (state.isGenerating) Color.White else ACCENT_BLUE
                        Canvas(modifier = Modifier.size(18.dp)) {
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            val rOuter = size.minDimension * 0.48f
                            val rInner = size.minDimension * 0.16f
                            val path = Path().apply {
                                moveTo(cx, cy - rOuter)
                                quadraticTo(cx + rInner, cy - rInner, cx + rOuter, cy)
                                quadraticTo(cx + rInner, cy + rInner, cx, cy + rOuter)
                                quadraticTo(cx - rInner, cy + rInner, cx - rOuter, cy)
                                quadraticTo(cx - rInner, cy - rInner, cx, cy - rOuter)
                                close()
                            }
                            drawPath(path = path, color = iconColor)
                        }
                    }
                    if (!state.isGenerating && state.ballHasUnreadReply) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF22C55E))
                                .border(1.5.dp, Color.White, CircleShape)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FloatingPanelComposable(
    state: OverlayUiState,
    promptFocusRequester: FocusRequester,
    onDragDelta: (dx: Int, dy: Int) -> Unit,
    onSelectProfile: (String) -> Unit,
    onCaptureDom: () -> Unit,
    onCaptureScreen: () -> Unit,
    onPasteClipboard: () -> Unit,
    onToggleTall: () -> Unit,
    onToggleOpacityBar: () -> Unit,
    onSetOpacity: (Float) -> Unit,
    onCollapse: () -> Unit,
    onClearContext: () -> Unit,
    onRemoveAttachedImage: () -> Unit,
    onSearchTikuOnly: () -> Unit,
    onQuickAction: (displayLabel: String, instruction: String) -> Unit,
    onDraftChange: (String) -> Unit,
    onSendOrStop: () -> Unit,
    onClearChat: () -> Unit,
    onCopyMessage: (String) -> Unit,
    onRegenerateLast: () -> Unit,
    onRequestImeFocus: (FocusRequester) -> Unit,
    onToggleContextPreview: () -> Unit = {},
    onSelectContextSource: (DomContextSource) -> Unit = {},
    onToggleQuestionIndex: (Int) -> Unit = {},
    onSelectAllQuestions: () -> Unit = {},
    onToggleDomImageIncluded: (Int) -> Unit = {}
) {
    var showProfileMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.content?.length) {
        if (state.messages.isNotEmpty()) {
            listState.scrollToItem(state.messages.lastIndex)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(state.panelOpacity)
            .padding(4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(14.dp, RoundedCornerShape(22.dp), clip = false)
                .clip(RoundedCornerShape(22.dp))
                .background(CARD_BG)
                .border(1.dp, BORDER_SUBTLE, RoundedCornerShape(22.dp))
        ) {
            // Draggable top grabber + header strip
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF9FAFB))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    // AndroidView touch handle gives rock-solid rawX/rawY WindowManager dragging
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFECEFF4))
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                View(ctx).apply {
                                    var downRawX = 0f
                                    var downRawY = 0f
                                    setOnTouchListener { _, ev ->
                                        when (ev.actionMasked) {
                                            MotionEvent.ACTION_DOWN -> {
                                                downRawX = ev.rawX
                                                downRawY = ev.rawY
                                                true
                                            }
                                            MotionEvent.ACTION_MOVE -> {
                                                val dx = (ev.rawX - downRawX).toInt()
                                                val dy = (ev.rawY - downRawY).toInt()
                                                if (dx != 0 || dy != 0) {
                                                    downRawX = ev.rawX
                                                    downRawY = ev.rawY
                                                    onDragDelta(dx, dy)
                                                }
                                                true
                                            }
                                            else -> false
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(26.dp)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(26.dp)
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            BasicText(
                                text = "${state.activeProfileName} · ${state.activeModel}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(
                                    color = TEXT_PRIMARY,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .clickable { showProfileMenu = !showProfileMenu }
                            )
                            BasicText(
                                text = " 切换 ▾",
                                style = TextStyle(color = ACCENT_BLUE, fontSize = 11.sp, fontWeight = FontWeight.Bold),
                                modifier = Modifier.clickable { showProfileMenu = !showProfileMenu }
                            )
                        }
                    }

                    SmallHeaderIconButton(if (state.isTallMode) "半" else "全", onToggleTall)
                    SmallHeaderIconButton("透", onToggleOpacityBar)
                    SmallHeaderIconButton("—", onCollapse)
                }

                // Profile switcher tray
                AnimatedVisibility(visible = showProfileMenu) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        state.profiles.forEach { p ->
                            val selected = p.id == state.activeProfileId
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (selected) ACCENT_BLUE else SURFACE_GRAY)
                                    .clickable {
                                        onSelectProfile(p.id)
                                        showProfileMenu = false
                                    }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                BasicText(
                                    text = "${p.name} (${p.model})",
                                    style = TextStyle(
                                        color = if (selected) Color.White else TEXT_PRIMARY,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                )
                            }
                        }
                    }
                }

                // Opacity bar
                AnimatedVisibility(visible = state.showOpacityBar) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicText(
                            text = "透明度:",
                            style = TextStyle(color = TEXT_SECONDARY, fontSize = 11.sp)
                        )
                        listOf(0.55f to "55%", 0.75f to "75%", 0.90f to "90%", 1.0f to "100%").forEach { (op, lbl) ->
                            val selected = kotlin.math.abs(state.panelOpacity - op) < 0.04f
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (selected) ACCENT_BLUE else SURFACE_GRAY)
                                    .clickable { onSetOpacity(op) }
                                    .padding(horizontal = 9.dp, vertical = 4.dp)
                            ) {
                                BasicText(
                                    text = lbl,
                                    style = TextStyle(
                                        color = if (selected) Color.White else TEXT_PRIMARY,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                )
                            }
                        }
                    }
                }

                // Dual input trigger row (读DOM + 截屏框选 + 剪贴板 + 清空)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val domLabel = if (state.domStatus == "抓取 DOM 中...") "抓取中..." else "读DOM"
                    ActionPill(domLabel, ACCENT_SOFT, ACCENT_BLUE, Modifier.weight(1f), onCaptureDom)
                    ActionPill("截屏框选", ACCENT_BLUE, Color.White, Modifier.weight(1.1f), onCaptureScreen)
                    ActionPill("剪贴板", SURFACE_GRAY, TEXT_PRIMARY, Modifier.weight(0.85f), onPasteClipboard)
                    ActionPill("清空", SURFACE_GRAY, TEXT_SECONDARY, Modifier.weight(0.65f), onClearChat)
                }
            }

            // Compact context chips + quick actions strip
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFFAFAFC))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (state.contextText.isNotBlank()) {
                    val baseLabel = OverlayPromptBuilder.quoteBadgeForStatus(state.domStatus)
                    val quoteLabel = OverlayPromptBuilder.formatQuoteBadge(
                        baseLabel = baseLabel,
                        questionCount = state.domQuestionCount,
                        imageCount = state.domImageCount
                    )
                    QuoteReferenceBlock(
                        label = quoteLabel,
                        containerBg = ACCENT_SOFT,
                        barColor = ACCENT_BLUE,
                        barWidth = 3.dp,
                        textColor = ACCENT_BLUE,
                        verticalPadding = 4.dp,
                        onClick = onToggleContextPreview,
                        trailing = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CompactActionPill(
                                    label = if (state.isContextPreviewExpanded) "收起" else "预览",
                                    textColor = ACCENT_BLUE,
                                    onClick = onToggleContextPreview
                                )
                                CompactRemoveButton(onClick = onClearContext)
                            }
                        }
                    )

                    AnimatedVisibility(visible = state.isContextPreviewExpanded) {
                        ContextPreviewCard(
                            state = state,
                            onSelectContextSource = onSelectContextSource,
                            onToggleQuestionIndex = onToggleQuestionIndex,
                            onSelectAllQuestions = onSelectAllQuestions,
                            onToggleDomImageIncluded = onToggleDomImageIncluded
                        )
                    }
                }

                if (state.warningText.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFFEF3C7))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        BasicText(
                            text = state.warningText,
                            style = TextStyle(color = Color(0xFF92400E), fontSize = 11.sp, lineHeight = 16.sp),
                            modifier = Modifier.weight(1f)
                        )
                        if (state.hasObfuscatedFont) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFFD97706))
                                    .clickable(onClick = onCaptureScreen)
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                BasicText(
                                    text = "截屏框选",
                                    style = TextStyle(
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                )
                            }
                        }
                    }
                }

                if (state.attachedImage != null) {
                    val bmp = state.attachedImage
                    val imgBitmap = remember(bmp) { bmp.asImageBitmap() }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(SURFACE_GRAY)
                            .padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Image(
                            bitmap = imgBitmap,
                            contentDescription = "Crop preview",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(width = 76.dp, height = 48.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            BasicText(
                                text = "已附加截图 ${bmp.width}×${bmp.height}",
                                style = TextStyle(color = TEXT_PRIMARY, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            )
                            BasicText(
                                text = "将随本次提问发送给 AI",
                                style = TextStyle(color = TEXT_SECONDARY, fontSize = 11.sp)
                            )
                        }
                        CompactRemoveButton(onClick = onRemoveAttachedImage)
                    }
                }

                // Quick prompt chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    QuickChip("查题库") {
                        onSearchTikuOnly()
                    }
                    QuickChip("直接解题") {
                        onQuickAction("直接解题", "请直接解答以下题目或问题，给出关键推导步骤与最终结论：")
                    }
                    QuickChip("总结要点") {
                        onQuickAction("总结要点", "请提取并条理化总结以下内容的核心要点：")
                    }
                    QuickChip("准确翻译") {
                        onQuickAction("准确翻译", "请将以下内容准确翻译为中文：")
                    }
                    QuickChip("仅发原文") {
                        onQuickAction("仅发原文", "")
                    }
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BORDER_SUBTLE))

            // Chat stream
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (state.messages.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(SURFACE_GRAY)
                                .padding(12.dp)
                        ) {
                            BasicText(
                                text = "点上方「读DOM」或「截屏框选」开始提问",
                                style = TextStyle(color = TEXT_SECONDARY, fontSize = 12.sp, lineHeight = 18.sp)
                            )
                        }
                    }
                }

                items(state.messages, key = { it.id }) { msg ->
                    val isUser = msg.role == "user"
                    val isLastAssistant = !isUser && msg.id == state.messages.lastOrNull()?.id
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth(if (isUser) 0.88f else 1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (isUser) ACCENT_BLUE else SURFACE_GRAY)
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (isUser && msg.contextBadge.isNotBlank()) {
                                QuoteReferenceBlock(
                                    label = msg.contextBadge,
                                    containerBg = Color(0x29FFFFFF),
                                    barColor = Color.White,
                                    barWidth = 2.5.dp,
                                    textColor = Color.White,
                                    verticalPadding = 5.dp
                                )
                            }
                            if (msg.imagePreview != null) {
                                val bmp = msg.imagePreview
                                val img = remember(bmp) { bmp.asImageBitmap() }
                                Image(
                                    bitmap = img,
                                    contentDescription = "Sent image",
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 120.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                )
                            }
                            if (isUser) {
                                BasicText(
                                    text = msg.content.ifBlank { "[仅发送图片]" },
                                    style = TextStyle(color = Color.White, fontSize = 13.sp, lineHeight = 19.sp)
                                )
                            } else {
                                MarkdownMessageView(
                                    rawText = msg.content,
                                    reasoningText = msg.reasoning,
                                    isStreaming = msg.isStreaming,
                                    textColor = TEXT_PRIMARY,
                                    accentColor = ACCENT_BLUE,
                                    onCopyCode = onCopyMessage,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        if (!isUser && !msg.isStreaming && msg.content.isNotBlank()) {
                            Row(
                                modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                BasicText(
                                    text = "复制回答",
                                    style = TextStyle(color = ACCENT_BLUE, fontSize = 11.sp, fontWeight = FontWeight.Medium),
                                    modifier = Modifier.clickable { onCopyMessage(msg.content) }
                                )
                                if (isLastAssistant) {
                                    BasicText(
                                        text = "重新生成",
                                        style = TextStyle(color = TEXT_SECONDARY, fontSize = 11.sp, fontWeight = FontWeight.Medium),
                                        modifier = Modifier.clickable(onClick = onRegenerateLast)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Bottom prompt bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF9FAFB))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(SURFACE_GRAY)
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                onRequestImeFocus(promptFocusRequester)
                            }
                        }
                ) {
                    if (state.draftPrompt.isEmpty()) {
                        BasicText(
                            text = "输入追问，或留空直接发送上下文/截图...",
                            style = TextStyle(color = Color(0xFF9CA3AF), fontSize = 13.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    BasicTextField(
                        value = state.draftPrompt,
                        onValueChange = onDraftChange,
                        textStyle = TextStyle(color = TEXT_PRIMARY, fontSize = 13.sp, lineHeight = 18.sp),
                        cursorBrush = SolidColor(ACCENT_BLUE),
                        maxLines = 3,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (!state.isGenerating) onSendOrStop() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(promptFocusRequester)
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (state.isGenerating) Color(0xFFEF4444) else ACCENT_BLUE)
                        .clickable(onClick = onSendOrStop)
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                ) {
                    BasicText(
                        text = if (state.isGenerating) "停止" else "发送",
                        style = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    )
                }
            }
        }
    }
}

@Composable
private fun SmallHeaderIconButton(
    label: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(Color(0xFFECEFF4))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = label,
            style = TextStyle(color = TEXT_PRIMARY, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        )
    }
}

@Composable
private fun QuoteReferenceBlock(
    label: String,
    containerBg: Color,
    barColor: Color,
    barWidth: Dp,
    textColor: Color,
    verticalPadding: Dp,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(containerBg)
            .padding(horizontal = 8.dp, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(IntrinsicSize.Min)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(barWidth)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(barColor)
            )
            BasicText(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    color = textColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            )
        }
        if (trailing != null) {
            trailing()
        }
    }
}

@Composable
private fun ContextPreviewCard(
    state: OverlayUiState,
    onSelectContextSource: (DomContextSource) -> Unit,
    onToggleQuestionIndex: (Int) -> Unit,
    onSelectAllQuestions: () -> Unit,
    onToggleDomImageIncluded: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(SURFACE_GRAY)
            .border(1.dp, BORDER_SUBTLE, RoundedCornerShape(10.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (state.availableContextSources.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                state.availableContextSources.forEach { source ->
                    val label = when (source) {
                        DomContextSource.SELECTION -> "选区"
                        DomContextSource.QUESTIONS -> "按题"
                        DomContextSource.FULL_DOM -> "完整DOM"
                    }
                    PreviewFilterChip(
                        label = label,
                        selected = source == state.activeContextSource,
                        onClick = { onSelectContextSource(source) }
                    )
                }
            }
        }

        if (state.questionChips.size >= 2) {
            val allSelected = state.questionChips.all { it.selected }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PreviewFilterChip(
                    label = "全部",
                    selected = allSelected,
                    onClick = onSelectAllQuestions
                )
                state.questionChips.forEach { chip ->
                    PreviewFilterChip(
                        label = chip.label.ifBlank { "第${chip.index + 1}题" },
                        selected = chip.selected,
                        onClick = { onToggleQuestionIndex(chip.index) }
                    )
                }
            }
        }

        if (state.domImagePreviews.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                state.domImagePreviews.forEach { item ->
                    DomImageThumbnailCard(
                        item = item,
                        onClick = { onToggleDomImageIncluded(item.index) }
                    )
                }
            }
        }

        BasicText(
            text = state.contextText.trim(),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                color = TEXT_SECONDARY,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        )
    }
}

@Composable
private fun PreviewFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) ACCENT_BLUE else Color.White)
            .border(
                width = 1.dp,
                color = if (selected) ACCENT_BLUE else BORDER_SUBTLE,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        BasicText(
            text = label,
            style = TextStyle(
                color = if (selected) Color.White else TEXT_PRIMARY,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        )
    }
}

@Composable
private fun DomImageThumbnailCard(
    item: DomImagePreviewItem,
    onClick: () -> Unit
) {
    val badgeText = item.label.ifBlank {
        if (item.isFormula) "公式${item.index}" else "图${item.index}"
    }
    Column(
        modifier = Modifier
            .width(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .border(
                width = if (item.included) 1.5.dp else 1.dp,
                color = if (item.included) ACCENT_BLUE else BORDER_SUBTLE,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(width = 56.dp, height = 44.dp)
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            val bmp = item.bitmap
            if (bmp != null && !bmp.isRecycled) {
                val imgBitmap = remember(bmp) { bmp.asImageBitmap() }
                Image(
                    bitmap = imgBitmap,
                    contentDescription = badgeText,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(3.dp)
                        .alpha(if (item.included) 1f else 0.35f)
                )
            } else {
                BasicText(
                    text = if (item.isFormula) "公式" else "图片",
                    style = TextStyle(
                        color = TEXT_SECONDARY,
                        fontSize = 10.sp
                    ),
                    modifier = Modifier.alpha(if (item.included) 1f else 0.45f)
                )
            }
            if (!item.included) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xB3111827))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    BasicText(
                        text = "已排除",
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (item.included) ACCENT_SOFT else Color(0xFFE5E7EB))
                .padding(horizontal = 3.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            BasicText(
                text = badgeText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    color = if (item.included) ACCENT_BLUE else TEXT_SECONDARY,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium
                )
            )
        }
    }
}

@Composable
private fun CompactActionPill(
    label: String,
    textColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        BasicText(
            text = label,
            style = TextStyle(color = textColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        )
    }
}

@Composable
private fun CompactRemoveButton(onClick: () -> Unit) {
    CompactActionPill(
        label = "移除",
        textColor = Color(0xFFDC2626),
        onClick = onClick
    )
}

@Composable
private fun ActionPill(
    label: String,
    bg: Color,
    fg: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = label,
            style = TextStyle(color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        )
    }
}

@Composable
private fun QuickChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(ACCENT_SOFT)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        BasicText(
            text = label,
            style = TextStyle(color = ACCENT_BLUE, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        )
    }
}
