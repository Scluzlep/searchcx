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

    private val TRUE_TOKENS = setOf("对", "正确", "是", "true", "t", "yes", "y", "√", "\u2713", "\u2714")
    private val FALSE_TOKENS = setOf("错", "错误", "否", "false", "f", "no", "n", "×", "\u2717", "\u2718", "\u2715")

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
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (cleaned.isEmpty()) return ""
        val withoutAlpha = cleaned.replaceFirst(OPTION_PREFIX_REGEX, "")
        val withoutParen = if (withoutAlpha != cleaned) {
            withoutAlpha
        } else {
            cleaned.replaceFirst(PAREN_OPTION_PREFIX_REGEX, "")
        }
        return withoutParen.trim()
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
