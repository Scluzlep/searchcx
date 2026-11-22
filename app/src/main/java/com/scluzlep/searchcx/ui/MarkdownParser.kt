package com.scluzlep.searchcx.ui

sealed interface MdInline {
    data class Plain(val text: String) : MdInline
    data class Bold(val text: String) : MdInline
    data class Italic(val text: String) : MdInline
    data class Strike(val text: String) : MdInline
    data class InlineCode(val code: String) : MdInline
    data class InlineMath(val pretty: String, val raw: String) : MdInline
    data class Link(val label: String, val url: String) : MdInline
}

sealed interface MdBlock {
    data class Heading(val level: Int, val inlines: List<MdInline>) : MdBlock
    data class Paragraph(val inlines: List<MdInline>) : MdBlock
    data class CodeBlock(val language: String, val code: String, val closed: Boolean) : MdBlock
    data class MathBlock(val pretty: String, val raw: String) : MdBlock
    data class BlockQuote(val inlines: List<MdInline>) : MdBlock
    data class BulletItem(val inlines: List<MdInline>) : MdBlock
    data class NumberedItem(val number: String, val inlines: List<MdInline>) : MdBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MdBlock
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

            // 1. Fenced code blocks ```lang
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
                blocks.add(MdBlock.CodeBlock(lang, codeLines.joinToString("\n"), closed))
                continue
            }

            // 2. Display math blocks $$...$$ or \[...\]
            if (trimmed.startsWith("$$") || trimmed.startsWith("\\[")) {
                flushParagraph()
                val isDollar = trimmed.startsWith("$$")
                val openTok = if (isDollar) "$$" else "\\["
                val closeTok = if (isDollar) "$$" else "\\]"
                val afterOpen = trimmed.removePrefix(openTok)
                if (afterOpen.contains(closeTok) && afterOpen.indexOf(closeTok) >= 0) {
                    val inner = afterOpen.substringBeforeLast(closeTok).trim()
                    blocks.add(MdBlock.MathBlock(prettifyLatexMath(inner), inner))
                    i++
                } else {
                    val mathLines = mutableListOf<String>()
                    if (afterOpen.isNotBlank()) mathLines.add(afterOpen)
                    i++
                    while (i < lines.size) {
                        val cur = lines[i]
                        if (cur.trim().endsWith(closeTok)) {
                            val tail = cur.trim().removeSuffix(closeTok).trim()
                            if (tail.isNotEmpty()) mathLines.add(tail)
                            i++
                            break
                        }
                        mathLines.add(cur)
                        i++
                    }
                    val rawMath = mathLines.joinToString("\n").trim()
                    blocks.add(MdBlock.MathBlock(prettifyLatexMath(rawMath), rawMath))
                }
                continue
            }

            // 3. Blank line
            if (trimmed.isEmpty()) {
                flushParagraph()
                i++
                continue
            }

            // 4. Horizontal rule
            if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
                flushParagraph()
                blocks.add(MdBlock.Divider)
                i++
                continue
            }

            // 5. Headings # .. ######
            val headingMatch = HEADING_REGEX.matchEntire(trimmed)
            if (headingMatch != null) {
                flushParagraph()
                val level = headingMatch.groupValues[1].length
                val titleText = headingMatch.groupValues[2].trim()
                blocks.add(MdBlock.Heading(level, parseInlines(titleText)))
                i++
                continue
            }

            // 6. Markdown table
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
                blocks.add(MdBlock.Table(headers, rows))
                continue
            }

            // 7. Blockquote
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

            // 8. Unordered list item
            val bulletMatch = BULLET_REGEX.matchEntire(line)
            if (bulletMatch != null) {
                flushParagraph()
                blocks.add(MdBlock.BulletItem(parseInlines(bulletMatch.groupValues[1])))
                i++
                continue
            }

            // 9. Ordered list item
            val numMatch = NUMBERED_REGEX.matchEntire(line)
            if (numMatch != null) {
                flushParagraph()
                blocks.add(
                    MdBlock.NumberedItem(
                        number = numMatch.groupValues[1],
                        inlines = parseInlines(numMatch.groupValues[2])
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

    fun parseInlines(input: String): List<MdInline> {
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
            if (input[i] == '`') {
                val end = input.indexOf('`', i + 1)
                if (end > i) {
                    flushPlain()
                    out.add(MdInline.InlineCode(input.substring(i + 1, end)))
                    i = end + 1
                    continue
                }
            }

            // Inline math $...$
            if (input[i] == '$' && (i == 0 || input[i - 1] != '\\') && input.getOrNull(i + 1) != '$') {
                val end = findClosingMathDollar(input, i + 1)
                if (end > i + 1) {
                    flushPlain()
                    val raw = input.substring(i + 1, end).trim()
                    out.add(MdInline.InlineMath(prettifyLatexMath(raw), raw))
                    i = end + 1
                    continue
                }
            }

            // Inline math \(...\)
            if (input.startsWith("\\(", i)) {
                val end = input.indexOf("\\)", i + 2)
                if (end > i + 2) {
                    flushPlain()
                    val raw = input.substring(i + 2, end).trim()
                    out.add(MdInline.InlineMath(prettifyLatexMath(raw), raw))
                    i = end + 2
                    continue
                }
            }

            // Link [label](url)
            if (input[i] == '[') {
                val closeBracket = input.indexOf(']', i + 1)
                if (closeBracket > i && input.getOrNull(closeBracket + 1) == '(') {
                    val closeParen = input.indexOf(')', closeBracket + 2)
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

            // Bold **...**
            if (input.startsWith("**", i)) {
                val end = input.indexOf("**", i + 2)
                if (end > i + 2) {
                    flushPlain()
                    out.add(MdInline.Bold(input.substring(i + 2, end)))
                    i = end + 2
                    continue
                }
            }

            // Strikethrough ~~...~~
            if (input.startsWith("~~", i)) {
                val end = input.indexOf("~~", i + 2)
                if (end > i + 2) {
                    flushPlain()
                    out.add(MdInline.Strike(input.substring(i + 2, end)))
                    i = end + 2
                    continue
                }
            }

            // Italic *...*
            if (input[i] == '*' && input.getOrNull(i + 1) != '*') {
                val end = input.indexOf('*', i + 1)
                if (end > i + 1 && !input.substring(i + 1, end).contains('\n')) {
                    flushPlain()
                    out.add(MdInline.Italic(input.substring(i + 1, end)))
                    i = end + 1
                    continue
                }
            }

            plain.append(input[i])
            i++
        }
        flushPlain()
        return out
    }

    fun prettifyLatexMath(raw: String): String {
        var s = raw.trim()
        s = replaceLatexCommands(s, "frac") { args ->
            val num = args.getOrNull(0).orEmpty()
            val den = args.getOrNull(1).orEmpty()
            "(${wrapIfComplex(num)})/(${wrapIfComplex(den)})"
        }
        s = replaceLatexCommands(s, "sqrt") { args ->
            "√(${args.firstOrNull().orEmpty()})"
        }
        for ((k, v) in LATEX_SYMBOLS) {
            s = s.replace(k, v)
        }
        s = s.replace(SUPERSCRIPT_REGEX) { m -> toSuperscript(m.groupValues[1]) }
        s = s.replace(SUBSCRIPT_REGEX) { m -> toSubscript(m.groupValues[1]) }
        return s.replace("\\,", " ")
            .replace("\\;", " ")
            .replace("\\!", "")
            .replace("\\left", "")
            .replace("\\right", "")
            .replace("{", "")
            .replace("}", "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    private fun wrapIfComplex(v: String): String = v.trim()

    private fun replaceLatexCommands(
        input: String,
        cmd: String,
        formatter: (List<String>) -> String
    ): String {
        var s = input
        val token = "\\$cmd"
        val expectedArgs = if (cmd == "frac") 2 else 1
        while (true) {
            val idx = s.indexOf(token)
            if (idx < 0) break
            var cursor = idx + token.length
            val args = mutableListOf<String>()
            var valid = true
            repeat(expectedArgs) {
                while (cursor < s.length && s[cursor] == ' ') cursor++
                if (cursor >= s.length || s[cursor] != '{') {
                    valid = false
                    return@repeat
                }
                var depth = 0
                val start = cursor + 1
                var end = -1
                while (cursor < s.length) {
                    when (s[cursor]) {
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                end = cursor
                                cursor++
                                break
                            }
                        }
                    }
                    cursor++
                }
                if (end < 0) {
                    valid = false
                    return@repeat
                }
                args.add(s.substring(start, end))
            }
            if (!valid || args.size < expectedArgs) break
            s = s.substring(0, idx) + formatter(args) + s.substring(cursor)
        }
        return s
    }

    private fun findClosingMathDollar(s: String, start: Int): Int {
        for (i in start until s.length) {
            if (s[i] == '\n') return -1
            if (s[i] == '$' && s[i - 1] != '\\') return i
        }
        return -1
    }

    private fun parseTableRow(line: String): List<String> {
        return line.trim().trim('|').split('|').map { it.trim() }
    }

    private fun toSuperscript(text: String): String = text.map {
        when (it) {
            '0' -> '⁰'; '1' -> '¹'; '2' -> '²'; '3' -> '³'; '4' -> '⁴'
            '5' -> '⁵'; '6' -> '⁶'; '7' -> '⁷'; '8' -> '⁸'; '9' -> '⁹'
            '+' -> '⁺'; '-' -> '⁻'; 'n' -> 'ⁿ'; 'i' -> 'ⁱ'
            else -> it
        }
    }.joinToString("")

    private fun toSubscript(text: String): String = text.map {
        when (it) {
            '0' -> '₀'; '1' -> '₁'; '2' -> '₂'; '3' -> '₃'; '4' -> '₄'
            '5' -> '₅'; '6' -> '₆'; '7' -> '₇'; '8' -> '₈'; '9' -> '₉'
            '+' -> '₊'; '-' -> '₋'
            else -> it
        }
    }.joinToString("")

    private val HEADING_REGEX = Regex("^(#{1,6})\\s+(.+)$")
    private val BULLET_REGEX = Regex("^\\s*[-*+]\\s+(.+)$")
    private val NUMBERED_REGEX = Regex("^\\s*(\\d+\\.)\\s+(.+)$")
    private val TABLE_SEP_REGEX = Regex("^\\|?(\\s*:?-{3,}:?\\s*\\|)+(\\s*:?-{3,}:?\\s*\\|?)?$")
    private val SUPERSCRIPT_REGEX = Regex("\\^([0-9]+|[+-]|[a-zA-Z]|\\{[^}]+\\})")
    private val SUBSCRIPT_REGEX = Regex("_([0-9]+|[+-]|\\{[^}]+\\})")

    private val LATEX_SYMBOLS = listOf(
        "\\Leftrightarrow" to "⇔",
        "\\Rightarrow" to "⇒",
        "\\rightarrow" to "→",
        "\\leftarrow" to "←",
        "\\approx" to "≈",
        "\\equiv" to "≡",
        "\\times" to "×",
        "\\cdot" to "·",
        "\\infty" to "∞",
        "\\partial" to "∂",
        "\\nabla" to "∇",
        "\\forall" to "∀",
        "\\exists" to "∃",
        "\\lambda" to "λ",
        "\\alpha" to "α",
        "\\beta" to "β",
        "\\gamma" to "γ",
        "\\delta" to "δ",
        "\\theta" to "θ",
        "\\sigma" to "σ",
        "\\omega" to "ω",
        "\\Delta" to "Δ",
        "\\Sigma" to "Σ",
        "\\Omega" to "Ω",
        "\\prod" to "∏",
        "\\notin" to "∉",
        "\\subseteq" to "⊆",
        "\\subset" to "⊂",
        "\\neq" to "≠",
        "\\geq" to "≥",
        "\\leq" to "≤",
        "\\sum" to "∑",
        "\\int" to "∫",
        "\\div" to "÷",
        "\\pm" to "±",
        "\\mp" to "∓",
        "\\mu" to "μ",
        "\\pi" to "π",
        "\\phi" to "φ",
        "\\rho" to "ρ",
        "\\tau" to "τ",
        "\\eta" to "η",
        "\\xi" to "ξ",
        "\\to" to "→",
        "\\in" to "∈",
        "\\le" to "≤",
        "\\ge" to "≥",
        "\\ne" to "≠"
    )
}
