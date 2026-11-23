package com.scluzlep.searchcx.ui

enum class MathFontStyle {
    Normal,
    Italic,
    Bold,
    BoldItalic,
    Upright,
    Calligraphic,
    Function
}

enum class MathAccentKind {
    OVERLINE,
    BAR,
    VEC,
    ARROW_RIGHT,
    HAT,
    WIDEHAT,
    TILDE,
    DOT,
    DDOT,
    UNDERLINE
}

sealed interface MathNode {
    data class Row(val children: List<MathNode>) : MathNode
    data class Text(val text: String, val style: MathFontStyle = MathFontStyle.Normal) : MathNode
    data class Operator(
        val symbol: String,
        val isLarge: Boolean = false,
        val limitsUnderOver: Boolean = false,
        val spacing: Boolean = true
    ) : MathNode
    data class Fraction(
        val numerator: MathNode,
        val denominator: MathNode,
        val hasBar: Boolean = true
    ) : MathNode
    data class Radical(val radicand: MathNode, val degree: MathNode? = null) : MathNode
    data class Script(
        val base: MathNode,
        val subscript: MathNode? = null,
        val superscript: MathNode? = null
    ) : MathNode
    data class Accent(val base: MathNode, val kind: MathAccentKind) : MathNode
    data class Styled(val content: MathNode, val style: MathFontStyle) : MathNode
    data class Delimited(val left: String, val right: String, val content: MathNode) : MathNode
    data class Matrix(
        val rows: List<List<MathNode>>,
        val leftDelimiter: String,
        val rightDelimiter: String
    ) : MathNode
    data class Cases(val rows: List<Pair<MathNode, MathNode?>>) : MathNode
    data class AlignedLines(val rows: List<List<MathNode>>, val centered: Boolean = false) : MathNode
}

sealed interface MdInline {
    data class Plain(val text: String) : MdInline
    data class Bold(val text: String, val children: List<MdInline> = emptyList()) : MdInline
    data class Italic(val text: String, val children: List<MdInline> = emptyList()) : MdInline
    data class BoldItalic(val text: String, val children: List<MdInline> = emptyList()) : MdInline
    data class Strike(val text: String, val children: List<MdInline> = emptyList()) : MdInline
    data class InlineCode(val code: String) : MdInline
    data class InlineMath(
        val pretty: String,
        val raw: String,
        val node: MathNode = MarkdownParser.parseLatexMath(raw)
    ) : MdInline
    data class Link(val label: String, val url: String) : MdInline
}

sealed interface MdBlock {
    data class Heading(val level: Int, val inlines: List<MdInline>) : MdBlock
    data class Paragraph(val inlines: List<MdInline>) : MdBlock
    data class CodeBlock(val language: String, val code: String, val closed: Boolean) : MdBlock
    data class MathBlock(
        val pretty: String,
        val raw: String,
        val node: MathNode = MarkdownParser.parseLatexMath(raw)
    ) : MdBlock
    data class BlockQuote(val inlines: List<MdInline>) : MdBlock
    data class BulletItem(
        val inlines: List<MdInline>,
        val indentLevel: Int = 0,
        val checked: Boolean? = null
    ) : MdBlock
    data class NumberedItem(
        val number: String,
        val inlines: List<MdInline>,
        val indentLevel: Int = 0
    ) : MdBlock
    data class Table(
        val headers: List<String>,
        val rows: List<List<String>>,
        val headerInlines: List<List<MdInline>> = headers.map { MarkdownParser.parseInlines(it) },
        val rowInlines: List<List<List<MdInline>>> = rows.map { row ->
            row.map { MarkdownParser.parseInlines(it) }
        }
    ) : MdBlock
    data object Divider : MdBlock
}

/**
 * Pure Kotlin Markdown + LaTeX math + `<think>` block parser.
 * Designed to stay Android-free so every parser rule is covered by fast JVM unit tests.
 */
object MarkdownParser {

    fun splitThinkTags(raw: String): Pair<String, String> {
        if (!raw.contains("<think>")) return "" to raw
        val reasoning = StringBuilder()
        val visible = StringBuilder()
        var cursor = 0
        while (cursor < raw.length) {
            val start = raw.indexOf("<think>", cursor)
            if (start < 0) {
                visible.append(raw.substring(cursor))
                break
            }
            if (start > cursor) visible.append(raw.substring(cursor, start))
            val contentStart = start + "<think>".length
            val end = raw.indexOf("</think>", contentStart)
            if (end < 0) {
                reasoning.append(raw.substring(contentStart))
                break
            }
            if (reasoning.isNotEmpty()) reasoning.append('\n')
            reasoning.append(raw.substring(contentStart, end))
            cursor = end + "</think>".length
        }
        return reasoning.toString().trim() to visible.toString().trimStart('\r', '\n')
    }

    fun parse(markdown: String): List<MdBlock> {
        if (markdown.isBlank()) return emptyList()
        val lines = markdown.replace("\r\n", "\n").split('\n')
        val blocks = mutableListOf<MdBlock>()
        var i = 0
        val paragraphBuf = mutableListOf<String>()

        fun flushParagraph() {
            if (paragraphBuf.isNotEmpty()) {
                val joined = paragraphBuf.joinToString("\n")
                blocks.add(MdBlock.Paragraph(parseInlines(joined)))
                paragraphBuf.clear()
            }
        }

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            // 1. Fenced code / math blocks ```lang
            if (trimmed.startsWith("```")) {
                flushParagraph()
                val lang = trimmed.removePrefix("```").trim()
                val codeLines = mutableListOf<String>()
                var closed = false
                i++
                while (i < lines.size) {
                    if (lines[i].trim().startsWith("```")) {
                        closed = true
                        i++
                        break
                    }
                    codeLines.add(lines[i])
                    i++
                }
                val body = codeLines.joinToString("\n")
                if (lang.equals("math", ignoreCase = true) || lang.equals("latex", ignoreCase = true)) {
                    val rawMath = body.trim()
                    if (rawMath.isNotEmpty()) {
                        blocks.add(buildMathBlock(rawMath))
                    }
                } else {
                    blocks.add(MdBlock.CodeBlock(lang, body, closed))
                }
                continue
            }

            // 2. Display math blocks $$...$$ or \[...\]
            if (trimmed.startsWith("$$") || trimmed.startsWith("\\[")) {
                val isDollar = trimmed.startsWith("$$")
                val openTok = if (isDollar) "$$" else "\\["
                val closeTok = if (isDollar) "$$" else "\\]"
                val afterOpen = trimmed.removePrefix(openTok)
                val sameLineClose = afterOpen.indexOf(closeTok)
                if (sameLineClose >= 0) {
                    val trailing = afterOpen.substring(sameLineClose + closeTok.length).trim()
                    if (trailing.isNotEmpty() && isDollar) {
                        // Mid-line $$...$$ followed by prose belongs in a paragraph.
                        paragraphBuf.add(line)
                        i++
                        continue
                    }
                    flushParagraph()
                    val inner = afterOpen.substring(0, sameLineClose).trim()
                    blocks.add(buildMathBlock(inner))
                    i++
                    if (trailing.isNotEmpty()) {
                        paragraphBuf.add(trailing)
                    }
                    continue
                }

                flushParagraph()
                val mathLines = mutableListOf<String>()
                if (afterOpen.isNotBlank()) mathLines.add(afterOpen)
                i++
                var trailingAfterBlock = ""
                while (i < lines.size) {
                    val cur = lines[i]
                    val closeIdx = cur.indexOf(closeTok)
                    if (closeIdx >= 0) {
                        val before = cur.substring(0, closeIdx).trimEnd()
                        if (before.isNotBlank()) mathLines.add(before)
                        trailingAfterBlock = cur.substring(closeIdx + closeTok.length).trim()
                        i++
                        break
                    }
                    mathLines.add(cur)
                    i++
                }
                val rawMath = mathLines.joinToString("\n").trim()
                blocks.add(buildMathBlock(rawMath))
                if (trailingAfterBlock.isNotEmpty()) {
                    paragraphBuf.add(trailingAfterBlock)
                }
                continue
            }

            // 3. Standalone LaTeX display math environment \begin{equation|align|aligned|gather|cases|...}
            val envMatch = BLOCK_MATH_ENV_REGEX.find(trimmed)
            if (envMatch != null && trimmed.startsWith("\\begin{")) {
                val envName = envMatch.groupValues[1]
                if (envName in BLOCK_MATH_ENVIRONMENTS) {
                    flushParagraph()
                    val collected = mutableListOf<String>()
                    var depth = 0
                    while (i < lines.size) {
                        val cur = lines[i]
                        collected.add(cur)
                        depth += countEnvOccurrences(cur, "\\begin{$envName}")
                        depth -= countEnvOccurrences(cur, "\\end{$envName}")
                        i++
                        if (depth <= 0) break
                    }
                    val rawMath = collected.joinToString("\n").trim()
                    blocks.add(buildMathBlock(rawMath))
                    continue
                }
            }

            // 4. Blank line
            if (trimmed.isEmpty()) {
                flushParagraph()
                i++
                continue
            }

            // 5. Horizontal rule
            if (DIVIDER_REGEX.matches(trimmed)) {
                flushParagraph()
                blocks.add(MdBlock.Divider)
                i++
                continue
            }

            // 6. Headings # .. ######
            val headingMatch = HEADING_REGEX.matchEntire(trimmed)
            if (headingMatch != null) {
                flushParagraph()
                val level = headingMatch.groupValues[1].length
                val titleText = headingMatch.groupValues[2].trim()
                blocks.add(MdBlock.Heading(level, parseInlines(titleText)))
                i++
                continue
            }

            // 7. Markdown table
            if (i + 1 < lines.size &&
                trimmed.startsWith("|") &&
                TABLE_SEP_REGEX.matches(lines[i + 1].trim())
            ) {
                flushParagraph()
                val headers = parseTableRow(trimmed)
                i += 2
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    rows.add(parseTableRow(lines[i].trim()))
                    i++
                }
                blocks.add(
                    MdBlock.Table(
                        headers = headers,
                        rows = rows,
                        headerInlines = headers.map { parseInlines(it) },
                        rowInlines = rows.map { row -> row.map { parseInlines(it) } }
                    )
                )
                continue
            }

            // 8. Blockquote
            if (trimmed.startsWith(">")) {
                flushParagraph()
                val quoteLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    quoteLines.add(lines[i].trim().removePrefix(">").trimStart())
                    i++
                }
                blocks.add(MdBlock.BlockQuote(parseInlines(quoteLines.joinToString("\n"))))
                continue
            }

            // 9. Unordered list item / Task list item (supports indentation)
            val bulletMatch = BULLET_REGEX.matchEntire(line)
            if (bulletMatch != null) {
                flushParagraph()
                val indentLevel = computeIndentLevel(bulletMatch.groupValues[1])
                val body = bulletMatch.groupValues[2]
                val taskMatch = TASK_PREFIX_REGEX.matchEntire(body)
                if (taskMatch != null) {
                    val checked = taskMatch.groupValues[1].equals("x", ignoreCase = true)
                    blocks.add(
                        MdBlock.BulletItem(
                            inlines = parseInlines(taskMatch.groupValues[2]),
                            indentLevel = indentLevel,
                            checked = checked
                        )
                    )
                } else {
                    blocks.add(
                        MdBlock.BulletItem(
                            inlines = parseInlines(body),
                            indentLevel = indentLevel,
                            checked = null
                        )
                    )
                }
                i++
                continue
            }

            // 10. Ordered list item (supports indentation)
            val numMatch = NUMBERED_REGEX.matchEntire(line)
            if (numMatch != null) {
                flushParagraph()
                val indentLevel = computeIndentLevel(numMatch.groupValues[1])
                blocks.add(
                    MdBlock.NumberedItem(
                        number = numMatch.groupValues[2],
                        inlines = parseInlines(numMatch.groupValues[3]),
                        indentLevel = indentLevel
                    )
                )
                i++
                continue
            }

            paragraphBuf.add(line)
            i++
        }

        flushParagraph()
        return blocks
    }

    fun parseInlines(input: String): List<MdInline> = parseInlinesInternal(input, depth = 0)

    private fun parseInlinesInternal(input: String, depth: Int): List<MdInline> {
        val out = mutableListOf<MdInline>()
        val plain = StringBuilder()

        fun flushPlain() {
            if (plain.isNotEmpty()) {
                out.add(MdInline.Plain(plain.toString()))
                plain.setLength(0)
            }
        }

        var i = 0
        while (i < input.length) {
            // Inline code `...`
            if (input[i] == '`' && !isEscaped(input, i)) {
                val end = input.indexOf('`', i + 1)
                if (end > i) {
                    flushPlain()
                    out.add(MdInline.InlineCode(input.substring(i + 1, end)))
                    i = end + 1
                    continue
                }
            }

            // Inline double-dollar math $$...$$ mid-line
            if (input.startsWith("$$", i) && !isEscaped(input, i)) {
                val end = findClosingDoubleDollar(input, i + 2)
                if (end > i + 2) {
                    flushPlain()
                    val raw = input.substring(i + 2, end).trim()
                    out.add(buildInlineMath(raw))
                    i = end + 2
                    continue
                }
            }

            // Inline math $...$ with currency safety
            if (input[i] == '$' && !isEscaped(input, i) && input.getOrNull(i + 1) != '$') {
                val end = findClosingMathDollar(input, i + 1)
                if (end > i + 1) {
                    flushPlain()
                    val raw = input.substring(i + 1, end).trim()
                    out.add(buildInlineMath(raw))
                    i = end + 1
                    continue
                }
            }

            // Inline math \(...\)
            if (input.startsWith("\\(", i) && !isEscaped(input, i)) {
                val end = input.indexOf("\\)", i + 2)
                if (end > i + 2) {
                    flushPlain()
                    val raw = input.substring(i + 2, end).trim()
                    out.add(buildInlineMath(raw))
                    i = end + 2
                    continue
                }
            }

            // Escaped Markdown punctuation outside math/code (e.g. \$100, \*, \_)
            if (input[i] == '\\' && i + 1 < input.length && input[i + 1] in ESCAPABLE_MD_CHARS) {
                plain.append(input[i + 1])
                i += 2
                continue
            }

            // Link [label](url)
            if (input[i] == '[') {
                val closeBracket = input.indexOf(']', i + 1)
                if (closeBracket > i && input.getOrNull(closeBracket + 1) == '(') {
                    val closeParen = findBalancedClosingParen(input, closeBracket + 2)
                    if (closeParen > closeBracket + 1) {
                        flushPlain()
                        out.add(
                            MdInline.Link(
                                label = input.substring(i + 1, closeBracket),
                                url = input.substring(closeBracket + 2, closeParen)
                            )
                        )
                        i = closeParen + 1
                        continue
                    }
                }
            }

            if (depth < 4) {
                // Bold+Italic ***...***
                if (input.startsWith("***", i)) {
                    val end = findClosingMdDelimiter(input, i + 3, "***")
                    if (end > i + 3) {
                        flushPlain()
                        val inner = input.substring(i + 3, end)
                        out.add(MdInline.BoldItalic(inner, parseInlinesInternal(inner, depth + 1)))
                        i = end + 3
                        continue
                    }
                }

                // Bold **...**
                if (input.startsWith("**", i)) {
                    val end = findClosingMdDelimiter(input, i + 2, "**")
                    if (end > i + 2) {
                        flushPlain()
                        val inner = input.substring(i + 2, end)
                        out.add(MdInline.Bold(inner, parseInlinesInternal(inner, depth + 1)))
                        i = end + 2
                        continue
                    }
                }

                // Bold __...__
                if (input.startsWith("__", i)) {
                    val end = findClosingMdDelimiter(input, i + 2, "__")
                    if (end > i + 2) {
                        flushPlain()
                        val inner = input.substring(i + 2, end)
                        out.add(MdInline.Bold(inner, parseInlinesInternal(inner, depth + 1)))
                        i = end + 2
                        continue
                    }
                }

                // Strikethrough ~~...~~
                if (input.startsWith("~~", i)) {
                    val end = findClosingMdDelimiter(input, i + 2, "~~")
                    if (end > i + 2) {
                        flushPlain()
                        val inner = input.substring(i + 2, end)
                        out.add(MdInline.Strike(inner, parseInlinesInternal(inner, depth + 1)))
                        i = end + 2
                        continue
                    }
                }

                // Italic *...*
                if (input[i] == '*' && input.getOrNull(i + 1) != '*' && !input.getOrNull(i + 1).isWhitespaceOrNull()) {
                    val end = findClosingSingleAsterisk(input, i + 1)
                    if (end > i + 1) {
                        flushPlain()
                        val inner = input.substring(i + 1, end)
                        out.add(MdInline.Italic(inner, parseInlinesInternal(inner, depth + 1)))
                        i = end + 1
                        continue
                    }
                }
            }

            plain.append(input[i])
            i++
        }
        flushPlain()
        return out
    }

    fun parseLatexMath(raw: String): MathNode {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return MathNode.Row(emptyList())
        return LatexMathParser(trimmed).parseDocument()
    }

    fun prettifyLatexMath(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        return formatMathPretty(parseLatexMath(trimmed))
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
    }

    fun formatMathPretty(node: MathNode, inScript: Boolean = false): String = when (node) {
        is MathNode.Row -> formatRowPretty(node.children, inScript)
        is MathNode.Text -> node.text
        is MathNode.Operator -> node.symbol
        is MathNode.Fraction -> {
            val num = formatMathPretty(node.numerator, inScript).trim()
            val den = formatMathPretty(node.denominator, inScript).trim()
            if (node.hasBar) "($num)/($den)" else "binom($num, $den)"
        }
        is MathNode.Radical -> {
            val rad = formatMathPretty(node.radicand, inScript).trim()
            if (node.degree != null) {
                val deg = formatMathPretty(node.degree, inScript = true).trim()
                val degPrefix = formatScriptValuePretty(deg, isSuper = true).let {
                    if (it.startsWith("^")) "($deg)" else it
                }
                "${degPrefix}√($rad)"
            } else {
                "√($rad)"
            }
        }
        is MathNode.Script -> {
            val baseStr = formatMathPretty(node.base, inScript)
            val subStr = node.subscript?.let {
                formatScriptValuePretty(formatMathPretty(it, inScript = true).trim(), isSuper = false)
            }.orEmpty()
            val supStr = node.superscript?.let {
                formatScriptValuePretty(formatMathPretty(it, inScript = true).trim(), isSuper = true)
            }.orEmpty()
            baseStr + subStr + supStr
        }
        is MathNode.Accent -> {
            val inner = formatMathPretty(node.base, inScript).trim()
            when (node.kind) {
                MathAccentKind.BAR, MathAccentKind.OVERLINE -> {
                    if (inner.length == 1) "$inner\u0304" else "overline($inner)"
                }
                MathAccentKind.VEC, MathAccentKind.ARROW_RIGHT -> {
                    if (inner.length == 1) "$inner\u20D7" else "vec($inner)"
                }
                MathAccentKind.HAT, MathAccentKind.WIDEHAT -> {
                    if (inner.length == 1) "$inner\u0302" else "hat($inner)"
                }
                MathAccentKind.TILDE -> {
                    if (inner.length == 1) "$inner\u0303" else "tilde($inner)"
                }
                MathAccentKind.DOT -> {
                    if (inner.length == 1) "$inner\u0307" else "dot($inner)"
                }
                MathAccentKind.DDOT -> {
                    if (inner.length == 1) "$inner\u0308" else "ddot($inner)"
                }
                MathAccentKind.UNDERLINE -> {
                    if (inner.length == 1) "$inner\u0332" else "underline($inner)"
                }
            }
        }
        is MathNode.Styled -> formatMathPretty(node.content, inScript)
        is MathNode.Delimited -> {
            val inner = formatMathPretty(node.content, inScript).trim()
            "${node.left}$inner${node.right}"
        }
        is MathNode.Matrix -> {
            val rowStrs = node.rows.map { row ->
                row.joinToString(", ") { formatMathPretty(it, inScript).trim() }
            }
            val joined = rowStrs.joinToString("; ")
            val l = node.leftDelimiter.ifEmpty { "[" }
            val r = node.rightDelimiter.ifEmpty { "]" }
            "$l$joined$r"
        }
        is MathNode.Cases -> {
            val rows = node.rows.joinToString("; ") { (expr, cond) ->
                val e = formatMathPretty(expr, inScript).trim()
                val c = cond?.let { formatMathPretty(it, inScript).trim() }.orEmpty()
                if (c.isNotEmpty()) "$e ($c)" else e
            }
            "{ $rows }"
        }
        is MathNode.AlignedLines -> {
            node.rows.joinToString("\n") { cols ->
                cols.joinToString(" ") { formatMathPretty(it, inScript).trim() }.trim()
            }
        }
    }

    private fun formatRowPretty(children: List<MathNode>, inScript: Boolean = false): String {
        if (children.isEmpty()) return ""
        val sb = StringBuilder()
        for (idx in children.indices) {
            val cur = children[idx]
            val prev = children.getOrNull(idx - 1)
            if (prev != null && needsGapBefore(prev, cur)) {
                if (sb.isNotEmpty() && !sb.endsWith(" ")) sb.append(' ')
            }
            val piece = formatMathPretty(cur, inScript)
            if (cur is MathNode.Operator && cur.spacing) {
                val compact = inScript && piece in COMPACT_SCRIPT_OPS
                if (!compact && sb.isNotEmpty() && !sb.endsWith(" ")) sb.append(' ')
                sb.append(piece)
                val next = children.getOrNull(idx + 1)
                if (!compact && next != null && piece != " ") sb.append(' ')
            } else {
                sb.append(piece)
                if (!inScript && cur is MathNode.Text && cur.text in TRAILING_COMMA_TOKENS && idx < children.lastIndex) {
                    sb.append(' ')
                }
            }
        }
        return sb.toString()
    }

    private fun needsGapBefore(prev: MathNode, cur: MathNode): Boolean {
        if (prev is MathNode.Operator && prev.spacing) return false
        if (cur is MathNode.Operator && cur.spacing) return false
        val prevIsWordOrLargeOp = isFunctionOrLargeOp(prev)
        if (prevIsWordOrLargeOp) {
            val curText = leadingRawText(cur)
            if (curText.isNotEmpty() && curText[0] !in NO_SPACE_AFTER_FUNC_CHARS) {
                return true
            }
        }
        if (isFunctionOrLargeOp(cur)) {
            val prevText = trailingRawText(prev)
            if (prevText.isNotEmpty() && prevText.last() !in NO_SPACE_BEFORE_FUNC_CHARS) {
                return true
            }
        }
        return false
    }

    private fun trailingRawText(node: MathNode): String = when (node) {
        is MathNode.Text -> node.text
        is MathNode.Operator -> node.symbol
        is MathNode.Delimited -> node.right
        is MathNode.Row -> node.children.lastOrNull()?.let { trailingRawText(it) }.orEmpty()
        is MathNode.Script -> "x"
        else -> "x"
    }

    private fun isFunctionOrLargeOp(node: MathNode): Boolean = when (node) {
        is MathNode.Operator -> node.isLarge || node.limitsUnderOver
        is MathNode.Text -> node.style == MathFontStyle.Function
        is MathNode.Script -> isFunctionOrLargeOp(node.base)
        is MathNode.Styled -> node.style == MathFontStyle.Function
        else -> false
    }

    private fun leadingRawText(node: MathNode): String = when (node) {
        is MathNode.Text -> node.text
        is MathNode.Operator -> node.symbol
        is MathNode.Delimited -> node.left
        is MathNode.Row -> node.children.firstOrNull()?.let { leadingRawText(it) }.orEmpty()
        is MathNode.Script -> leadingRawText(node.base)
        else -> "x"
    }

    private fun formatScriptValuePretty(value: String, isSuper: Boolean): String {
        if (value.isEmpty()) return ""
        val map = if (isSuper) SUPERSCRIPT_CHAR_MAP else SUBSCRIPT_CHAR_MAP
        val isSingleConvertible = value.length == 1 && value[0] in map
        val isDigitsOnly = value.all { it in '0'..'9' }
        if (isSingleConvertible || isDigitsOnly) {
            return value.map { map[it] ?: it }.joinToString("")
        }
        val prefix = if (isSuper) "^" else "_"
        val unwrapped = if (isSingleBalancedParenPair(value)) value else "($value)"
        return "$prefix$unwrapped"
    }

    private fun isSingleBalancedParenPair(s: String): Boolean {
        if (s.length < 2 || s.first() != '(' || s.last() != ')') return false
        var depth = 0
        for (idx in s.indices) {
            when (s[idx]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0 && idx < s.lastIndex) return false
                }
            }
        }
        return depth == 0
    }

    private fun buildMathBlock(raw: String): MdBlock.MathBlock {
        val node = parseLatexMath(raw)
        val pretty = formatMathPretty(node).replace(MULTI_SPACE_REGEX, " ").trim()
        return MdBlock.MathBlock(pretty, raw, node)
    }

    private fun buildInlineMath(raw: String): MdInline.InlineMath {
        val node = parseLatexMath(raw)
        val pretty = formatMathPretty(node).replace(MULTI_SPACE_REGEX, " ").trim()
        return MdInline.InlineMath(pretty, raw, node)
    }

    private fun countEnvOccurrences(line: String, token: String): Int {
        var count = 0
        var idx = 0
        while (true) {
            val found = line.indexOf(token, idx)
            if (found < 0) break
            count++
            idx = found + token.length
        }
        return count
    }

    private fun computeIndentLevel(leading: String): Int {
        var spaces = 0
        for (ch in leading) {
            spaces += if (ch == '\t') 4 else 1
        }
        return (spaces / 2).coerceIn(0, 4)
    }

    private fun isEscaped(s: String, index: Int): Boolean {
        var backslashes = 0
        var k = index - 1
        while (k >= 0 && s[k] == '\\') {
            backslashes++
            k--
        }
        return backslashes % 2 == 1
    }

    private fun findClosingDoubleDollar(s: String, start: Int): Int {
        var i = start
        while (i + 1 < s.length) {
            if (s[i] == '$' && s[i + 1] == '$' && !isEscaped(s, i)) {
                return i
            }
            i++
        }
        return -1
    }

    private fun findClosingMathDollar(s: String, start: Int): Int {
        if (start >= s.length || s[start].isWhitespace()) return -1
        for (i in start until s.length) {
            if (s[i] == '\n') return -1
            if (s[i] == '$' && !isEscaped(s, i)) {
                if (s.getOrNull(i + 1) == '$') return -1
                if (s[i - 1].isWhitespace()) return -1
                if (s.getOrNull(i + 1)?.isDigit() == true) return -1
                return i
            }
        }
        return -1
    }

    private fun findClosingMdDelimiter(s: String, start: Int, delim: String): Int {
        var i = start
        while (i <= s.length - delim.length) {
            if (s[i] == '\n' && s.getOrNull(i + 1) == '\n') return -1
            if (s[i] == '`' && !isEscaped(s, i)) {
                val closeCode = s.indexOf('`', i + 1)
                if (closeCode > i) {
                    i = closeCode + 1
                    continue
                }
            }
            if (s[i] == '$' && !isEscaped(s, i) && s.getOrNull(i + 1) != '$') {
                val closeMath = findClosingMathDollar(s, i + 1)
                if (closeMath > i) {
                    i = closeMath + 1
                    continue
                }
            }
            if (s.startsWith(delim, i) && !isEscaped(s, i)) {
                return i
            }
            i++
        }
        return -1
    }

    private fun findClosingSingleAsterisk(s: String, start: Int): Int {
        var i = start
        while (i < s.length) {
            if (s[i] == '\n') return -1
            if (s[i] == '`' && !isEscaped(s, i)) {
                val closeCode = s.indexOf('`', i + 1)
                if (closeCode > i) {
                    i = closeCode + 1
                    continue
                }
            }
            if (s[i] == '$' && !isEscaped(s, i) && s.getOrNull(i + 1) != '$') {
                val closeMath = findClosingMathDollar(s, i + 1)
                if (closeMath > i) {
                    i = closeMath + 1
                    continue
                }
            }
            if (s[i] == '*' && !isEscaped(s, i)) {
                if (s.getOrNull(i + 1) == '*') {
                    i += 2
                    continue
                }
                if (!s[i - 1].isWhitespace()) {
                    return i
                }
            }
            i++
        }
        return -1
    }

    private fun findBalancedClosingParen(s: String, start: Int): Int {
        var depth = 1
        var i = start
        while (i < s.length) {
            when {
                s[i] == '\n' -> return -1
                s[i] == '(' -> depth++
                s[i] == ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    private fun parseTableRow(line: String): List<String> {
        val trimmed = line.trim().let { raw ->
            val withoutLeading = if (raw.startsWith("|")) raw.substring(1) else raw
            if (withoutLeading.endsWith("|") && !isEscaped(withoutLeading, withoutLeading.lastIndex)) {
                withoutLeading.dropLast(1)
            } else {
                withoutLeading
            }
        }
        val cells = mutableListOf<String>()
        val cur = StringBuilder()
        var inCode = false
        var inDollarMath = false
        var inParenMath = false
        var i = 0
        while (i < trimmed.length) {
            val ch = trimmed[i]
            if (ch == '\\' && i + 1 < trimmed.length && trimmed[i + 1] == '|' && !inCode && !inDollarMath && !inParenMath) {
                cur.append('|')
                i += 2
                continue
            }
            if (ch == '`' && !isEscaped(trimmed, i) && !inDollarMath && !inParenMath) {
                inCode = !inCode
                cur.append(ch)
                i++
                continue
            }
            if (!inCode && !inParenMath && ch == '$' && !isEscaped(trimmed, i)) {
                if (trimmed.getOrNull(i + 1) == '$') {
                    inDollarMath = !inDollarMath
                    cur.append("$$")
                    i += 2
                    continue
                }
                if (!inDollarMath) {
                    if (findClosingMathDollar(trimmed, i + 1) > i) {
                        inDollarMath = true
                    }
                } else {
                    inDollarMath = false
                }
                cur.append(ch)
                i++
                continue
            }
            if (!inCode && !inDollarMath && trimmed.startsWith("\\(", i)) {
                inParenMath = true
                cur.append("\\(")
                i += 2
                continue
            }
            if (!inCode && !inDollarMath && inParenMath && trimmed.startsWith("\\)", i)) {
                inParenMath = false
                cur.append("\\)")
                i += 2
                continue
            }
            if (ch == '|' && !inCode && !inDollarMath && !inParenMath) {
                cells.add(cur.toString().trim())
                cur.setLength(0)
                i++
                continue
            }
            cur.append(ch)
            i++
        }
        cells.add(cur.toString().trim())
        return cells
    }

    private fun Char?.isWhitespaceOrNull(): Boolean = this == null || this.isWhitespace()

    private class LatexMathParser(private val input: String) {
        private var pos = 0

        fun parseDocument(): MathNode {
            val topRows = splitTopLevel(input, "\\\\")
            if (topRows.size > 1) {
                val hasAmp = topRows.any { splitTopLevel(it, "&").size > 1 }
                val parsedRows = topRows
                    .filterIndexed { idx, row -> row.isNotBlank() || idx < topRows.lastIndex }
                    .map { row ->
                        val cleaned = stripOptionalBracketArg(row.trimStart())
                        if (hasAmp) {
                            splitTopLevel(cleaned, "&").map { LatexMathParser(it.trim()).parseSequence() }
                        } else {
                            listOf(LatexMathParser(cleaned.trim()).parseSequence())
                        }
                    }
                return MathNode.AlignedLines(parsedRows, centered = !hasAmp)
            }
            return parseSequence()
        }

        fun parseSequence(
            stopAtBrace: Boolean = false,
            stopAtRight: Boolean = false
        ): MathNode {
            val items = mutableListOf<MathNode>()
            val infixSplitIndices = mutableListOf<Pair<Int, String>>()

            while (pos < input.length) {
                skipWhitespace()
                if (pos >= input.length) break

                val ch = input[pos]
                if (stopAtBrace && ch == '}') {
                    pos++
                    break
                }
                if (stopAtRight && input.startsWith("\\right", pos) && !isAlphaAt(pos + 6)) {
                    break
                }
                if (ch == '{') {
                    val inner = parseBracedGroup()
                    val scripted = parsePostfixScripts(inner)
                    items.add(scripted)
                    continue
                }
                if (ch == '^' || ch == '_') {
                    val base = if (items.isNotEmpty()) items.removeAt(items.lastIndex) else MathNode.Row(emptyList())
                    items.add(parsePostfixScripts(base, alreadyHasScriptMarker = true))
                    continue
                }
                if (ch == '\'') {
                    var primes = 0
                    while (pos < input.length && input[pos] == '\'') {
                        primes++
                        pos++
                    }
                    val base = if (items.isNotEmpty()) items.removeAt(items.lastIndex) else MathNode.Row(emptyList())
                    val primeNode = MathNode.Text("′".repeat(primes), MathFontStyle.Normal)
                    val combined = mergeSuperscript(base, primeNode)
                    items.add(parsePostfixScripts(combined))
                    continue
                }
                if (ch == '\\') {
                    val cmd = readCommandName()
                    if (cmd == "over" || cmd == "choose") {
                        infixSplitIndices.add(items.size to cmd)
                        continue
                    }
                    val node = handleCommand(cmd, items)
                    if (node != null) {
                        val scripted = parsePostfixScripts(node)
                        items.add(scripted)
                    }
                    continue
                }
                val atom = parseSimpleToken(items)
                if (atom != null) {
                    val scripted = parsePostfixScripts(atom)
                    items.add(scripted)
                }
            }

            if (infixSplitIndices.isNotEmpty()) {
                val (splitIdx, kind) = infixSplitIndices.first()
                val left = compactRow(items.subList(0, splitIdx))
                val right = compactRow(items.subList(splitIdx, items.size))
                val frac = MathNode.Fraction(left, right, hasBar = (kind == "over"))
                return if (kind == "choose") MathNode.Delimited("(", ")", frac) else frac
            }
            return compactRow(items)
        }

        private fun parsePostfixScripts(
            initialBase: MathNode,
            alreadyHasScriptMarker: Boolean = false
        ): MathNode {
            var base = initialBase
            var sub: MathNode? = null
            var sup: MathNode? = null
            if (base is MathNode.Script && alreadyHasScriptMarker) {
                sub = base.subscript
                sup = base.superscript
                base = base.base
            }
            while (true) {
                skipWhitespace()
                if (pos >= input.length) break
                when (input[pos]) {
                    '_' -> {
                        pos++
                        val arg = parseScriptArgument()
                        if (sub == null) {
                            sub = arg
                        } else {
                            base = MathNode.Script(base, sub, sup)
                            sub = arg
                            sup = null
                        }
                    }
                    '^' -> {
                        pos++
                        val arg = parseScriptArgument()
                        if (sup == null) {
                            sup = arg
                        } else {
                            base = MathNode.Script(base, sub, sup)
                            sub = null
                            sup = arg
                        }
                    }
                    '\'' -> {
                        var primes = 0
                        while (pos < input.length && input[pos] == '\'') {
                            primes++
                            pos++
                        }
                        val primeNode = MathNode.Text("′".repeat(primes), MathFontStyle.Normal)
                        sup = if (sup == null) primeNode else compactRow(listOf(sup, primeNode))
                    }
                    else -> break
                }
            }
            return if (sub != null || sup != null) {
                MathNode.Script(base, sub, sup)
            } else {
                base
            }
        }

        private fun mergeSuperscript(base: MathNode, extraSup: MathNode): MathNode {
            return if (base is MathNode.Script) {
                val mergedSup = if (base.superscript == null) extraSup else compactRow(listOf(base.superscript, extraSup))
                base.copy(superscript = mergedSup)
            } else {
                MathNode.Script(base, subscript = null, superscript = extraSup)
            }
        }

        private fun parseScriptArgument(): MathNode {
            skipWhitespace()
            if (pos >= input.length) return MathNode.Row(emptyList())
            val ch = input[pos]
            if (ch == '{') return parseBracedGroup()
            if (ch == '\\') {
                val cmd = readCommandName()
                return handleCommand(cmd, emptyList()) ?: MathNode.Row(emptyList())
            }
            return parseSimpleToken(emptyList()) ?: MathNode.Row(emptyList())
        }

        private fun parseBracedGroup(): MathNode {
            if (pos < input.length && input[pos] == '{') pos++
            return parseSequence(stopAtBrace = true)
        }

        private fun readLiteralBracedText(): String {
            skipWhitespace()
            if (pos >= input.length) return ""
            if (input[pos] != '{') {
                val ch = input[pos++]
                return ch.toString()
            }
            pos++ // skip '{'
            val sb = StringBuilder()
            var depth = 1
            while (pos < input.length && depth > 0) {
                val ch = input[pos]
                when (ch) {
                    '\\' -> {
                        if (pos + 1 < input.length) {
                            val next = input[pos + 1]
                            if (next in TEXT_ESCAPED_CHARS) {
                                sb.append(next)
                                pos += 2
                                continue
                            }
                        }
                        sb.append(ch)
                        pos++
                    }
                    '{' -> {
                        depth++
                        sb.append(ch)
                        pos++
                    }
                    '}' -> {
                        depth--
                        if (depth > 0) sb.append(ch)
                        pos++
                    }
                    else -> {
                        sb.append(ch)
                        pos++
                    }
                }
            }
            return sb.toString()
        }

        private fun readCommandName(): String {
            pos++ // skip '\'
            if (pos >= input.length) return ""
            val first = input[pos]
            if (!first.isLetter()) {
                pos++
                return first.toString()
            }
            val start = pos
            while (pos < input.length && input[pos].isLetter()) pos++
            if (pos < input.length && input[pos] == '*') {
                val name = input.substring(start, pos)
                if (name in STARRED_COMMANDS) {
                    pos++
                    return "$name*"
                }
            }
            return input.substring(start, pos)
        }

        private fun handleCommand(cmd: String, precedingItems: List<MathNode>): MathNode? {
            if (cmd.isEmpty()) return null
            return when (cmd) {
                // Fractions & binomials
                "frac", "dfrac", "tfrac", "cfrac" -> {
                    val num = parseScriptArgument()
                    val den = parseScriptArgument()
                    MathNode.Fraction(num, den, hasBar = true)
                }
                "binom", "dbinom", "tbinom" -> {
                    val top = parseScriptArgument()
                    val bot = parseScriptArgument()
                    MathNode.Delimited("(", ")", MathNode.Fraction(top, bot, hasBar = false))
                }

                // Radicals
                "sqrt" -> {
                    skipWhitespace()
                    val degree = if (pos < input.length && input[pos] == '[') {
                        val degRaw = readBracketGroup()
                        LatexMathParser(degRaw).parseSequence()
                    } else {
                        null
                    }
                    val radicand = parseScriptArgument()
                    MathNode.Radical(radicand, degree)
                }

                // Delimiters \left ... \right
                "left" -> {
                    val leftDelim = readDelimiterToken()
                    val inner = parseSequence(stopAtRight = true)
                    val rightDelim = if (input.startsWith("\\right", pos) && !isAlphaAt(pos + 6)) {
                        pos += 6
                        readDelimiterToken()
                    } else {
                        ""
                    }
                    MathNode.Delimited(leftDelim, rightDelim, inner)
                }
                "right" -> {
                    // Stray \right outside \left
                    val delim = readDelimiterToken()
                    if (delim.isEmpty()) null else MathNode.Text(delim, MathFontStyle.Normal)
                }

                // Environments \begin{...}...\end{...}
                "begin" -> {
                    val envName = readLiteralBracedText().trim()
                    val body = readEnvironmentBody(envName)
                    parseEnvironment(envName, body)
                }
                "end" -> {
                    readLiteralBracedText()
                    null
                }

                // Text / Font commands
                "text", "textrm", "mbox", "textnormal", "textup", "textsf", "texttt" -> {
                    MathNode.Text(readLiteralBracedText(), MathFontStyle.Upright)
                }
                "textbf" -> MathNode.Text(readLiteralBracedText(), MathFontStyle.Bold)
                "textit" -> MathNode.Text(readLiteralBracedText(), MathFontStyle.Italic)
                "mathrm" -> MathNode.Styled(parseScriptArgument(), MathFontStyle.Upright)
                "mathbf", "boldsymbol", "bm" -> MathNode.Styled(parseScriptArgument(), MathFontStyle.Bold)
                "mathit" -> MathNode.Styled(parseScriptArgument(), MathFontStyle.Italic)
                "mathcal" -> MathNode.Styled(parseScriptArgument(), MathFontStyle.Calligraphic)
                "mathbb" -> {
                    val rawArg = readLiteralBracedText()
                    val mapped = rawArg.map { BLACKBOARD_MAP[it] ?: it.toString() }.joinToString("")
                    MathNode.Text(mapped, MathFontStyle.Upright)
                }
                "operatorname", "operatorname*" -> {
                    val name = readLiteralBracedText().trim()
                    if (cmd == "operatorname*") {
                        MathNode.Operator(name, isLarge = false, limitsUnderOver = true, spacing = false)
                    } else {
                        MathNode.Text(name, MathFontStyle.Function)
                    }
                }
                "pmod" -> {
                    val modArg = parseScriptArgument()
                    MathNode.Row(
                        listOf(
                            MathNode.Text(" (mod ", MathFontStyle.Function),
                            modArg,
                            MathNode.Text(")", MathFontStyle.Normal)
                        )
                    )
                }
                "bmod", "mod" -> MathNode.Operator("mod", isLarge = false, limitsUnderOver = false, spacing = true)
                "textcolor" -> {
                    readLiteralBracedText() // ignore color name
                    parseScriptArgument()
                }
                "color", "hspace", "vspace", "phantom" -> {
                    readLiteralBracedText()
                    if (cmd == "phantom") MathNode.Text(" ", MathFontStyle.Normal) else null
                }
                "boxed" -> MathNode.Delimited("[", "]", parseScriptArgument())
                "tag" -> {
                    val t = parseScriptArgument()
                    MathNode.Delimited("(", ")", t)
                }

                // Accents
                "overline" -> MathNode.Accent(parseScriptArgument(), MathAccentKind.OVERLINE)
                "bar" -> MathNode.Accent(parseScriptArgument(), MathAccentKind.BAR)
                "vec" -> MathNode.Accent(parseScriptArgument(), MathAccentKind.VEC)
                "overrightarrow" -> MathNode.Accent(parseScriptArgument(), MathAccentKind.ARROW_RIGHT)
                "hat" -> MathNode.Accent(parseScriptArgument(), MathAccentKind.HAT)
                "widehat" -> MathNode.Accent(parseScriptArgument(), MathAccentKind.WIDEHAT)
                "tilde", "widetilde" -> MathNode.Accent(parseScriptArgument(), MathAccentKind.TILDE)
                "dot" -> MathNode.Accent(parseScriptArgument(), MathAccentKind.DOT)
                "ddot" -> MathNode.Accent(parseScriptArgument(), MathAccentKind.DDOT)
                "underline" -> MathNode.Accent(parseScriptArgument(), MathAccentKind.UNDERLINE)

                // Spacing & ignored formatting directives
                ",", ":", ";", " " -> MathNode.Text(" ", MathFontStyle.Normal)
                "quad" -> MathNode.Text("  ", MathFontStyle.Normal)
                "qquad" -> MathNode.Text("    ", MathFontStyle.Normal)
                "!" -> null
                "displaystyle", "textstyle", "scriptstyle", "scriptscriptstyle",
                "limits", "nolimits", "big", "Big", "bigg", "Bigg",
                "bigl", "bigr", "Bigl", "Bigr", "biggl", "biggr", "Biggl", "Biggr",
                "nonumber", "notag" -> null

                // Escaped symbols
                "{", "}", "%", "&", "#", "_", "$" -> MathNode.Text(cmd, MathFontStyle.Normal)
                "|" -> MathNode.Text("‖", MathFontStyle.Normal)
                "\\" -> MathNode.Text(" ", MathFontStyle.Normal)

                else -> {
                    when {
                        cmd in LARGE_LIMITS_OPS -> MathNode.Operator(
                            symbol = LARGE_LIMITS_OPS.getValue(cmd),
                            isLarge = true,
                            limitsUnderOver = true,
                            spacing = false
                        )
                        cmd in LARGE_SIDE_OPS -> MathNode.Operator(
                            symbol = LARGE_SIDE_OPS.getValue(cmd),
                            isLarge = true,
                            limitsUnderOver = false,
                            spacing = false
                        )
                        cmd in LIMIT_WORD_FUNCTIONS -> MathNode.Operator(
                            symbol = cmd,
                            isLarge = false,
                            limitsUnderOver = true,
                            spacing = false
                        )
                        cmd in MATH_FUNCTIONS -> MathNode.Text(cmd, MathFontStyle.Function)
                        cmd in SPACED_BINARY_OPS -> {
                            val sym = SPACED_BINARY_OPS.getValue(cmd)
                            val isUnarySign = (cmd == "pm" || cmd == "mp") && isUnaryContext(precedingItems)
                            MathNode.Operator(sym, isLarge = false, limitsUnderOver = false, spacing = !isUnarySign)
                        }
                        cmd in SYMBOL_MAP -> MathNode.Text(SYMBOL_MAP.getValue(cmd), MathFontStyle.Normal)
                        else -> MathNode.Text("\\$cmd", MathFontStyle.Upright)
                    }
                }
            }
        }

        private fun parseSimpleToken(precedingItems: List<MathNode>): MathNode? {
            if (pos >= input.length) return null
            val ch = input[pos]
            if (ch in '0'..'9') {
                val start = pos
                while (pos < input.length && (input[pos] in '0'..'9' || (input[pos] == '.' && pos + 1 < input.length && input[pos + 1] in '0'..'9'))) {
                    pos++
                }
                return MathNode.Text(input.substring(start, pos), MathFontStyle.Normal)
            }
            if (ch in 'a'..'z' || ch in 'A'..'Z') {
                pos++
                return MathNode.Text(ch.toString(), MathFontStyle.Italic)
            }
            pos++
            return when (ch) {
                '+', '-' -> {
                    val sym = ch.toString()
                    val unary = isUnaryContext(precedingItems)
                    MathNode.Operator(sym, spacing = !unary)
                }
                '=', '<', '>' -> MathNode.Operator(ch.toString(), spacing = true)
                '*' -> MathNode.Operator("∗", spacing = true)
                '~' -> MathNode.Text(" ", MathFontStyle.Normal)
                else -> MathNode.Text(ch.toString(), MathFontStyle.Normal)
            }
        }

        private fun isUnaryContext(precedingItems: List<MathNode>): Boolean {
            if (precedingItems.isEmpty()) return true
            return when (val prev = precedingItems.last()) {
                is MathNode.Operator -> true
                is MathNode.Text -> prev.text in UNARY_PRECEDING_TOKENS
                else -> false
            }
        }

        private fun readBracketGroup(): String {
            if (pos < input.length && input[pos] == '[') pos++
            val start = pos
            var braceDepth = 0
            var bracketDepth = 1
            while (pos < input.length && bracketDepth > 0) {
                when (input[pos]) {
                    '\\' -> pos += 2
                    '{' -> {
                        braceDepth++
                        pos++
                    }
                    '}' -> {
                        if (braceDepth > 0) braceDepth--
                        pos++
                    }
                    '[' -> {
                        if (braceDepth == 0) bracketDepth++
                        pos++
                    }
                    ']' -> {
                        if (braceDepth == 0) {
                            bracketDepth--
                            if (bracketDepth == 0) {
                                val content = input.substring(start, pos)
                                pos++
                                return content
                            }
                        }
                        pos++
                    }
                    else -> pos++
                }
            }
            return input.substring(start, pos)
        }

        private fun readDelimiterToken(): String {
            skipWhitespace()
            if (pos >= input.length) return ""
            val ch = input[pos]
            if (ch == '.') {
                pos++
                return ""
            }
            if (ch != '\\') {
                pos++
                return ch.toString()
            }
            val cmd = readCommandName()
            return when (cmd) {
                "{", "}" -> cmd
                "|", "lVert", "rVert" -> "‖"
                "lvert", "rvert" -> "|"
                "langle" -> "⟨"
                "rangle" -> "⟩"
                "lfloor" -> "⌊"
                "rfloor" -> "⌋"
                "lceil" -> "⌈"
                "rceil" -> "⌉"
                "." -> ""
                else -> SYMBOL_MAP[cmd] ?: cmd
            }
        }

        private fun readEnvironmentBody(envName: String): String {
            val beginTok = "\\begin{$envName}"
            val endTok = "\\end{$envName}"
            val start = pos
            var depth = 1
            while (pos < input.length) {
                if (input.startsWith(beginTok, pos)) {
                    depth++
                    pos += beginTok.length
                    continue
                }
                if (input.startsWith(endTok, pos)) {
                    depth--
                    if (depth == 0) {
                        val body = input.substring(start, pos)
                        pos += endTok.length
                        return body
                    }
                    pos += endTok.length
                    continue
                }
                pos++
            }
            return input.substring(start)
        }

        private fun parseEnvironment(envName: String, body: String): MathNode {
            val rawRows = splitTopLevel(body.trim(), "\\\\")
                .map { stripOptionalBracketArg(it.trim()) }
                .let { list ->
                    if (list.size > 1 && list.last().isBlank()) list.dropLast(1) else list
                }
            return when (envName) {
                "cases" -> {
                    val caseRows = rawRows.map { row ->
                        val cols = splitTopLevel(row, "&")
                        val expr = LatexMathParser(cols.getOrNull(0).orEmpty().trim()).parseSequence()
                        val cond = if (cols.size > 1) {
                            LatexMathParser(cols.drop(1).joinToString(" ").trim()).parseSequence()
                        } else {
                            null
                        }
                        expr to cond
                    }
                    MathNode.Cases(caseRows)
                }
                "pmatrix", "bmatrix", "Bmatrix", "vmatrix", "Vmatrix", "matrix", "smallmatrix" -> {
                    val (left, right) = when (envName) {
                        "pmatrix" -> "(" to ")"
                        "bmatrix" -> "[" to "]"
                        "Bmatrix" -> "{" to "}"
                        "vmatrix" -> "|" to "|"
                        "Vmatrix" -> "‖" to "‖"
                        else -> "" to ""
                    }
                    val matrixRows = rawRows.map { row ->
                        splitTopLevel(row, "&").map { cell ->
                            LatexMathParser(cell.trim()).parseSequence()
                        }
                    }
                    MathNode.Matrix(matrixRows, left, right)
                }
                "aligned", "align", "align*", "split", "flalign", "flalign*", "alignat", "alignat*", "eqnarray", "eqnarray*" -> {
                    val alignedRows = rawRows.map { row ->
                        splitTopLevel(row, "&").map { cell ->
                            LatexMathParser(cell.trim()).parseSequence()
                        }
                    }
                    MathNode.AlignedLines(alignedRows, centered = false)
                }
                "gather", "gather*", "gathered", "equation", "equation*", "multline", "multline*" -> {
                    val lines = rawRows.map { row ->
                        splitTopLevel(row, "&").map { cell ->
                            LatexMathParser(cell.trim()).parseSequence()
                        }
                    }
                    MathNode.AlignedLines(lines, centered = true)
                }
                else -> LatexMathParser(body).parseSequence()
            }
        }

        private fun splitTopLevel(text: String, delimiter: String): List<String> {
            val out = mutableListOf<String>()
            val cur = StringBuilder()
            var braceDepth = 0
            var envDepth = 0
            var i = 0
            while (i < text.length) {
                if (text.startsWith("\\begin{", i)) {
                    envDepth++
                    cur.append("\\begin{")
                    i += 7
                    continue
                }
                if (text.startsWith("\\end{", i)) {
                    if (envDepth > 0) envDepth--
                    cur.append("\\end{")
                    i += 5
                    continue
                }
                if (text[i] == '\\' && i + 1 < text.length && text[i + 1] in setOf('{', '}', '&')) {
                    cur.append(text[i]).append(text[i + 1])
                    i += 2
                    continue
                }
                if (text[i] == '{') {
                    braceDepth++
                    cur.append('{')
                    i++
                    continue
                }
                if (text[i] == '}') {
                    if (braceDepth > 0) braceDepth--
                    cur.append('}')
                    i++
                    continue
                }
                if (braceDepth == 0 && envDepth == 0 && text.startsWith(delimiter, i)) {
                    out.add(cur.toString())
                    cur.setLength(0)
                    i += delimiter.length
                    continue
                }
                cur.append(text[i])
                i++
            }
            out.add(cur.toString())
            return out
        }

        private fun stripOptionalBracketArg(text: String): String {
            if (!text.startsWith("[")) return text
            val end = text.indexOf(']')
            if (end in 1..12) {
                val inner = text.substring(1, end).trim()
                if (inner.endsWith("pt") || inner.endsWith("em") || inner.endsWith("ex") || inner.endsWith("mm")) {
                    return text.substring(end + 1).trimStart()
                }
            }
            return text
        }

        private fun skipWhitespace() {
            while (pos < input.length && input[pos].isWhitespace()) pos++
        }

        private fun isAlphaAt(idx: Int): Boolean = idx < input.length && input[idx].isLetter()

        private fun compactRow(items: List<MathNode>): MathNode = when (items.size) {
            0 -> MathNode.Row(emptyList())
            1 -> items[0]
            else -> MathNode.Row(items)
        }
    }

    private val HEADING_REGEX = Regex("^(#{1,6})\\s+(.+)$")
    private val BULLET_REGEX = Regex("^([ \\t]*)[-*+]\\s+(.+)$")
    private val TASK_PREFIX_REGEX = Regex("^\\[([ xX])\\]\\s*(.*)$")
    private val NUMBERED_REGEX = Regex("^([ \\t]*)(\\d+\\.)\\s+(.+)$")
    private val DIVIDER_REGEX = Regex("^([-*_])(\\s*\\1){2,}$")
    private val TABLE_SEP_REGEX = Regex("^\\|?(\\s*:?-{1,}:?\\s*\\|)+(\\s*:?-{1,}:?\\s*\\|?)?$")
    private val BLOCK_MATH_ENV_REGEX = Regex("^\\\\begin\\{([a-zA-Z*]+)\\}")
    private val MULTI_SPACE_REGEX = Regex("\\s{2,}")

    private val ESCAPABLE_MD_CHARS = setOf('$', '*', '_', '`', '\\', '|', '[', ']')
    private val TEXT_ESCAPED_CHARS = setOf('{', '}', '%', '&', '#', '_', '$', '\\')
    private val TRAILING_COMMA_TOKENS = setOf(",", ";")
    private val COMPACT_SCRIPT_OPS = setOf("+", "-", "=", "<", ">", "∗", "·")
    private val NO_SPACE_AFTER_FUNC_CHARS = setOf('(', '[', '{', ',', '.', ';', ':', ')', ']', '}')
    private val NO_SPACE_BEFORE_FUNC_CHARS = setOf('(', '[', '{', ' ')
    private val UNARY_PRECEDING_TOKENS = setOf("(", "[", "{", ",", ";", ":", "|", "‖")
    private val STARRED_COMMANDS = setOf("operatorname")

    private val BLOCK_MATH_ENVIRONMENTS = setOf(
        "equation", "equation*", "align", "align*", "aligned",
        "gather", "gather*", "gathered", "cases", "split",
        "multline", "multline*", "pmatrix", "bmatrix", "Bmatrix",
        "vmatrix", "Vmatrix", "matrix"
    )

    private val SUPERSCRIPT_CHAR_MAP = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
        '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
        '+' to '⁺', '-' to '⁻', '−' to '⁻', 'n' to 'ⁿ', 'i' to 'ⁱ'
    )

    private val SUBSCRIPT_CHAR_MAP = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
        '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
        '+' to '₊', '-' to '₋', '−' to '₋'
    )

    private val BLACKBOARD_MAP = mapOf(
        'R' to "ℝ", 'N' to "ℕ", 'Z' to "ℤ", 'Q' to "ℚ", 'C' to "ℂ",
        'P' to "ℙ", 'E' to "𝔼", 'H' to "ℍ", 'F' to "𝔽"
    )

    private val LARGE_LIMITS_OPS = mapOf(
        "sum" to "∑",
        "prod" to "∏",
        "coprod" to "∐",
        "bigcup" to "⋃",
        "bigcap" to "⋂",
        "bigoplus" to "⨁",
        "bigotimes" to "⨂"
    )

    private val LARGE_SIDE_OPS = mapOf(
        "int" to "∫",
        "iint" to "∬",
        "iiint" to "∭",
        "oint" to "∮"
    )

    private val LIMIT_WORD_FUNCTIONS = setOf(
        "lim", "limsup", "liminf", "max", "min", "sup", "inf", "gcd", "Pr"
    )

    private val MATH_FUNCTIONS = setOf(
        "sin", "cos", "tan", "cot", "sec", "csc",
        "arcsin", "arccos", "arctan",
        "sinh", "cosh", "tanh", "coth",
        "ln", "log", "lg", "exp",
        "det", "dim", "ker", "deg", "hom", "arg", "lcm"
    )

    private val SPACED_BINARY_OPS = mapOf(
        "implies" to "⇒",
        "impliedby" to "⇐",
        "iff" to "⇔",
        "Rightarrow" to "⇒",
        "Leftarrow" to "⇐",
        "Leftrightarrow" to "⇔",
        "rightarrow" to "→",
        "leftarrow" to "←",
        "leftrightarrow" to "↔",
        "longrightarrow" to "⟶",
        "longleftarrow" to "⟵",
        "to" to "→",
        "gets" to "←",
        "mapsto" to "↦",
        "longmapsto" to "↦",
        "cup" to "∪",
        "cap" to "∩",
        "setminus" to "∖",
        "subset" to "⊂",
        "subseteq" to "⊆",
        "supset" to "⊃",
        "supseteq" to "⊇",
        "nsubseteq" to "⊈",
        "nsupseteq" to "⊉",
        "subsetneq" to "⊊",
        "supsetneq" to "⊋",
        "in" to "∈",
        "notin" to "∉",
        "ni" to "∋",
        "land" to "∧",
        "wedge" to "∧",
        "lor" to "∨",
        "vee" to "∨",
        "cong" to "≅",
        "sim" to "∼",
        "simeq" to "≃",
        "approx" to "≈",
        "equiv" to "≡",
        "propto" to "∝",
        "neq" to "≠",
        "ne" to "≠",
        "leq" to "≤",
        "le" to "≤",
        "geq" to "≥",
        "ge" to "≥",
        "ll" to "≪",
        "gg" to "≫",
        "prec" to "≺",
        "succ" to "≻",
        "preceq" to "⪯",
        "succeq" to "⪰",
        "mp" to "∓",
        "pm" to "±",
        "times" to "×",
        "div" to "÷",
        "cdot" to "·",
        "ast" to "∗",
        "star" to "⋆",
        "circ" to "∘",
        "bullet" to "•",
        "oplus" to "⊕",
        "otimes" to "⊗",
        "ominus" to "⊖",
        "odot" to "⊙",
        "perp" to "⊥",
        "parallel" to "∥",
        "mid" to "∣",
        "nmid" to "∤",
        "vdash" to "⊢",
        "models" to "⊨"
    )

    private val SYMBOL_MAP = mapOf(
        // Lowercase Greek
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
        "epsilon" to "ϵ", "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η",
        "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ",
        "varkappa" to "ϰ", "lambda" to "λ", "mu" to "μ", "nu" to "ν",
        "xi" to "ξ", "omicron" to "ο", "pi" to "π", "varpi" to "ϖ",
        "rho" to "ρ", "varrho" to "ϱ", "sigma" to "σ", "varsigma" to "ς",
        "tau" to "τ", "upsilon" to "υ", "phi" to "φ", "varphi" to "ϕ",
        "chi" to "χ", "psi" to "ψ", "omega" to "ω",
        // Uppercase Greek
        "Alpha" to "Α", "Beta" to "Β", "Gamma" to "Γ", "Delta" to "Δ",
        "Epsilon" to "Ε", "Zeta" to "Ζ", "Eta" to "Η", "Theta" to "Θ",
        "Iota" to "Ι", "Kappa" to "Κ", "Lambda" to "Λ", "Mu" to "Μ",
        "Nu" to "Ν", "Xi" to "Ξ", "Omicron" to "Ο", "Pi" to "Π",
        "Rho" to "Ρ", "Sigma" to "Σ", "Tau" to "Τ", "Upsilon" to "Υ",
        "Phi" to "Φ", "Chi" to "Χ", "Psi" to "Ψ", "Omega" to "Ω",
        // Arrows & logic & sets & geometry & misc
        "uparrow" to "↑", "downarrow" to "↓", "updownarrow" to "↕",
        "Uparrow" to "⇑", "Downarrow" to "⇓",
        "nearrow" to "↗", "searrow" to "↘", "swarrow" to "↙", "nwarrow" to "↖",
        "emptyset" to "∅", "varnothing" to "∅",
        "neg" to "¬", "lnot" to "¬",
        "forall" to "∀", "exists" to "∃", "nexists" to "∄",
        "therefore" to "∴", "because" to "∵",
        "top" to "⊤", "bot" to "⊥",
        "angle" to "∠", "measuredangle" to "∡", "triangle" to "△", "square" to "□",
        "infty" to "∞", "partial" to "∂", "nabla" to "∇",
        "hbar" to "ℏ", "ell" to "ℓ", "wp" to "℘", "Re" to "ℜ", "Im" to "ℑ", "aleph" to "ℵ",
        "dots" to "…", "ldots" to "…", "cdots" to "⋯", "vdots" to "⋮", "ddots" to "⋱",
        "prime" to "′", "degree" to "°", "surd" to "√", "checkmark" to "✓",
        "langle" to "⟨", "rangle" to "⟩",
        "lfloor" to "⌊", "rfloor" to "⌋",
        "lceil" to "⌈", "rceil" to "⌉",
        "dagger" to "†", "ddagger" to "‡"
    )
}
