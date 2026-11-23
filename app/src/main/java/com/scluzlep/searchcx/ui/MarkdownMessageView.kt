package com.scluzlep.searchcx.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import kotlin.math.max

@Composable
fun MarkdownMessageView(
    rawText: String,
    reasoningText: String,
    isStreaming: Boolean,
    textColor: Color,
    accentColor: Color,
    onCopyCode: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val (tagReasoning, cleanBody) = remember(rawText) { MarkdownParser.splitThinkTags(rawText) }
    val combinedReasoning = remember(reasoningText, tagReasoning) {
        listOf(reasoningText.trim(), tagReasoning.trim()).filter { it.isNotEmpty() }.joinToString("\n\n")
    }
    val blocks = remember(cleanBody) { MarkdownParser.parse(cleanBody) }
    var reasoningExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (combinedReasoning.isNotEmpty()) {
            ReasoningCard(
                reasoning = combinedReasoning,
                isStreaming = isStreaming && cleanBody.isBlank(),
                expanded = reasoningExpanded,
                onToggle = { reasoningExpanded = !reasoningExpanded }
            )
        }

        if (blocks.isEmpty() && cleanBody.isBlank() && combinedReasoning.isBlank()) {
            BasicText(
                text = if (isStreaming) "正在生成回答..." else "",
                style = TextStyle(color = Color(0xFF8E8E93), fontSize = 13.sp)
            )
            return
        }

        val uriHandler = LocalUriHandler.current
        val onLinkClick: (String) -> Unit = { url -> runCatching { uriHandler.openUri(url) } }
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                for (block in blocks) {
                    when (block) {
                        is MdBlock.Heading -> {
                            val size = when (block.level) {
                                1 -> 17.sp
                                2 -> 16.sp
                                3 -> 15.sp
                                else -> 14.sp
                            }
                            InlineRichText(
                                inlines = block.inlines,
                                baseStyle = TextStyle(
                                    color = textColor,
                                    fontSize = size,
                                    lineHeight = size * 1.35f,
                                    fontWeight = FontWeight.Bold
                                ),
                                accentColor = accentColor,
                                onLinkClick = onLinkClick
                            )
                        }
                        is MdBlock.Paragraph -> {
                            InlineRichText(
                                inlines = block.inlines,
                                baseStyle = TextStyle(
                                    color = textColor,
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp
                                ),
                                accentColor = accentColor,
                                onLinkClick = onLinkClick
                            )
                        }
                        is MdBlock.CodeBlock -> {
                            CodeBlockCard(
                                language = block.language,
                                code = block.code,
                                closed = block.closed,
                                onCopy = { onCopyCode(block.code) }
                            )
                        }
                        is MdBlock.MathBlock -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFFEFF6FF))
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                MathBlockView(node = block.node)
                            }
                        }
                        is MdBlock.BlockQuote -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(IntrinsicSize.Min)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFFF3F4F6))
                                    .padding(vertical = 6.dp, horizontal = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .fillMaxHeight()
                                        .background(accentColor, RoundedCornerShape(2.dp))
                                )
                                InlineRichText(
                                    inlines = block.inlines,
                                    baseStyle = TextStyle(
                                        color = Color(0xFF4B5563),
                                        fontSize = 13.sp,
                                        lineHeight = 19.sp
                                    ),
                                    accentColor = accentColor,
                                    onLinkClick = onLinkClick
                                )
                            }
                        }
                        is MdBlock.BulletItem -> {
                            val indentPadding = (block.indentLevel * 14).dp
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = indentPadding),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                if (block.checked != null) {
                                    TaskCheckbox(
                                        checked = block.checked,
                                        accentColor = accentColor,
                                        modifier = Modifier.padding(top = 3.dp)
                                    )
                                } else {
                                    BasicText(
                                        text = if (block.indentLevel == 0) "•" else "◦",
                                        style = TextStyle(
                                            color = accentColor,
                                            fontSize = 14.sp,
                                            lineHeight = 20.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                }
                                InlineRichText(
                                    inlines = block.inlines,
                                    baseStyle = TextStyle(
                                        color = if (block.checked == true) Color(0xFF6B7280) else textColor,
                                        fontSize = 14.sp,
                                        lineHeight = 20.sp
                                    ),
                                    accentColor = accentColor,
                                    onLinkClick = onLinkClick
                                )
                            }
                        }
                        is MdBlock.NumberedItem -> {
                            val indentPadding = (block.indentLevel * 14).dp
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = indentPadding),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                BasicText(
                                    text = block.number,
                                    style = TextStyle(
                                        color = accentColor,
                                        fontSize = 13.sp,
                                        lineHeight = 20.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                )
                                InlineRichText(
                                    inlines = block.inlines,
                                    baseStyle = TextStyle(
                                        color = textColor,
                                        fontSize = 14.sp,
                                        lineHeight = 20.sp
                                    ),
                                    accentColor = accentColor,
                                    onLinkClick = onLinkClick
                                )
                            }
                        }
                        is MdBlock.Table -> {
                            TableCard(
                                headers = block.headerInlines,
                                rows = block.rowInlines,
                                accentColor = accentColor,
                                onLinkClick = onLinkClick
                            )
                        }
                        MdBlock.Divider -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(Color(0xFFE5E7EB))
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskCheckbox(
    checked: Boolean,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(14.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(if (checked) accentColor else Color.Transparent)
            .border(
                width = 1.25.dp,
                color = if (checked) accentColor else Color(0xFF9CA3AF),
                shape = RoundedCornerShape(3.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Canvas(modifier = Modifier.size(9.dp)) {
                val path = Path().apply {
                    moveTo(size.width * 0.14f, size.height * 0.52f)
                    lineTo(size.width * 0.42f, size.height * 0.80f)
                    lineTo(size.width * 0.86f, size.height * 0.22f)
                }
                drawPath(
                    path = path,
                    color = Color.White,
                    style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }
    }
}

@Composable
private fun ReasoningCard(
    reasoning: String,
    isStreaming: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFF3F4F6))
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(
                text = if (isStreaming) "思考中" else "思考过程",
                style = TextStyle(
                    color = Color(0xFF4B5563),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            )
            BasicText(
                text = if (expanded) "收起" else "展开",
                style = TextStyle(color = Color(0xFF6B7280), fontSize = 11.sp)
            )
        }
        if (expanded) {
            BasicText(
                text = reasoning,
                style = TextStyle(
                    color = Color(0xFF4B5563),
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            )
        }
    }
}

@Composable
private fun CodeBlockCard(
    language: String,
    code: String,
    closed: Boolean,
    onCopy: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1E293B))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0F172A))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(
                text = language.ifBlank { "code" } + if (closed) "" else " · 输出中",
                style = TextStyle(color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Medium)
            )
            BasicText(
                text = "复制代码",
                style = TextStyle(color = Color(0xFF60A5FA), fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onCopy)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(10.dp)
        ) {
            BasicText(
                text = code,
                style = TextStyle(
                    color = Color(0xFFE2E8F0),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    fontFamily = FontFamily.Monospace
                )
            )
        }
    }
}

@Composable
private fun TableCard(
    headers: List<List<MdInline>>,
    rows: List<List<List<MdInline>>>,
    accentColor: Color,
    onLinkClick: (String) -> Unit
) {
    val colCount = max(headers.size, rows.maxOfOrNull { it.size } ?: 0)
    if (colCount == 0) return

    val colWidths = remember(headers, rows, colCount) {
        List(colCount) { col ->
            val headerLen = estimateInlinesLength(headers.getOrNull(col).orEmpty())
            val maxRowLen = rows.maxOfOrNull { row ->
                estimateInlinesLength(row.getOrNull(col).orEmpty())
            } ?: 0
            val longest = max(headerLen, maxRowLen)
            (longest * 8 + 24).coerceIn(72, 176).dp
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFF9FAFB))
            .horizontalScroll(rememberScrollState())
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (c in 0 until colCount) {
                Box(modifier = Modifier.width(colWidths[c])) {
                    InlineRichText(
                        inlines = headers.getOrNull(c).orEmpty(),
                        baseStyle = TextStyle(
                            color = Color(0xFF111827),
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        accentColor = accentColor,
                        onLinkClick = onLinkClick
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .width(colWidths.fold(0.dp) { acc, w -> acc + w } + (12 * (colCount - 1)).dp)
                .height(1.dp)
                .background(Color(0xFFE5E7EB))
        )
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (c in 0 until colCount) {
                    Box(modifier = Modifier.width(colWidths[c])) {
                        InlineRichText(
                            inlines = row.getOrNull(c).orEmpty(),
                            baseStyle = TextStyle(
                                color = Color(0xFF374151),
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            ),
                            accentColor = accentColor,
                            onLinkClick = onLinkClick
                        )
                    }
                }
            }
        }
    }
}

private fun estimateInlinesLength(inlines: List<MdInline>): Int {
    var total = 0
    for (inline in inlines) {
        total += when (inline) {
            is MdInline.Plain -> inline.text.length
            is MdInline.Bold -> inline.text.length
            is MdInline.Italic -> inline.text.length
            is MdInline.BoldItalic -> inline.text.length
            is MdInline.Strike -> inline.text.length
            is MdInline.InlineCode -> inline.code.length + 2
            is MdInline.InlineMath -> inline.pretty.length + 2
            is MdInline.Link -> inline.label.length
        }
    }
    return total
}

@Composable
private fun InlineRichText(
    inlines: List<MdInline>,
    baseStyle: TextStyle,
    accentColor: Color,
    onLinkClick: (String) -> Unit
) {
    val baseSize = if (baseStyle.fontSize.isSpecified) baseStyle.fontSize else 14.sp
    val annotated: AnnotatedString = remember(inlines, accentColor, baseSize) {
        buildAnnotatedString {
            appendMdInlines(
                inlines = inlines,
                baseFontSize = baseSize,
                accentColor = accentColor,
                onLinkClick = onLinkClick
            )
        }
    }
    BasicText(text = annotated, style = baseStyle)
}

private fun AnnotatedString.Builder.appendMdInlines(
    inlines: List<MdInline>,
    baseFontSize: TextUnit,
    accentColor: Color,
    onLinkClick: (String) -> Unit
) {
    for (inline in inlines) {
        when (inline) {
            is MdInline.Plain -> append(inline.text)
            is MdInline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                if (inline.children.isNotEmpty()) {
                    appendMdInlines(inline.children, baseFontSize, accentColor, onLinkClick)
                } else {
                    append(inline.text)
                }
            }
            is MdInline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                if (inline.children.isNotEmpty()) {
                    appendMdInlines(inline.children, baseFontSize, accentColor, onLinkClick)
                } else {
                    append(inline.text)
                }
            }
            is MdInline.BoldItalic -> withStyle(
                SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
            ) {
                if (inline.children.isNotEmpty()) {
                    appendMdInlines(inline.children, baseFontSize, accentColor, onLinkClick)
                } else {
                    append(inline.text)
                }
            }
            is MdInline.Strike -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                if (inline.children.isNotEmpty()) {
                    appendMdInlines(inline.children, baseFontSize, accentColor, onLinkClick)
                } else {
                    append(inline.text)
                }
            }
            is MdInline.InlineCode -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = Color(0xFFE5E7EB),
                    color = Color(0xFF1E293B)
                )
            ) {
                append(" ${inline.code} ")
            }
            is MdInline.InlineMath -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Serif,
                    color = Color(0xFF1D4ED8),
                    background = Color(0xFFEFF6FF),
                    fontWeight = FontWeight.Medium
                )
            ) {
                append(" ")
                appendInlineMath(inline.node, baseFontSize = baseFontSize)
                append(" ")
            }
            is MdInline.Link -> {
                val link = LinkAnnotation.Clickable(tag = inline.url) {
                    onLinkClick(inline.url)
                }
                withLink(link) {
                    withStyle(
                        SpanStyle(
                            color = accentColor,
                            textDecoration = TextDecoration.Underline
                        )
                    ) {
                        append(inline.label)
                    }
                }
            }
        }
    }
}

private fun AnnotatedString.Builder.appendInlineMath(
    node: MathNode,
    baseFontSize: TextUnit,
    inheritedStyle: MathFontStyle? = null,
    depth: Int = 0
) {
    when (node) {
        is MathNode.Row -> appendInlineMathRow(node.children, baseFontSize, inheritedStyle, depth)
        is MathNode.Text -> {
            val effective = resolveFontStyle(node.style, inheritedStyle)
            withStyle(mathSpanStyleFor(effective)) {
                append(node.text)
            }
        }
        is MathNode.Operator -> {
            withStyle(
                SpanStyle(
                    fontStyle = FontStyle.Normal,
                    fontWeight = if (node.isLarge) FontWeight.SemiBold else FontWeight.Normal
                )
            ) {
                append(node.symbol)
            }
        }
        is MathNode.Fraction -> {
            if (node.hasBar) {
                appendInlineFractionPart(node.numerator, baseFontSize, inheritedStyle, depth)
                append("/")
                appendInlineFractionPart(node.denominator, baseFontSize, inheritedStyle, depth)
            } else {
                append("binom(")
                appendInlineMath(node.numerator, baseFontSize, inheritedStyle, depth)
                append(", ")
                appendInlineMath(node.denominator, baseFontSize, inheritedStyle, depth)
                append(")")
            }
        }
        is MathNode.Radical -> {
            if (node.degree != null) {
                val degSize = (baseFontSize.value * 0.68f).coerceAtLeast(8f).sp
                val shift = if (depth == 0) BaselineShift.Superscript else BaselineShift(0.65f)
                withStyle(SpanStyle(fontSize = degSize, baselineShift = shift)) {
                    appendInlineMath(node.degree, degSize, inheritedStyle, depth + 1)
                }
            }
            append("√(")
            appendInlineMath(node.radicand, baseFontSize, inheritedStyle, depth)
            append(")")
        }
        is MathNode.Script -> {
            appendInlineMath(node.base, baseFontSize, inheritedStyle, depth)
            val scriptSize = (baseFontSize.value * 0.72f).coerceAtLeast(if (depth >= 1) 8f else 9f).sp
            if (node.subscript != null) {
                val subShift = if (depth == 0) BaselineShift.Subscript else BaselineShift(-0.55f)
                withStyle(SpanStyle(fontSize = scriptSize, baselineShift = subShift)) {
                    appendInlineMath(node.subscript, scriptSize, inheritedStyle, depth + 1)
                }
            }
            if (node.superscript != null) {
                val supShift = if (depth == 0) BaselineShift.Superscript else BaselineShift(0.68f)
                withStyle(SpanStyle(fontSize = scriptSize, baselineShift = supShift)) {
                    appendInlineMath(node.superscript, scriptSize, inheritedStyle, depth + 1)
                }
            }
        }
        is MathNode.Accent -> {
            if (node.kind == MathAccentKind.UNDERLINE) {
                withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
                    appendInlineMath(node.base, baseFontSize, inheritedStyle, depth)
                }
            } else {
                append(MarkdownParser.formatMathPretty(node))
            }
        }
        is MathNode.Styled -> {
            appendInlineMath(node.content, baseFontSize, node.style, depth)
        }
        is MathNode.Delimited -> {
            append(node.left)
            appendInlineMath(node.content, baseFontSize, inheritedStyle, depth)
            append(node.right)
        }
        is MathNode.Matrix, is MathNode.Cases, is MathNode.AlignedLines -> {
            append(MarkdownParser.formatMathPretty(node))
        }
    }
}

private fun AnnotatedString.Builder.appendInlineMathRow(
    children: List<MathNode>,
    baseFontSize: TextUnit,
    inheritedStyle: MathFontStyle?,
    depth: Int
) {
    for (idx in children.indices) {
        val cur = children[idx]
        val prev = children.getOrNull(idx - 1)
        if (prev != null && inlineNeedsGap(prev, cur)) {
            append(" ")
        }
        if (cur is MathNode.Operator && cur.spacing) {
            if (idx > 0) append(" ")
            appendInlineMath(cur, baseFontSize, inheritedStyle, depth)
            if (idx < children.lastIndex && cur.symbol != " ") append(" ")
        } else {
            appendInlineMath(cur, baseFontSize, inheritedStyle, depth)
            if (cur is MathNode.Text && (cur.text == "," || cur.text == ";") && idx < children.lastIndex) {
                append(" ")
            }
        }
    }
}

private fun AnnotatedString.Builder.appendInlineFractionPart(
    part: MathNode,
    baseFontSize: TextUnit,
    inheritedStyle: MathFontStyle?,
    depth: Int
) {
    val wrap = isCompoundForInlineFraction(part)
    if (wrap) append("(")
    appendInlineMath(part, baseFontSize, inheritedStyle, depth)
    if (wrap) append(")")
}

private fun isCompoundForInlineFraction(node: MathNode): Boolean = when (node) {
    is MathNode.Row -> node.children.size > 1
    is MathNode.Fraction -> true
    else -> false
}

private fun isFuncOrLargeOp(node: MathNode): Boolean = when (node) {
    is MathNode.Operator -> node.isLarge || node.limitsUnderOver
    is MathNode.Text -> node.style == MathFontStyle.Function
    is MathNode.Script -> isFuncOrLargeOp(node.base)
    is MathNode.Styled -> node.style == MathFontStyle.Function
    else -> false
}

private fun inlineNeedsGap(prev: MathNode, cur: MathNode): Boolean {
    if (prev is MathNode.Operator && prev.spacing) return false
    if (cur is MathNode.Operator && cur.spacing) return false
    if (isFuncOrLargeOp(prev)) {
        val lead = when (cur) {
            is MathNode.Text -> cur.text
            is MathNode.Delimited -> cur.left
            else -> "x"
        }
        if (lead.isNotEmpty() && lead[0] !in setOf('(', '[', '{', ',', '.', ';', ':')) {
            return true
        }
    }
    if (isFuncOrLargeOp(cur)) {
        val trail = when (prev) {
            is MathNode.Text -> prev.text
            is MathNode.Delimited -> prev.right
            else -> "x"
        }
        if (trail.isNotEmpty() && trail.last() !in setOf('(', '[', '{', ' ')) {
            return true
        }
    }
    return false
}

private fun resolveFontStyle(own: MathFontStyle, inherited: MathFontStyle?): MathFontStyle {
    if (inherited == null) return own
    if (own == MathFontStyle.Normal) return inherited
    return when (inherited) {
        MathFontStyle.Bold -> if (own == MathFontStyle.Italic) MathFontStyle.BoldItalic else MathFontStyle.Bold
        MathFontStyle.Italic -> if (own == MathFontStyle.Bold) MathFontStyle.BoldItalic else MathFontStyle.Italic
        else -> inherited
    }
}

private fun mathSpanStyleFor(style: MathFontStyle): SpanStyle = when (style) {
    MathFontStyle.Normal, MathFontStyle.Upright, MathFontStyle.Function -> SpanStyle(
        fontStyle = FontStyle.Normal,
        fontFamily = FontFamily.Serif
    )
    MathFontStyle.Italic -> SpanStyle(
        fontStyle = FontStyle.Italic,
        fontFamily = FontFamily.Serif
    )
    MathFontStyle.Bold -> SpanStyle(
        fontWeight = FontWeight.Bold,
        fontStyle = FontStyle.Normal,
        fontFamily = FontFamily.Serif
    )
    MathFontStyle.BoldItalic -> SpanStyle(
        fontWeight = FontWeight.Bold,
        fontStyle = FontStyle.Italic,
        fontFamily = FontFamily.Serif
    )
    MathFontStyle.Calligraphic -> SpanStyle(
        fontStyle = FontStyle.Italic,
        fontFamily = FontFamily.Cursive
    )
}

@Composable
fun MathBlockView(
    node: MathNode,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF1E3A8A),
    fontSize: TextUnit = 15.sp,
    inheritedStyle: MathFontStyle? = null
) {
    when (node) {
        is MathNode.Row -> {
            MathRowView(
                children = node.children,
                modifier = modifier,
                color = color,
                fontSize = fontSize,
                inheritedStyle = inheritedStyle
            )
        }
        is MathNode.Text, is MathNode.Operator -> {
            val annotated = remember(node, fontSize, inheritedStyle) {
                buildAnnotatedString {
                    appendInlineMath(node, baseFontSize = fontSize, inheritedStyle = inheritedStyle)
                }
            }
            val effectiveSize = if (node is MathNode.Operator && node.isLarge) {
                (fontSize.value * if (node.limitsUnderOver) 1.28f else 1.38f).sp
            } else {
                fontSize
            }
            BasicText(
                text = annotated,
                modifier = modifier,
                style = TextStyle(
                    color = color,
                    fontSize = effectiveSize,
                    lineHeight = effectiveSize * 1.25f,
                    fontFamily = FontFamily.Serif
                )
            )
        }
        is MathNode.Styled -> {
            MathBlockView(
                node = node.content,
                modifier = modifier,
                color = color,
                fontSize = fontSize,
                inheritedStyle = node.style
            )
        }
        is MathNode.Fraction -> {
            val childFontSize = (fontSize.value * 0.88f).coerceAtLeast(10f).sp
            Column(
                modifier = modifier
                    .width(IntrinsicSize.Max)
                    .padding(horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)) {
                    MathBlockView(
                        node = node.numerator,
                        color = color,
                        fontSize = childFontSize,
                        inheritedStyle = inheritedStyle
                    )
                }
                if (node.hasBar) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(color)
                    )
                } else {
                    Spacer(modifier = Modifier.height(3.dp))
                }
                Box(modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)) {
                    MathBlockView(
                        node = node.denominator,
                        color = color,
                        fontSize = childFontSize,
                        inheritedStyle = inheritedStyle
                    )
                }
            }
        }
        is MathNode.Radical -> {
            Row(
                modifier = modifier.height(IntrinsicSize.Min),
                verticalAlignment = Alignment.Bottom
            ) {
                if (node.degree != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Top)
                            .padding(top = 0.dp, end = 1.dp)
                    ) {
                        MathBlockView(
                            node = node.degree,
                            color = color,
                            fontSize = (fontSize.value * 0.64f).coerceAtLeast(9f).sp,
                            inheritedStyle = inheritedStyle
                        )
                    }
                }
                Canvas(
                    modifier = Modifier
                        .width(11.dp)
                        .fillMaxHeight()
                ) {
                    val w = size.width
                    val h = size.height
                    val stroke = 1.25.dp.toPx()
                    val path = Path().apply {
                        moveTo(0f, h * 0.58f)
                        lineTo(w * 0.25f, h * 0.50f)
                        lineTo(w * 0.55f, (h - stroke).coerceAtLeast(h * 0.6f))
                        lineTo(w, stroke * 0.5f)
                    }
                    drawPath(
                        path = path,
                        color = color,
                        style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
                Column(modifier = Modifier.width(IntrinsicSize.Max)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(color)
                    )
                    Box(
                        modifier = Modifier.padding(
                            start = 2.dp,
                            end = 3.dp,
                            top = 2.dp,
                            bottom = 1.dp
                        )
                    ) {
                        MathBlockView(
                            node = node.radicand,
                            color = color,
                            fontSize = fontSize,
                            inheritedStyle = inheritedStyle
                        )
                    }
                }
            }
        }
        is MathNode.Script -> {
            MathScriptView(
                node = node,
                modifier = modifier,
                color = color,
                fontSize = fontSize,
                inheritedStyle = inheritedStyle
            )
        }
        is MathNode.Accent -> {
            MathAccentView(
                node = node,
                modifier = modifier,
                color = color,
                fontSize = fontSize,
                inheritedStyle = inheritedStyle
            )
        }
        is MathNode.Delimited -> {
            if (!contains2DNode(node.content)) {
                val annotated = remember(node, fontSize, inheritedStyle) {
                    buildAnnotatedString {
                        appendInlineMath(node, baseFontSize = fontSize, inheritedStyle = inheritedStyle)
                    }
                }
                BasicText(
                    text = annotated,
                    modifier = modifier,
                    style = TextStyle(
                        color = color,
                        fontSize = fontSize,
                        lineHeight = fontSize * 1.25f,
                        fontFamily = FontFamily.Serif
                    )
                )
            } else {
                Row(
                    modifier = modifier.height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (node.left.isNotEmpty()) {
                        MathDelimiterView(symbol = node.left, color = color)
                    }
                    Box(modifier = Modifier.padding(horizontal = 2.dp)) {
                        MathBlockView(
                            node = node.content,
                            color = color,
                            fontSize = fontSize,
                            inheritedStyle = inheritedStyle
                        )
                    }
                    if (node.right.isNotEmpty()) {
                        MathDelimiterView(symbol = node.right, color = color)
                    }
                }
            }
        }
        is MathNode.Matrix -> {
            Row(
                modifier = modifier.height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (node.leftDelimiter.isNotEmpty()) {
                    MathDelimiterView(symbol = node.leftDelimiter, color = color)
                }
                MathGrid(
                    rows = node.rows,
                    columnSpacing = 12.dp,
                    rowSpacing = 6.dp,
                    alignmentMode = MathGridAlignment.Center,
                    color = color,
                    fontSize = (fontSize.value * 0.94f).coerceAtLeast(11f).sp,
                    inheritedStyle = inheritedStyle,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
                if (node.rightDelimiter.isNotEmpty()) {
                    MathDelimiterView(symbol = node.rightDelimiter, color = color)
                }
            }
        }
        is MathNode.Cases -> {
            val gridRows = remember(node.rows) {
                node.rows.map { (expr, cond) ->
                    if (cond != null) listOf(expr, cond) else listOf(expr)
                }
            }
            Row(
                modifier = modifier.height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MathDelimiterView(symbol = "{", color = color)
                MathGrid(
                    rows = gridRows,
                    columnSpacing = 14.dp,
                    rowSpacing = 6.dp,
                    alignmentMode = MathGridAlignment.Start,
                    color = color,
                    fontSize = (fontSize.value * 0.94f).coerceAtLeast(11f).sp,
                    inheritedStyle = inheritedStyle,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp)
                )
            }
        }
        is MathNode.AlignedLines -> {
            MathGrid(
                rows = node.rows,
                columnSpacing = 6.dp,
                rowSpacing = 6.dp,
                alignmentMode = if (node.centered) MathGridAlignment.Center else MathGridAlignment.AlignedPairs,
                color = color,
                fontSize = fontSize,
                inheritedStyle = inheritedStyle,
                modifier = modifier
            )
        }
    }
}

@Composable
private fun MathRowView(
    children: List<MathNode>,
    modifier: Modifier,
    color: Color,
    fontSize: TextUnit,
    inheritedStyle: MathFontStyle?
) {
    if (children.isEmpty()) {
        Spacer(modifier = modifier.width(0.dp))
        return
    }
    if (children.all { isInlineBlockRunNode(it) }) {
        val annotated = remember(children, fontSize, inheritedStyle) {
            buildAnnotatedString {
                appendInlineMathRow(children, baseFontSize = fontSize, inheritedStyle = inheritedStyle, depth = 0)
            }
        }
        BasicText(
            text = annotated,
            modifier = modifier,
            style = TextStyle(
                color = color,
                fontSize = fontSize,
                lineHeight = fontSize * 1.25f,
                fontFamily = FontFamily.Serif
            )
        )
        return
    }

    val segments = remember(children) { segmentMathRow(children) }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (idx in segments.indices) {
            val seg = segments[idx]
            val prevSeg = segments.getOrNull(idx - 1)
            val prevNode = prevSeg?.lastNode
            val curNode = seg.firstNode
            if (prevNode != null && curNode != null) {
                val gap = computeSegmentGap(prevNode, curNode)
                if (gap > 0.dp) Spacer(modifier = Modifier.width(gap))
            }
            when (seg) {
                is MathRowSegment.InlineRun -> {
                    val annotated = remember(seg.nodes, fontSize, inheritedStyle, idx, segments.size) {
                        buildAnnotatedString {
                            appendInlineMathRow(
                                seg.nodes,
                                baseFontSize = fontSize,
                                inheritedStyle = inheritedStyle,
                                depth = 0
                            )
                        }
                    }
                    BasicText(
                        text = annotated,
                        style = TextStyle(
                            color = color,
                            fontSize = fontSize,
                            lineHeight = fontSize * 1.25f,
                            fontFamily = FontFamily.Serif
                        )
                    )
                }
                is MathRowSegment.BlockNode -> {
                    MathBlockView(
                        node = seg.node,
                        color = color,
                        fontSize = fontSize,
                        inheritedStyle = inheritedStyle
                    )
                }
            }
        }
    }
}

private sealed interface MathRowSegment {
    val firstNode: MathNode?
    val lastNode: MathNode?

    data class InlineRun(val nodes: List<MathNode>) : MathRowSegment {
        override val firstNode: MathNode? get() = nodes.firstOrNull()
        override val lastNode: MathNode? get() = nodes.lastOrNull()
    }

    data class BlockNode(val node: MathNode) : MathRowSegment {
        override val firstNode: MathNode get() = node
        override val lastNode: MathNode get() = node
    }
}

private fun segmentMathRow(children: List<MathNode>): List<MathRowSegment> {
    val out = mutableListOf<MathRowSegment>()
    val inlineBuf = mutableListOf<MathNode>()
    fun flushInline() {
        if (inlineBuf.isNotEmpty()) {
            out.add(MathRowSegment.InlineRun(inlineBuf.toList()))
            inlineBuf.clear()
        }
    }
    for (child in children) {
        if (isInlineBlockRunNode(child)) {
            inlineBuf.add(child)
        } else {
            flushInline()
            out.add(MathRowSegment.BlockNode(child))
        }
    }
    flushInline()
    return out
}

private fun computeSegmentGap(prev: MathNode, cur: MathNode): Dp {
    val prevSpacedOp = prev is MathNode.Operator && prev.spacing
    val curSpacedOp = cur is MathNode.Operator && cur.spacing
    if (prevSpacedOp || curSpacedOp) return 4.dp
    if (prev is MathNode.Text && (prev.text == "," || prev.text == ";")) return 4.dp
    if (inlineNeedsGap(prev, cur)) return 3.dp
    return 1.dp
}

private fun isInlineBlockRunNode(node: MathNode): Boolean = when (node) {
    is MathNode.Text -> true
    is MathNode.Operator -> !node.isLarge && !node.limitsUnderOver
    is MathNode.Styled -> isInlineBlockRunNode(node.content)
    is MathNode.Delimited -> !contains2DNode(node.content)
    is MathNode.Script -> {
        val baseInline = isInlineBlockRunNode(node.base) && !isLimitsUnderOverNode(node.base)
        val hasOnlyOneScript = (node.subscript == null) || (node.superscript == null)
        val subOk = node.subscript == null || isInlineBlockRunNode(node.subscript)
        val supOk = node.superscript == null || isInlineBlockRunNode(node.superscript)
        baseInline && hasOnlyOneScript && subOk && supOk
    }
    is MathNode.Row -> node.children.all { isInlineBlockRunNode(it) }
    else -> false
}

private fun contains2DNode(node: MathNode): Boolean = when (node) {
    is MathNode.Fraction,
    is MathNode.Radical,
    is MathNode.Matrix,
    is MathNode.Cases,
    is MathNode.AlignedLines,
    is MathNode.Accent -> true
    is MathNode.Operator -> node.isLarge || node.limitsUnderOver
    is MathNode.Row -> node.children.any { contains2DNode(it) }
    is MathNode.Styled -> contains2DNode(node.content)
    is MathNode.Delimited -> contains2DNode(node.content)
    is MathNode.Script -> contains2DNode(node.base) ||
        (node.subscript != null && contains2DNode(node.subscript)) ||
        (node.superscript != null && contains2DNode(node.superscript))
    is MathNode.Text -> false
}

private fun isLimitsUnderOverNode(node: MathNode): Boolean = when (node) {
    is MathNode.Operator -> node.limitsUnderOver
    is MathNode.Styled -> isLimitsUnderOverNode(node.content)
    else -> false
}

@Composable
private fun MathScriptView(
    node: MathNode.Script,
    modifier: Modifier,
    color: Color,
    fontSize: TextUnit,
    inheritedStyle: MathFontStyle?
) {
    val scriptFontSize = (fontSize.value * 0.70f).coerceAtLeast(9.5f).sp
    if (isLimitsUnderOverNode(node.base)) {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (node.superscript != null) {
                Box(modifier = Modifier.padding(bottom = 1.dp)) {
                    MathBlockView(
                        node = node.superscript,
                        color = color,
                        fontSize = scriptFontSize,
                        inheritedStyle = inheritedStyle
                    )
                }
            }
            MathBlockView(
                node = node.base,
                color = color,
                fontSize = fontSize,
                inheritedStyle = inheritedStyle
            )
            if (node.subscript != null) {
                Box(modifier = Modifier.padding(top = 1.dp)) {
                    MathBlockView(
                        node = node.subscript,
                        color = color,
                        fontSize = scriptFontSize,
                        inheritedStyle = inheritedStyle
                    )
                }
            }
        }
        return
    }

    if (isInlineBlockRunNode(node)) {
        val annotated = remember(node, fontSize, inheritedStyle) {
            buildAnnotatedString {
                appendInlineMath(node, baseFontSize = fontSize, inheritedStyle = inheritedStyle)
            }
        }
        BasicText(
            text = annotated,
            modifier = modifier,
            style = TextStyle(
                color = color,
                fontSize = fontSize,
                lineHeight = fontSize * 1.25f,
                fontFamily = FontFamily.Serif
            )
        )
        return
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        MathBlockView(
            node = node.base,
            color = color,
            fontSize = fontSize,
            inheritedStyle = inheritedStyle
        )
        Column(
            modifier = Modifier.padding(start = 1.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (node.superscript != null) {
                Box(
                    modifier = Modifier.padding(
                        bottom = if (node.subscript == null) 8.dp else 1.dp
                    )
                ) {
                    MathBlockView(
                        node = node.superscript,
                        color = color,
                        fontSize = scriptFontSize,
                        inheritedStyle = inheritedStyle
                    )
                }
            }
            if (node.subscript != null) {
                Box(
                    modifier = Modifier.padding(
                        top = if (node.superscript == null) 8.dp else 1.dp
                    )
                ) {
                    MathBlockView(
                        node = node.subscript,
                        color = color,
                        fontSize = scriptFontSize,
                        inheritedStyle = inheritedStyle
                    )
                }
            }
        }
    }
}

@Composable
private fun MathAccentView(
    node: MathNode.Accent,
    modifier: Modifier,
    color: Color,
    fontSize: TextUnit,
    inheritedStyle: MathFontStyle?
) {
    Column(
        modifier = modifier.width(IntrinsicSize.Max),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when (node.kind) {
            MathAccentKind.OVERLINE, MathAccentKind.BAR -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = if (node.kind == MathAccentKind.BAR) 2.dp else 0.dp,
                            vertical = 1.dp
                        )
                        .height(1.dp)
                        .background(color)
                )
            }
            MathAccentKind.VEC, MathAccentKind.ARROW_RIGHT -> {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                ) {
                    val stroke = 1.1.dp.toPx()
                    val y = size.height * 0.55f
                    val startX = 1.dp.toPx()
                    val endX = (size.width - 1.dp.toPx()).coerceAtLeast(startX + 4f)
                    drawLine(
                        color = color,
                        start = Offset(startX, y),
                        end = Offset(endX, y),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round
                    )
                    val head = 2.6.dp.toPx()
                    val path = Path().apply {
                        moveTo(endX - head, y - head * 0.7f)
                        lineTo(endX, y)
                        lineTo(endX - head, y + head * 0.7f)
                    }
                    drawPath(
                        path = path,
                        color = color,
                        style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
            }
            MathAccentKind.HAT, MathAccentKind.WIDEHAT -> {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                ) {
                    val stroke = 1.1.dp.toPx()
                    val margin = if (node.kind == MathAccentKind.HAT) size.width * 0.18f else 0f
                    val path = Path().apply {
                        moveTo(margin, size.height - stroke * 0.5f)
                        lineTo(size.width * 0.5f, stroke * 0.5f)
                        lineTo(size.width - margin, size.height - stroke * 0.5f)
                    }
                    drawPath(
                        path = path,
                        color = color,
                        style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
            }
            MathAccentKind.TILDE -> {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                ) {
                    val stroke = 1.1.dp.toPx()
                    val midY = size.height * 0.5f
                    val path = Path().apply {
                        moveTo(1f, midY)
                        cubicTo(
                            size.width * 0.25f, 0f,
                            size.width * 0.35f, size.height,
                            size.width * 0.5f, midY
                        )
                        cubicTo(
                            size.width * 0.65f, 0f,
                            size.width * 0.75f, size.height,
                            size.width - 1f, midY
                        )
                    }
                    drawPath(
                        path = path,
                        color = color,
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                }
            }
            MathAccentKind.DOT, MathAccentKind.DDOT -> {
                Canvas(
                    modifier = Modifier
                        .widthIn(min = 10.dp)
                        .fillMaxWidth()
                        .height(4.dp)
                ) {
                    val radius = 1.15.dp.toPx()
                    val cy = size.height * 0.5f
                    if (node.kind == MathAccentKind.DOT) {
                        drawCircle(color = color, radius = radius, center = Offset(size.width * 0.5f, cy))
                    } else {
                        val offset = 2.2.dp.toPx()
                        drawCircle(color = color, radius = radius, center = Offset(size.width * 0.5f - offset, cy))
                        drawCircle(color = color, radius = radius, center = Offset(size.width * 0.5f + offset, cy))
                    }
                }
            }
            MathAccentKind.UNDERLINE -> Unit
        }

        MathBlockView(
            node = node.base,
            color = color,
            fontSize = fontSize,
            inheritedStyle = inheritedStyle
        )

        if (node.kind == MathAccentKind.UNDERLINE) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 1.dp)
                    .height(1.dp)
                    .background(color)
            )
        }
    }
}

@Composable
private fun MathDelimiterView(
    symbol: String,
    color: Color
) {
    val width = when (symbol) {
        "|", "∣" -> 5.dp
        "‖" -> 8.dp
        "[", "]" -> 7.dp
        "{", "}" -> 10.dp
        "(", ")", "⟨", "⟩", "⌊", "⌋", "⌈", "⌉" -> 9.dp
        else -> 8.dp
    }
    Canvas(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
    ) {
        val w = size.width
        val h = size.height
        val stroke = 1.3.dp.toPx()
        val top = 1.dp.toPx()
        val bottom = (h - 1.dp.toPx()).coerceAtLeast(top + 4f)
        val midY = (top + bottom) * 0.5f

        when (symbol) {
            "(" -> {
                val path = Path().apply {
                    moveTo(w * 0.85f, top)
                    quadraticTo(w * 0.1f, midY, w * 0.85f, bottom)
                }
                drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
            }
            ")" -> {
                val path = Path().apply {
                    moveTo(w * 0.15f, top)
                    quadraticTo(w * 0.9f, midY, w * 0.15f, bottom)
                }
                drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
            }
            "[" -> {
                val path = Path().apply {
                    moveTo(w * 0.85f, top)
                    lineTo(w * 0.25f, top)
                    lineTo(w * 0.25f, bottom)
                    lineTo(w * 0.85f, bottom)
                }
                drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Square))
            }
            "]" -> {
                val path = Path().apply {
                    moveTo(w * 0.15f, top)
                    lineTo(w * 0.75f, top)
                    lineTo(w * 0.75f, bottom)
                    lineTo(w * 0.15f, bottom)
                }
                drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Square))
            }
            "{" -> {
                val path = Path().apply {
                    moveTo(w * 0.85f, top)
                    cubicTo(w * 0.40f, top, w * 0.55f, midY - 2f, w * 0.12f, midY)
                    cubicTo(w * 0.55f, midY + 2f, w * 0.40f, bottom, w * 0.85f, bottom)
                }
                drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
            }
            "}" -> {
                val path = Path().apply {
                    moveTo(w * 0.15f, top)
                    cubicTo(w * 0.60f, top, w * 0.45f, midY - 2f, w * 0.88f, midY)
                    cubicTo(w * 0.45f, midY + 2f, w * 0.60f, bottom, w * 0.15f, bottom)
                }
                drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
            }
            "|", "∣" -> {
                drawLine(color, Offset(w * 0.5f, top), Offset(w * 0.5f, bottom), strokeWidth = stroke)
            }
            "‖" -> {
                drawLine(color, Offset(w * 0.32f, top), Offset(w * 0.32f, bottom), strokeWidth = stroke)
                drawLine(color, Offset(w * 0.68f, top), Offset(w * 0.68f, bottom), strokeWidth = stroke)
            }
            "⟨" -> {
                val path = Path().apply {
                    moveTo(w * 0.82f, top)
                    lineTo(w * 0.18f, midY)
                    lineTo(w * 0.82f, bottom)
                }
                drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            "⟩" -> {
                val path = Path().apply {
                    moveTo(w * 0.18f, top)
                    lineTo(w * 0.82f, midY)
                    lineTo(w * 0.18f, bottom)
                }
                drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            "⌊" -> {
                val path = Path().apply {
                    moveTo(w * 0.25f, top)
                    lineTo(w * 0.25f, bottom)
                    lineTo(w * 0.85f, bottom)
                }
                drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Square))
            }
            "⌋" -> {
                val path = Path().apply {
                    moveTo(w * 0.75f, top)
                    lineTo(w * 0.75f, bottom)
                    lineTo(w * 0.15f, bottom)
                }
                drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Square))
            }
            "⌈" -> {
                val path = Path().apply {
                    moveTo(w * 0.85f, top)
                    lineTo(w * 0.25f, top)
                    lineTo(w * 0.25f, bottom)
                }
                drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Square))
            }
            "⌉" -> {
                val path = Path().apply {
                    moveTo(w * 0.15f, top)
                    lineTo(w * 0.75f, top)
                    lineTo(w * 0.75f, bottom)
                }
                drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Square))
            }
            else -> {
                drawLine(color, Offset(w * 0.5f, top), Offset(w * 0.5f, bottom), strokeWidth = stroke)
            }
        }
    }
}

private enum class MathGridAlignment {
    Center,
    Start,
    AlignedPairs
}

@Composable
private fun MathGrid(
    rows: List<List<MathNode>>,
    columnSpacing: Dp,
    rowSpacing: Dp,
    alignmentMode: MathGridAlignment,
    color: Color,
    fontSize: TextUnit,
    inheritedStyle: MathFontStyle?,
    modifier: Modifier = Modifier
) {
    val numRows = rows.size
    val numCols = rows.maxOfOrNull { it.size } ?: 0
    if (numRows == 0 || numCols == 0) {
        Spacer(modifier = modifier.size(0.dp))
        return
    }

    val policy = remember(numRows, numCols, columnSpacing, rowSpacing, alignmentMode) {
        object : MeasurePolicy {
            override fun MeasureScope.measure(
                measurables: List<Measurable>,
                constraints: Constraints
            ): MeasureResult {
                val colGapPx = columnSpacing.roundToPx()
                val rowGapPx = rowSpacing.roundToPx()
                val childConstraints = Constraints()
                val placeables = measurables.map { it.measure(childConstraints) }

                val colWidths = IntArray(numCols)
                val rowHeights = IntArray(numRows)
                for (r in 0 until numRows) {
                    for (c in 0 until numCols) {
                        val p = placeables[r * numCols + c]
                        colWidths[c] = max(colWidths[c], p.width)
                        rowHeights[r] = max(rowHeights[r], p.height)
                    }
                }
                val totalW = colWidths.sum() + colGapPx * (numCols - 1).coerceAtLeast(0)
                val totalH = rowHeights.sum() + rowGapPx * (numRows - 1).coerceAtLeast(0)

                return layout(totalW, totalH) {
                    var y = 0
                    for (r in 0 until numRows) {
                        var x = 0
                        val rowH = rowHeights[r]
                        for (c in 0 until numCols) {
                            val colW = colWidths[c]
                            val p = placeables[r * numCols + c]
                            val dx = when (alignmentMode) {
                                MathGridAlignment.Center -> (colW - p.width) / 2
                                MathGridAlignment.Start -> 0
                                MathGridAlignment.AlignedPairs -> if (c % 2 == 0) colW - p.width else 0
                            }
                            val dy = (rowH - p.height) / 2
                            p.placeRelative(x + dx, y + dy)
                            x += colW + colGapPx
                        }
                        y += rowH + rowGapPx
                    }
                }
            }

            override fun IntrinsicMeasureScope.minIntrinsicWidth(
                measurables: List<IntrinsicMeasurable>,
                height: Int
            ): Int = gridIntrinsicWidth(measurables, height, isMax = false)

            override fun IntrinsicMeasureScope.maxIntrinsicWidth(
                measurables: List<IntrinsicMeasurable>,
                height: Int
            ): Int = gridIntrinsicWidth(measurables, height, isMax = true)

            override fun IntrinsicMeasureScope.minIntrinsicHeight(
                measurables: List<IntrinsicMeasurable>,
                width: Int
            ): Int = gridIntrinsicHeight(measurables, width, isMax = false)

            override fun IntrinsicMeasureScope.maxIntrinsicHeight(
                measurables: List<IntrinsicMeasurable>,
                width: Int
            ): Int = gridIntrinsicHeight(measurables, width, isMax = true)

            private fun IntrinsicMeasureScope.gridIntrinsicWidth(
                measurables: List<IntrinsicMeasurable>,
                height: Int,
                isMax: Boolean
            ): Int {
                val colWidths = IntArray(numCols)
                for (r in 0 until numRows) {
                    for (c in 0 until numCols) {
                        val m = measurables[r * numCols + c]
                        val w = if (isMax) m.maxIntrinsicWidth(height) else m.minIntrinsicWidth(height)
                        colWidths[c] = max(colWidths[c], w)
                    }
                }
                return colWidths.sum() + columnSpacing.roundToPx() * (numCols - 1).coerceAtLeast(0)
            }

            private fun IntrinsicMeasureScope.gridIntrinsicHeight(
                measurables: List<IntrinsicMeasurable>,
                width: Int,
                isMax: Boolean
            ): Int {
                val rowHeights = IntArray(numRows)
                for (r in 0 until numRows) {
                    for (c in 0 until numCols) {
                        val m = measurables[r * numCols + c]
                        val h = if (isMax) m.maxIntrinsicHeight(width) else m.minIntrinsicHeight(width)
                        rowHeights[r] = max(rowHeights[r], h)
                    }
                }
                return rowHeights.sum() + rowSpacing.roundToPx() * (numRows - 1).coerceAtLeast(0)
            }
        }
    }

    Layout(
        modifier = modifier,
        content = {
            for (r in 0 until numRows) {
                val row = rows[r]
                for (c in 0 until numCols) {
                    val cell = row.getOrNull(c)
                    if (cell != null) {
                        MathBlockView(
                            node = cell,
                            color = color,
                            fontSize = fontSize,
                            inheritedStyle = inheritedStyle
                        )
                    } else {
                        Spacer(modifier = Modifier.size(0.dp))
                    }
                }
            }
        },
        measurePolicy = policy
    )
}
