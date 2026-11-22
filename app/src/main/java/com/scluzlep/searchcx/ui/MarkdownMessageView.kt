package com.scluzlep.searchcx.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
                                onLinkClick = { runCatching { uriHandler.openUri(it) } }
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
                                onLinkClick = { runCatching { uriHandler.openUri(it) } }
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
                                    .padding(horizontal = 12.dp, vertical = 9.dp)
                            ) {
                                BasicText(
                                    text = block.pretty,
                                    style = TextStyle(
                                        color = Color(0xFF1E3A8A),
                                        fontSize = 14.sp,
                                        lineHeight = 20.sp,
                                        fontFamily = FontFamily.Serif,
                                        fontWeight = FontWeight.Medium
                                    )
                                )
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
                                    onLinkClick = { runCatching { uriHandler.openUri(it) } }
                                )
                            }
                        }
                        is MdBlock.BulletItem -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                BasicText(
                                    text = "•",
                                    style = TextStyle(
                                        color = accentColor,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
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
                                    onLinkClick = { runCatching { uriHandler.openUri(it) } }
                                )
                            }
                        }
                        is MdBlock.NumberedItem -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
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
                                    onLinkClick = { runCatching { uriHandler.openUri(it) } }
                                )
                            }
                        }
                        is MdBlock.Table -> {
                            TableCard(headers = block.headers, rows = block.rows)
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
                text = if (isStreaming) "思考中 (${reasoning.length} 字)..." else "思考过程 (${reasoning.length} 字)",
                style = TextStyle(
                    color = Color(0xFF4B5563),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            )
            BasicText(
                text = if (expanded) "收起 ▲" else "展开 ▼",
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
private fun TableCard(headers: List<String>, rows: List<List<String>>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFF9FAFB))
            .horizontalScroll(rememberScrollState())
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            headers.forEach { h ->
                BasicText(
                    text = h,
                    style = TextStyle(
                        color = Color(0xFF111827),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    modifier = Modifier.width(108.dp)
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFE5E7EB)))
        rows.forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                headers.indices.forEach { idx ->
                    BasicText(
                        text = r.getOrElse(idx) { "" },
                        style = TextStyle(color = Color(0xFF374151), fontSize = 12.sp),
                        modifier = Modifier.width(108.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun InlineRichText(
    inlines: List<MdInline>,
    baseStyle: TextStyle,
    accentColor: Color,
    onLinkClick: (String) -> Unit
) {
    val annotated: AnnotatedString = remember(inlines, accentColor) {
        buildAnnotatedString {
            for (inline in inlines) {
                when (inline) {
                    is MdInline.Plain -> append(inline.text)
                    is MdInline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inline.text) }
                    is MdInline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inline.text) }
                    is MdInline.Strike -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        append(inline.text)
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
                        append(" ${inline.pretty} ")
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
    }
    BasicText(text = annotated, style = baseStyle)
}
