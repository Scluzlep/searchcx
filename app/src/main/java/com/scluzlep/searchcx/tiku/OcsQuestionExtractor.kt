package com.scluzlep.searchcx.tiku

/**
 * Extracts structured quiz questions and options from pre-parsed DOM items or raw page text,
 * following OCS question/option cleanup and question-type inference conventions.
 */
object OcsQuestionExtractor {

    private const val MAX_DOM_ITEMS = 15
    private const val MAX_RAW_ITEMS = 10
    private const val MAX_OPTIONS = 12
    private const val MAX_QUESTION_CHARS = 2000

    private val OPTION_PREFIX_REGEX = Regex("""^[A-Ha-h][.．:：、)）]\s*""")
    private val PAREN_OPTION_PREFIX_REGEX = Regex("""^[(（\[]\s*[A-Ha-h]\s*[)）\]]\s*""")
    private val INLINE_OPTION_MARKER_REGEX = Regex("""(?:^|\s)([A-Ha-h])[.．:：、)）]\s*""")
    private val QUESTION_NUMBER_PREFIX_REGEX = Regex(
        """^\s*(?:第\s*\d{1,3}\s*题(?:\s+|[:：.．、])\s*|(?:[(（\[]\s*\d{1,3}\s*[)）\]]|\d{1,3}\s*[.．、)）])(?![0-9])\s*)"""
    )
    private val LEADING_TYPE_TAG_REGEX = Regex(
        """^\s*[\[【(（]\s*(?:单选题|单项选择题|多选题|多项选择题|填空题|判断题|简答题|问答题|主观题|客观题)\s*[\]】)）]\s*"""
    )
    private val TRAILING_SCORE_TAG_REGEX = Regex(
        """\s*[(（\[]\s*\d+(?:\.\d+)?\s*分\s*[)）\]]\s*$"""
    )
    private val MULTI_QUESTION_START_REGEX = Regex(
        """^\s*(?:第\s*\d{1,3}\s*题\s*[:：.．、]?\s*|(?:[(（]\s*\d{1,3}\s*[)）]|\d{1,3}[.．、)）])(?![0-9]))\s*(.+)$"""
    )
    private val MULTI_CHOICE_HINT_REGEX = Regex(
        """(?:多选|多项|以下哪些|下列哪些|正确的有|错误的有|不正确的有|属于.{0,12}的有|至少有两个)"""
    )
    private val BLANK_HINT_REGEX = Regex("""_{2,}|[（(]\s*[)）]|【\s*】""")
    private val IMAGE_PLACEHOLDER_REGEX = Regex("""\[图片\s*\d*]""")
    private val LATEX_LINEBREAK_REGEX = Regex("""\\\\(?:\s*\[[^\]]*\])?""")
    private val MULTI_WHITESPACE_REGEX = Regex("""\s+""")

    private val LAYOUT_STRIP_COMMANDS = setOf(
        "displaystyle", "textstyle", "scriptstyle", "scriptscriptstyle",
        "limits", "nolimits",
        "big", "Big", "bigg", "Bigg",
        "bigl", "bigr", "Bigl", "Bigr",
        "biggl", "biggr", "Biggl", "Biggr"
    )

    private val UNWRAP_COMMANDS = setOf(
        "text", "textbf", "textit", "textrm", "texttt",
        "mathrm", "mathbf", "mathit", "mathsf", "mathtt",
        "mathbb", "mathcal", "mathfrak", "boldsymbol",
        "operatorname", "mbox", "hbox",
        "overline", "underline", "hat", "widehat",
        "bar", "vec", "overrightarrow", "tilde", "widetilde",
        "dot", "ddot"
    )

    private val MATH_FUNC_COMMANDS = setOf(
        "sin", "cos", "tan", "cot", "sec", "csc",
        "arcsin", "arccos", "arctan",
        "sinh", "cosh", "tanh",
        "ln", "log", "lg", "exp",
        "lim", "max", "min", "sup", "inf",
        "det", "gcd", "mod", "arg", "deg", "dim", "ker"
    )

    private val LATEX_SYMBOL_MAP = mapOf(
        "times" to "×",
        "div" to "÷",
        "pm" to "±",
        "mp" to "∓",
        "cdot" to "·",
        "ast" to "*",
        "circ" to "∘",
        "degree" to "°",
        "le" to "≤",
        "leq" to "≤",
        "ge" to "≥",
        "geq" to "≥",
        "ne" to "≠",
        "neq" to "≠",
        "approx" to "≈",
        "equiv" to "≡",
        "sim" to "∼",
        "simeq" to "≃",
        "cong" to "≅",
        "propto" to "∝",
        "ll" to "≪",
        "gg" to "≫",
        "infty" to "∞",
        "to" to "→",
        "rightarrow" to "→",
        "leftarrow" to "←",
        "leftrightarrow" to "↔",
        "Rightarrow" to "⇒",
        "implies" to "⇒",
        "Leftarrow" to "⇐",
        "impliedby" to "⇐",
        "Leftrightarrow" to "⇔",
        "iff" to "⇔",
        "mapsto" to "↦",
        "sum" to "∑",
        "prod" to "∏",
        "int" to "∫",
        "iint" to "∬",
        "oint" to "∮",
        "partial" to "∂",
        "nabla" to "∇",
        "in" to "∈",
        "notin" to "∉",
        "ni" to "∋",
        "cup" to "∪",
        "cap" to "∩",
        "subset" to "⊂",
        "subseteq" to "⊆",
        "supset" to "⊃",
        "supseteq" to "⊇",
        "emptyset" to "∅",
        "varnothing" to "∅",
        "forall" to "∀",
        "exists" to "∃",
        "nexists" to "∄",
        "neg" to "¬",
        "lnot" to "¬",
        "land" to "∧",
        "wedge" to "∧",
        "lor" to "∨",
        "vee" to "∨",
        "because" to "∵",
        "therefore" to "∴",
        "angle" to "∠",
        "triangle" to "△",
        "perp" to "⊥",
        "parallel" to "∥",
        "prime" to "′",
        "dots" to "...",
        "ldots" to "...",
        "cdots" to "···",
        "alpha" to "α",
        "beta" to "β",
        "gamma" to "γ",
        "delta" to "δ",
        "epsilon" to "ε",
        "varepsilon" to "ε",
        "zeta" to "ζ",
        "eta" to "η",
        "theta" to "θ",
        "vartheta" to "θ",
        "iota" to "ι",
        "kappa" to "κ",
        "varkappa" to "κ",
        "lambda" to "λ",
        "mu" to "μ",
        "nu" to "ν",
        "xi" to "ξ",
        "omicron" to "ο",
        "pi" to "π",
        "varpi" to "π",
        "rho" to "ρ",
        "varrho" to "ρ",
        "sigma" to "σ",
        "varsigma" to "σ",
        "tau" to "τ",
        "upsilon" to "υ",
        "phi" to "φ",
        "varphi" to "φ",
        "chi" to "χ",
        "psi" to "ψ",
        "omega" to "ω",
        "Gamma" to "Γ",
        "Delta" to "Δ",
        "Theta" to "Θ",
        "Lambda" to "Λ",
        "Xi" to "Ξ",
        "Pi" to "Π",
        "Sigma" to "Σ",
        "Upsilon" to "Υ",
        "Phi" to "Φ",
        "Psi" to "Ψ",
        "Omega" to "Ω"
    )

    private val SIMPLE_SINGLE_SYMBOLS = setOf('∞', '′', '∅', '∂', '∇')

    private val TRUE_TOKENS = setOf("对", "正确", "是", "true", "t", "yes", "y", "√", "\u2713", "\u2714")
    private val FALSE_TOKENS = setOf("错", "错误", "否", "false", "f", "no", "n", "×", "\u2717", "\u2718", "\u2715")

    fun flattenLatexForTiku(src: String): String {
        if (src.isBlank()) return ""
        val withoutImages = src.replace(IMAGE_PLACEHOLDER_REGEX, " ")
        val flattened = processDelimitedMath(withoutImages)
        return flattened.replace(MULTI_WHITESPACE_REGEX, " ").trim()
    }

    private fun processDelimitedMath(src: String): String {
        val out = StringBuilder(src.length)
        var segmentStart = 0
        var i = 0

        fun flushOutside(endExclusive: Int) {
            if (endExclusive > segmentStart) {
                out.append(flattenMathTokens(src.substring(segmentStart, endExclusive), inDelimitedMath = false))
            }
        }

        while (i < src.length) {
            if (src.startsWith("$$", i) && !isEscaped(src, i)) {
                val close = findClosingDelimiter(src, i + 2, "$$")
                if (close >= 0) {
                    flushOutside(i)
                    val inner = src.substring(i + 2, close).replace(LATEX_LINEBREAK_REGEX, " ").trim()
                    out.append(flattenMathTokens(inner, inDelimitedMath = true))
                    i = close + 2
                    segmentStart = i
                    continue
                }
                i += 2
                continue
            }
            if (src.startsWith("\\[", i) && !isEscaped(src, i)) {
                val close = findClosingDelimiter(src, i + 2, "\\]")
                if (close >= 0) {
                    flushOutside(i)
                    val inner = src.substring(i + 2, close).replace(LATEX_LINEBREAK_REGEX, " ").trim()
                    out.append(flattenMathTokens(inner, inDelimitedMath = true))
                    i = close + 2
                    segmentStart = i
                    continue
                }
                i += 2
                continue
            }
            if (src.startsWith("\\(", i) && !isEscaped(src, i)) {
                val close = findClosingDelimiter(src, i + 2, "\\)")
                if (close >= 0) {
                    flushOutside(i)
                    val inner = src.substring(i + 2, close).replace(LATEX_LINEBREAK_REGEX, " ").trim()
                    out.append(flattenMathTokens(inner, inDelimitedMath = true))
                    i = close + 2
                    segmentStart = i
                    continue
                }
                i += 2
                continue
            }
            if (src[i] == '$' && !isEscaped(src, i)) {
                val close = findClosingInlineDollar(src, i)
                if (close > i) {
                    flushOutside(i)
                    val inner = src.substring(i + 1, close).trim()
                    out.append(flattenMathTokens(inner, inDelimitedMath = true))
                    i = close + 1
                    segmentStart = i
                    continue
                }
            }
            i++
        }
        flushOutside(src.length)
        return out.toString()
    }

    private fun flattenMathTokens(s: String, inDelimitedMath: Boolean): String {
        if (s.isEmpty()) return ""
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                val next = s[i + 1]
                if (next == '\\') {
                    sb.append("\\\\")
                    i += 2
                    continue
                }
                if (next == '{' || next == '}') {
                    sb.append(next)
                    i += 2
                    continue
                }
                if (next == '|') {
                    sb.append('‖')
                    i += 2
                    continue
                }
                if (next == ',' || next == ';' || next == ':') {
                    sb.append(' ')
                    i += 2
                    continue
                }
                if (next == '!') {
                    i += 2
                    continue
                }
                if (inDelimitedMath && (next == '%' || next == '&' || next == '#' || next == '_' || next == '$' || next == ' ')) {
                    sb.append(next)
                    i += 2
                    continue
                }
                if (next.isAsciiLetter()) {
                    var cmdEnd = i + 1
                    while (cmdEnd < s.length && s[cmdEnd].isAsciiLetter()) {
                        cmdEnd++
                    }
                    val cmd = s.substring(i + 1, cmdEnd)
                    when {
                        cmd == "left" || cmd == "right" -> {
                            i = if (cmdEnd < s.length && s[cmdEnd] == '.') cmdEnd + 1 else cmdEnd
                            continue
                        }
                        cmd in LAYOUT_STRIP_COMMANDS -> {
                            i = cmdEnd
                            continue
                        }
                        cmd == "quad" || cmd == "qquad" -> {
                            sb.append(' ')
                            i = cmdEnd
                            continue
                        }
                        cmd in UNWRAP_COMMANDS -> {
                            var argIdx = cmdEnd
                            if (cmd == "operatorname" && argIdx < s.length && s[argIdx] == '*') {
                                argIdx++
                            }
                            argIdx = skipHorizontalSpaces(s, argIdx)
                            if (argIdx < s.length && s[argIdx] == '{') {
                                val braced = readBalancedBraces(s, argIdx)
                                if (braced != null) {
                                    val innerFlat = if (inDelimitedMath) {
                                        flattenMathTokens(braced.first, true)
                                    } else {
                                        processDelimitedMath(braced.first)
                                    }
                                    sb.append(innerFlat)
                                    i = braced.second
                                    continue
                                }
                            }
                            sb.append('\\').append(cmd)
                            i = cmdEnd
                            continue
                        }
                        cmd == "frac" || cmd == "dfrac" || cmd == "tfrac" || cmd == "cfrac" -> {
                            val numArg = readLatexArg(s, cmdEnd)
                            val denArg = if (numArg != null) readLatexArg(s, numArg.second) else null
                            if (numArg != null && denArg != null) {
                                val num = flattenMathTokens(numArg.first, inDelimitedMath).trim()
                                val den = flattenMathTokens(denArg.first, inDelimitedMath).trim()
                                sb.append(formatFraction(num, den))
                                i = denArg.second
                                continue
                            }
                            sb.append('\\').append(cmd)
                            i = cmdEnd
                            continue
                        }
                        cmd == "sqrt" -> {
                            val optIdx = skipHorizontalSpaces(s, cmdEnd)
                            if (optIdx < s.length && s[optIdx] == '[') {
                                val bracket = readBalancedBrackets(s, optIdx)
                                val radArg = if (bracket != null) readLatexArg(s, bracket.second) else null
                                if (bracket != null && radArg != null) {
                                    val deg = flattenMathTokens(bracket.first, inDelimitedMath).trim()
                                    val rad = flattenMathTokens(radArg.first, inDelimitedMath).trim()
                                    if (deg.isEmpty() || deg == "2") {
                                        sb.append('√').append(wrapIfCompound(rad))
                                    } else {
                                        sb.append(wrapIfCompound(deg)).append("次根号").append(wrapIfCompound(rad))
                                    }
                                    i = radArg.second
                                    continue
                                }
                            } else {
                                val radArg = readLatexArg(s, cmdEnd)
                                if (radArg != null) {
                                    val rad = flattenMathTokens(radArg.first, inDelimitedMath).trim()
                                    sb.append('√').append(wrapIfCompound(rad))
                                    i = radArg.second
                                    continue
                                }
                            }
                            sb.append('\\').append(cmd)
                            i = cmdEnd
                            continue
                        }
                        cmd in MATH_FUNC_COMMANDS -> {
                            sb.append(cmd)
                            if (cmdEnd < s.length && s[cmdEnd] == '\\') {
                                sb.append(' ')
                            }
                            i = cmdEnd
                            continue
                        }
                        LATEX_SYMBOL_MAP.containsKey(cmd) -> {
                            sb.append(LATEX_SYMBOL_MAP.getValue(cmd))
                            i = cmdEnd
                            continue
                        }
                        else -> {
                            sb.append('\\').append(cmd)
                            i = cmdEnd
                            continue
                        }
                    }
                }
            }

            if (ch == '^' || ch == '_') {
                val k = skipHorizontalSpaces(s, i + 1)
                if (ch == '^') {
                    if (k < s.length && s[k] == '{') {
                        val braced = readBalancedBraces(s, k)
                        if (braced != null) {
                            val trimmedInner = braced.first.trim()
                            if (trimmedInner == "\\circ" || trimmedInner == "\\degree") {
                                sb.append('°')
                                i = braced.second
                                continue
                            }
                        }
                    } else if (s.startsWith("\\circ", k) && !isAsciiLetterAt(s, k + 5)) {
                        sb.append('°')
                        i = k + 5
                        continue
                    }
                }
                if (k < s.length && s[k] == '{') {
                    val braced = readBalancedBraces(s, k)
                    if (braced != null) {
                        val flatScript = flattenMathTokens(braced.first, inDelimitedMath).trim()
                        if (flatScript.isNotEmpty()) {
                            sb.append(ch).append(wrapIfCompound(flatScript))
                        }
                        i = braced.second
                        continue
                    }
                }
                sb.append(ch)
                i++
                continue
            }

            sb.append(ch)
            i++
        }
        return sb.toString()
    }

    private fun isEscaped(s: String, index: Int): Boolean {
        var backslashes = 0
        var j = index - 1
        while (j >= 0 && s[j] == '\\') {
            backslashes++
            j--
        }
        return backslashes % 2 == 1
    }

    private fun findClosingDelimiter(s: String, start: Int, delim: String): Int {
        var i = start
        while (i <= s.length - delim.length) {
            if (s.startsWith(delim, i) && !isEscaped(s, i)) {
                return i
            }
            i++
        }
        return -1
    }

    private fun findClosingInlineDollar(s: String, openIdx: Int): Int {
        val start = openIdx + 1
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

    private fun skipHorizontalSpaces(s: String, start: Int): Int {
        var idx = start
        while (idx < s.length && (s[idx] == ' ' || s[idx] == '\t')) {
            idx++
        }
        return idx
    }

    private fun isAsciiLetterAt(s: String, idx: Int): Boolean =
        idx in s.indices && s[idx].isAsciiLetter()

    private fun Char.isAsciiLetter(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z'

    private fun readBalancedBraces(s: String, openBraceIdx: Int): Pair<String, Int>? {
        if (openBraceIdx !in s.indices || s[openBraceIdx] != '{') return null
        var depth = 1
        var i = openBraceIdx + 1
        while (i < s.length) {
            val c = s[i]
            if (c == '\\') {
                val next = s.getOrNull(i + 1)
                if (next == '{' || next == '}' || next == '\\') {
                    i += 2
                    continue
                }
            } else if (c == '{') {
                depth++
            } else if (c == '}') {
                depth--
                if (depth == 0) {
                    return s.substring(openBraceIdx + 1, i) to (i + 1)
                }
            }
            i++
        }
        return null
    }

    private fun readBalancedBrackets(s: String, openBracketIdx: Int): Pair<String, Int>? {
        if (openBracketIdx !in s.indices || s[openBracketIdx] != '[') return null
        var braceDepth = 0
        var bracketDepth = 1
        var i = openBracketIdx + 1
        while (i < s.length) {
            val c = s[i]
            if (c == '\\') {
                i += if (i + 1 < s.length) 2 else 1
                continue
            }
            when {
                c == '{' -> braceDepth++
                c == '}' && braceDepth > 0 -> braceDepth--
                c == '[' && braceDepth == 0 -> bracketDepth++
                c == ']' && braceDepth == 0 -> {
                    bracketDepth--
                    if (bracketDepth == 0) {
                        return s.substring(openBracketIdx + 1, i) to (i + 1)
                    }
                }
            }
            i++
        }
        return null
    }

    private fun readLatexArg(s: String, start: Int): Pair<String, Int>? {
        val idx = skipHorizontalSpaces(s, start)
        if (idx >= s.length) return null
        if (s[idx] == '{') {
            return readBalancedBraces(s, idx)
        }
        if (s[idx].isLetterOrDigit()) {
            return s.substring(idx, idx + 1) to (idx + 1)
        }
        return null
    }

    private fun formatFraction(num: String, den: String): String {
        if (num.isEmpty() && den.isEmpty()) return ""
        return "${wrapIfCompound(num)}/${wrapIfCompound(den)}"
    }

    private fun wrapIfCompound(expr: String): String {
        val t = expr.trim()
        if (t.isEmpty() || isSimpleToken(t) || isWrappedInOuterParens(t)) {
            return t
        }
        return "($t)"
    }

    private fun isSimpleToken(token: String): Boolean {
        if (token.isEmpty()) return true
        if (token.all { it.isLetterOrDigit() }) return true
        return token.length == 1 && (token[0].isLetterOrDigit() || token[0] in SIMPLE_SINGLE_SYMBOLS)
    }

    private fun isWrappedInOuterParens(token: String): Boolean {
        if (token.length < 2 || token.first() != '(' || token.last() != ')') return false
        var depth = 0
        for (i in token.indices) {
            when (token[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0 && i < token.lastIndex) return false
                }
            }
        }
        return depth == 0
    }

    fun normalizeQuestionText(src: String): String {
        if (src.isBlank()) return ""
        var s = src
            .replace('\u00A0', ' ')
            .replace('\u3000', ' ')
            .replace(Regex("""[\u200B-\u200D\uFEFF]"""), "")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        s = flattenLatexForTiku(s)
        s = s.replace(Regex("""[ \t\n]+"""), " ").trim()
        // Strip leading question index and optional leading type/trailing score tags iteratively.
        var prev: String
        do {
            prev = s
            s = s.replaceFirst(QUESTION_NUMBER_PREFIX_REGEX, "").trim()
            s = s.replaceFirst(LEADING_TYPE_TAG_REGEX, "").trim()
        } while (s != prev && s.isNotEmpty())
        s = s.replaceFirst(TRAILING_SCORE_TAG_REGEX, "").trim()
        return if (s.length > MAX_QUESTION_CHARS) s.substring(0, MAX_QUESTION_CHARS).trim() else s
    }

    fun stripOptionPrefix(option: String): String {
        val cleaned = option
            .replace('\u00A0', ' ')
            .replace('\u3000', ' ')
            .replace(Regex("""[\u200B-\u200D\uFEFF]"""), "")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (cleaned.isEmpty()) return ""
        val withoutAlpha = cleaned.replaceFirst(OPTION_PREFIX_REGEX, "")
        val withoutParen = if (withoutAlpha != cleaned) {
            withoutAlpha
        } else {
            cleaned.replaceFirst(PAREN_OPTION_PREFIX_REGEX, "")
        }
        return flattenLatexForTiku(withoutParen).trim()
    }

    fun extract(
        rawText: String,
        domQuestions: List<TikuSearchRequest> = emptyList()
    ): List<TikuSearchRequest> {
        if (domQuestions.isNotEmpty()) {
            val cleanedDom = cleanAndDeduplicate(domQuestions, MAX_DOM_ITEMS)
            if (cleanedDom.isNotEmpty()) {
                return cleanedDom
            }
        }
        if (rawText.isBlank()) return emptyList()

        val blocks = splitQuestionBlocks(rawText)
        val parsed = blocks.mapNotNull { parseSingleBlock(it) }
        return cleanAndDeduplicate(parsed, MAX_RAW_ITEMS)
    }

    private fun cleanAndDeduplicate(
        items: List<TikuSearchRequest>,
        limit: Int
    ): List<TikuSearchRequest> {
        val out = ArrayList<TikuSearchRequest>(items.size.coerceAtMost(limit))
        val seen = HashSet<String>()
        for (item in items) {
            val cleanQ = normalizeQuestionText(item.question)
            if (cleanQ.isBlank()) continue
            val dedupeKey = cleanQ.lowercase()
            if (!seen.add(dedupeKey)) continue

            val cleanOpts = item.options
                .map { stripOptionPrefix(it) }
                .filter { it.isNotBlank() }
                .take(MAX_OPTIONS)

            val inferredType = if (item.type in 0..4) {
                if (item.type == TikuQuestionType.SINGLE.code && isJudgementOptions(cleanOpts)) {
                    TikuQuestionType.JUDGEMENT.code
                } else {
                    item.type
                }
            } else {
                inferQuestionType(item.question, cleanQ, cleanOpts)
            }

            out.add(
                item.copy(
                    question = cleanQ,
                    options = cleanOpts,
                    type = inferredType
                )
            )
            if (out.size >= limit) break
        }
        return out
    }

    private fun splitQuestionBlocks(rawText: String): List<List<String>> {
        val normalized = rawText
            .replace('\u00A0', ' ')
            .replace('\u3000', ' ')
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val lines = normalized.lines()

        val startIndices = ArrayList<Int>()
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.isEmpty()) continue
            if (OPTION_PREFIX_REGEX.containsMatchIn(line)) continue
            if (MULTI_QUESTION_START_REGEX.matches(line)) {
                startIndices.add(i)
            }
        }

        if (startIndices.size <= 1) {
            return listOf(lines)
        }

        val blocks = ArrayList<List<String>>(startIndices.size)
        for (idx in startIndices.indices) {
            val start = startIndices[idx]
            val end = if (idx + 1 < startIndices.size) startIndices[idx + 1] else lines.size
            blocks.add(lines.subList(start, end))
        }
        return blocks
    }

    private fun parseSingleBlock(lines: List<String>): TikuSearchRequest? {
        val nonEmptyLines = lines.map { it.trim() }.filter { it.isNotEmpty() }
        if (nonEmptyLines.isEmpty()) return null

        val stemLines = ArrayList<String>()
        val optionLines = ArrayList<String>()
        var inOptionsSection = false

        for (line in nonEmptyLines) {
            when {
                OPTION_PREFIX_REGEX.containsMatchIn(line) || PAREN_OPTION_PREFIX_REGEX.containsMatchIn(line) -> {
                    inOptionsSection = true
                    val inlineParts = splitInlineOptions(line)
                    if (inlineParts.size > 1) {
                        optionLines.addAll(inlineParts)
                    } else {
                        optionLines.add(line)
                    }
                }
                !inOptionsSection && hasInlineOptionSequence(line) -> {
                    val split = splitStemAndInlineOptions(line)
                    if (split != null) {
                        if (split.first.isNotBlank()) {
                            stemLines.add(split.first)
                        }
                        optionLines.addAll(split.second)
                        inOptionsSection = true
                    } else {
                        stemLines.add(line)
                    }
                }
                inOptionsSection && isBareJudgementOption(line) -> {
                    optionLines.add(line)
                }
                !inOptionsSection -> {
                    stemLines.add(line)
                }
                else -> {
                    // Continuation of previous option when wrapped across lines.
                    if (optionLines.isNotEmpty()) {
                        val lastIdx = optionLines.lastIndex
                        optionLines[lastIdx] = "${optionLines[lastIdx]} $line"
                    }
                }
            }
        }

        // Handle judgement options written on bare lines without A./B. prefixes (e.g. stem + "正确" + "错误").
        if (optionLines.isEmpty() && stemLines.size >= 3) {
            val tailTwo = stemLines.takeLast(2)
            if (isJudgementOptions(tailTwo)) {
                optionLines.addAll(tailTwo)
                repeat(2) { stemLines.removeAt(stemLines.lastIndex) }
            }
        } else if (optionLines.isEmpty() && stemLines.size == 2) {
            val maybeSplit = splitInlineJudgementLine(stemLines[1])
            if (maybeSplit != null) {
                optionLines.addAll(maybeSplit)
                stemLines.removeAt(1)
            }
        }

        val rawStem = stemLines.joinToString(" ")
        val cleanStem = normalizeQuestionText(rawStem)
        if (cleanStem.isBlank()) return null

        val cleanOptions = optionLines
            .map { stripOptionPrefix(it) }
            .filter { it.isNotBlank() }
            .take(MAX_OPTIONS)

        val rawBlockText = nonEmptyLines.joinToString("\n")
        val type = inferQuestionType(rawBlockText, cleanStem, cleanOptions)
        return TikuSearchRequest(
            question = cleanStem,
            options = cleanOptions,
            type = type
        )
    }

    private fun hasInlineOptionSequence(line: String): Boolean {
        val matches = INLINE_OPTION_MARKER_REGEX.findAll(line).toList()
        if (matches.size < 2) return false
        val letters = matches.map { it.groupValues[1].uppercase()[0] }
        return letters[0] == 'A' && letters[1] == 'B'
    }

    private fun splitInlineOptions(line: String): List<String> {
        val matches = INLINE_OPTION_MARKER_REGEX.findAll(line).toList()
        if (matches.size < 2) return listOf(line)
        val letters = matches.map { it.groupValues[1].uppercase()[0] }
        if (letters[0] != 'A' || letters[1] != 'B') return listOf(line)
        val result = ArrayList<String>(matches.size)
        for (i in matches.indices) {
            val start = matches[i].range.first
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else line.length
            val segment = line.substring(start, end).trim()
            if (segment.isNotEmpty()) result.add(segment)
        }
        return result
    }

    private fun splitStemAndInlineOptions(line: String): Pair<String, List<String>>? {
        val matches = INLINE_OPTION_MARKER_REGEX.findAll(line).toList()
        if (matches.size < 2) return null
        val letters = matches.map { it.groupValues[1].uppercase()[0] }
        if (letters[0] != 'A' || letters[1] != 'B') return null
        val stem = line.substring(0, matches[0].range.first).trim()
        val opts = ArrayList<String>(matches.size)
        for (i in matches.indices) {
            val start = matches[i].range.first
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else line.length
            val segment = line.substring(start, end).trim()
            if (segment.isNotEmpty()) opts.add(segment)
        }
        return stem to opts
    }

    private fun splitInlineJudgementLine(line: String): List<String>? {
        val parts = line.trim().split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (parts.size == 2 && isJudgementOptions(parts)) return parts
        return null
    }

    private fun isBareJudgementOption(line: String): Boolean {
        val norm = stripOptionPrefix(line).lowercase()
        return norm in TRUE_TOKENS || norm in FALSE_TOKENS
    }

    private fun isJudgementOptions(options: List<String>): Boolean {
        if (options.size != 2) return false
        val a = stripOptionPrefix(options[0]).lowercase()
        val b = stripOptionPrefix(options[1]).lowercase()
        return (a in TRUE_TOKENS && b in FALSE_TOKENS) || (a in FALSE_TOKENS && b in TRUE_TOKENS)
    }

    private fun inferQuestionType(
        rawText: String,
        cleanQuestion: String,
        options: List<String>
    ): Int {
        if (rawText.contains("多选") || rawText.contains("多项选择")) {
            return TikuQuestionType.MULTIPLE.code
        }
        if (rawText.contains("判断")) {
            return TikuQuestionType.JUDGEMENT.code
        }
        if (rawText.contains("填空")) {
            return TikuQuestionType.COMPLETION.code
        }
        if (rawText.contains("问答") || rawText.contains("简答") || rawText.contains("论述")) {
            return TikuQuestionType.QA.code
        }
        if (rawText.contains("单选") || rawText.contains("单项选择")) {
            return TikuQuestionType.SINGLE.code
        }

        if (options.isNotEmpty()) {
            if (isJudgementOptions(options)) {
                return TikuQuestionType.JUDGEMENT.code
            }
            if (MULTI_CHOICE_HINT_REGEX.containsMatchIn(cleanQuestion)) {
                return TikuQuestionType.MULTIPLE.code
            }
            return TikuQuestionType.SINGLE.code
        }

        return if (BLANK_HINT_REGEX.containsMatchIn(cleanQuestion)) {
            TikuQuestionType.COMPLETION.code
        } else {
            TikuQuestionType.QA.code
        }
    }
}
