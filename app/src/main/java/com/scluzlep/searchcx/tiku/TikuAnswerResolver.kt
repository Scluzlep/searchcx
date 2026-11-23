package com.scluzlep.searchcx.tiku

/**
 * Normalizes and resolves multi-provider answers against request options using
 * tikuAdapter formatting/similarity rules and OCS plain-letter helpers.
 */
object TikuAnswerResolver {

    const val DICE_MATCH_THRESHOLD = 0.70

    data class ProviderAnswer(
        val providerId: String,
        val answers: List<String>
    )

    private val OPTION_PREFIX_WITH_TEXT_REGEX = Regex("""^[A-Ha-h][.．:：、)）]\s*(.+)$""")
    private val PAREN_OPTION_PREFIX_WITH_TEXT_REGEX = Regex("""^[(（\[]\s*[A-Ha-h]\s*[)）\]]\s*(.+)$""")
    private val ZERO_WIDTH_REGEX = Regex("""[\u200B-\u200D\uFEFF]""")
    private val WHITESPACE_REGEX = Regex("""\s+""")
    private val PLAIN_SEPARATOR_REGEX = Regex("""[,，.。．、 #;；\s]+""")
    private val TRAILING_PUNCT_CHARS = charArrayOf(',', '.', '?', ':', '!', ';')

    private val TRUE_TOKENS = setOf(
        "正确", "对", "是", "true", "t", "yes", "y", "1", "√", "\u2713", "\u2714", "对的", "right", "correct"
    )
    private val FALSE_TOKENS = setOf(
        "错误", "错", "否", "false", "f", "no", "n", "0", "×", "\u2717", "\u2718", "\u2715", "错的", "wrong", "incorrect"
    )

    fun toHalfWidth(src: String): String {
        if (src.isEmpty()) return ""
        val sb = StringBuilder(src.length)
        for (ch in src) {
            when {
                ch == '\u3000' || ch == '\u00A0' -> sb.append(' ')
                ch in '\uFF01'..'\uFF5E' -> sb.append((ch.code - 0xFEE0).toChar())
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun normalizePunctuation(src: String): String {
        if (src.isEmpty()) return ""
        val half = toHalfWidth(src)
        val sb = StringBuilder(half.length)
        for (ch in half) {
            when (ch) {
                '、' -> sb.append(',')
                '。' -> sb.append('.')
                '；' -> sb.append(';')
                '：' -> sb.append(':')
                '？' -> sb.append('?')
                '！' -> sb.append('!')
                '（' -> sb.append('(')
                '）' -> sb.append(')')
                '【' -> sb.append('[')
                '】' -> sb.append(']')
                '“', '”' -> sb.append('"')
                '‘', '’' -> sb.append('\'')
                '《' -> sb.append('<')
                '》' -> sb.append('>')
                '—' -> sb.append('-')
                '～' -> sb.append('~')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun stripOptionPrefix(src: String): String {
        val trimmed = src.trim()
        if (trimmed.isEmpty()) return ""
        OPTION_PREFIX_WITH_TEXT_REGEX.matchEntire(trimmed)?.let {
            return it.groupValues[1].trim()
        }
        PAREN_OPTION_PREFIX_WITH_TEXT_REGEX.matchEntire(trimmed)?.let {
            return it.groupValues[1].trim()
        }
        return trimmed
    }

    fun normalizeAnswerText(src: String): String {
        if (src.isBlank()) return ""
        val cleaned = normalizePunctuation(src.replace(ZERO_WIDTH_REGEX, ""))
            .replace(WHITESPACE_REGEX, " ")
            .trim()
            .trimEnd { it.isWhitespace() || it in TRAILING_PUNCT_CHARS }
            .trim()
        return stripOptionPrefix(cleaned)
            .trimEnd { it.isWhitespace() || it in TRAILING_PUNCT_CHARS }
            .trim()
    }

    fun canonicalizeJudgement(src: String): String? {
        val norm = normalizeAnswerText(src).lowercase()
        if (norm.isEmpty()) return null
        return when {
            norm in TRUE_TOKENS -> "正确"
            norm in FALSE_TOKENS -> "错误"
            else -> null
        }
    }

    fun isPlainAnswer(answer: String): Boolean {
        val trimmed = answer.trim()
        if (trimmed.isEmpty() || trimmed.length > 8) return false
        var minCode = 0
        for (ch in trimmed) {
            if (ch !in 'A'..'Z') return false
            val code = ch.code
            if (code < minCode) return false
            minCode = code
        }
        return true
    }

    fun resolvePlainAnswer(answer: String): String? {
        val cleaned = toHalfWidth(answer.trim())
            .replace(PLAIN_SEPARATOR_REGEX, "")
            .trim()
        return if (isPlainAnswer(cleaned)) cleaned else null
    }

    fun diceSimilarity(a: String, b: String): Double {
        val s1 = normalizeComparisonKey(a)
        val s2 = normalizeComparisonKey(b)
        if (s1.isEmpty() && s2.isEmpty()) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        if (s1 == s2) return 1.0
        if (s1.length < 2 || s2.length < 2) return 0.0

        val bigrams1 = HashMap<String, Int>(s1.length)
        for (i in 0 until s1.length - 1) {
            val gram = s1.substring(i, i + 2)
            bigrams1[gram] = (bigrams1[gram] ?: 0) + 1
        }

        var intersection = 0
        for (i in 0 until s2.length - 1) {
            val gram = s2.substring(i, i + 2)
            val count = bigrams1[gram] ?: 0
            if (count > 0) {
                intersection++
                bigrams1[gram] = count - 1
            }
        }
        return (2.0 * intersection) / ((s1.length - 1) + (s2.length - 1))
    }

    fun resolve(
        request: TikuSearchRequest,
        allAnswers: List<List<String>>,
        matchedProviders: List<String> = emptyList(),
        error: String? = null
    ): TikuSearchResult {
        val wrapped = allAnswers.mapIndexed { idx, list ->
            val pid = matchedProviders.getOrNull(idx) ?: "source_$idx"
            ProviderAnswer(pid, list)
        }
        return resolve(request, wrapped, error)
    }

    fun resolve(
        request: TikuSearchRequest,
        providerAnswers: List<ProviderAnswer>,
        error: String? = null
    ): TikuSearchResult {
        val nonEmptyProviders = providerAnswers.mapNotNull { pa ->
            val items = pa.answers.map { it.trim() }.filter { it.isNotEmpty() }
            if (items.isEmpty()) null else ProviderAnswer(pa.providerId, items)
        }

        val allRawAnswers = nonEmptyProviders.map { it.answers }
        val matchedIds = nonEmptyProviders.map { it.providerId }.distinct()

        if (nonEmptyProviders.isEmpty()) {
            return TikuSearchResult(
                question = request.question,
                options = request.options,
                type = request.type,
                bestAnswer = emptyList(),
                answerKey = emptyList(),
                answerKeyText = "",
                answerText = "",
                answerIndex = emptyList(),
                allAnswers = emptyList(),
                matchedProviders = emptyList(),
                error = error
            )
        }

        val cleanedOptions = request.options.map { rawOpt ->
            stripOptionPrefix(normalizePunctuation(rawOpt)).ifEmpty { rawOpt.trim() }
        }
        val isJudgement = request.type == TikuQuestionType.JUDGEMENT.code ||
            isJudgementOptions(cleanedOptions)

        if (cleanedOptions.isNotEmpty()) {
            val optionVotes = ArrayList<List<Int>>()
            val fallbackVotes = ArrayList<List<String>>()

            for (pa in nonEmptyProviders) {
                val matchedIndices = matchProviderToOptions(pa.answers, cleanedOptions, isJudgement)
                if (matchedIndices.isNotEmpty()) {
                    optionVotes.add(matchedIndices)
                } else {
                    val textItems = normalizePlainItems(pa.answers, isJudgement)
                    if (textItems.isNotEmpty()) {
                        fallbackVotes.add(textItems)
                    }
                }
            }

            if (optionVotes.isNotEmpty()) {
                val winningIndices = pickMajorityIndices(optionVotes)
                val keys = winningIndices.map { ('A' + it).toString() }
                val best = winningIndices.map { cleanedOptions[it] }
                return TikuSearchResult(
                    question = request.question,
                    options = request.options,
                    type = request.type,
                    bestAnswer = best,
                    answerKey = keys,
                    answerKeyText = keys.joinToString(""),
                    answerText = best.joinToString("#"),
                    answerIndex = winningIndices,
                    allAnswers = allRawAnswers,
                    matchedProviders = matchedIds,
                    error = null
                )
            }

            if (fallbackVotes.isNotEmpty()) {
                val winningText = pickMajorityTexts(fallbackVotes)
                return TikuSearchResult(
                    question = request.question,
                    options = request.options,
                    type = request.type,
                    bestAnswer = winningText,
                    answerKey = emptyList(),
                    answerKeyText = "",
                    answerText = winningText.joinToString("#"),
                    answerIndex = emptyList(),
                    allAnswers = allRawAnswers,
                    matchedProviders = matchedIds,
                    error = null
                )
            }
        }

        val textVotes = nonEmptyProviders.mapNotNull { pa ->
            val normalized = normalizePlainItems(pa.answers, isJudgement)
            if (normalized.isEmpty()) null else normalized
        }
        val winning = if (textVotes.isNotEmpty()) pickMajorityTexts(textVotes) else emptyList()
        return TikuSearchResult(
            question = request.question,
            options = request.options,
            type = request.type,
            bestAnswer = winning,
            answerKey = emptyList(),
            answerKeyText = "",
            answerText = winning.joinToString("#"),
            answerIndex = emptyList(),
            allAnswers = allRawAnswers,
            matchedProviders = matchedIds,
            error = if (winning.isEmpty()) error else null
        )
    }

    private fun matchProviderToOptions(
        answers: List<String>,
        cleanedOptions: List<String>,
        isJudgement: Boolean
    ): List<Int> {
        // 1. In judgement questions, prioritize canonical True/False tokens (so "T"/"F" never confuse as option letters).
        if (isJudgement) {
            val judgeMatches = ArrayList<Int>()
            for (ans in answers) {
                val canonical = canonicalizeJudgement(ans) ?: continue
                val idx = cleanedOptions.indexOfFirst { canonicalizeJudgement(it) == canonical }
                if (idx >= 0) {
                    judgeMatches.add(idx)
                }
            }
            if (judgeMatches.isNotEmpty()) {
                return judgeMatches.distinct().sorted()
            }
        }

        // 2. Plain letter shortcut (e.g., ["BC"] or ["B", "C"]).
        if (answers.size == 1) {
            val plain = resolvePlainAnswer(answers[0])
            if (plain != null && !(isJudgement && (plain == "T" || plain == "F"))) {
                val mapped = plain.map { it - 'A' }
                if (mapped.all { it in cleanedOptions.indices }) {
                    return mapped.distinct().sorted()
                }
            }
        } else {
            val singleLetters = answers.mapNotNull { item ->
                val p = resolvePlainAnswer(item)
                if (p != null && p.length == 1 && !(isJudgement && (p == "T" || p == "F"))) {
                    p[0] - 'A'
                } else {
                    null
                }
            }
            if (singleLetters.size == answers.size && singleLetters.all { it in cleanedOptions.indices }) {
                return singleLetters.distinct().sorted()
            }
        }

        // 3. Text exact / glued-letter / Dice >= 0.70 match per answer token (also splitting '#' if needed).
        val expanded = ArrayList<String>(answers.size)
        for (ans in answers) {
            if (ans.contains('#') && matchSingleItemToOption(ans, cleanedOptions, isJudgement) == null) {
                val parts = ans.split('#').map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.isNotEmpty()) {
                    expanded.addAll(parts)
                    continue
                }
            }
            expanded.add(ans)
        }

        val indices = LinkedHashSet<Int>()
        for (item in expanded) {
            val idx = matchSingleItemToOption(item, cleanedOptions, isJudgement)
            if (idx != null) {
                indices.add(idx)
            }
        }
        return indices.toList().sorted()
    }

    private fun matchSingleItemToOption(
        rawAnswer: String,
        cleanedOptions: List<String>,
        isJudgement: Boolean
    ): Int? {
        if (isJudgement) {
            val canonical = canonicalizeJudgement(rawAnswer)
            if (canonical != null) {
                val judgeIdx = cleanedOptions.indexOfFirst { canonicalizeJudgement(it) == canonical }
                if (judgeIdx >= 0) return judgeIdx
            }
        }

        val plain = resolvePlainAnswer(rawAnswer)
        if (plain != null && plain.length == 1 && !(isJudgement && (plain == "T" || plain == "F"))) {
            val letterIdx = plain[0] - 'A'
            if (letterIdx in cleanedOptions.indices) return letterIdx
        }

        val normAns = normalizeAnswerText(rawAnswer)
        if (normAns.isEmpty()) return null

        val ansKey = normalizeComparisonKey(normAns)
        // Exact normalized match
        for (i in cleanedOptions.indices) {
            if (normalizeComparisonKey(cleanedOptions[i]) == ansKey) {
                return i
            }
        }

        // Glued leading option letter fallback (e.g. "A特殊侵权责任")
        val gluedStripped = stripGluedOptionLetter(normAns)
        if (gluedStripped != null) {
            val gluedKey = normalizeComparisonKey(gluedStripped)
            for (i in cleanedOptions.indices) {
                if (normalizeComparisonKey(cleanedOptions[i]) == gluedKey) {
                    return i
                }
            }
        }

        // 2-gram Dice similarity >= 0.70 fallback
        var bestIdx = -1
        var bestScore = 0.0
        for (i in cleanedOptions.indices) {
            val opt = cleanedOptions[i]
            val scoreDirect = diceSimilarity(normAns, opt)
            val scoreGlued = if (gluedStripped != null) diceSimilarity(gluedStripped, opt) else 0.0
            val score = maxOf(scoreDirect, scoreGlued)
            if (score >= DICE_MATCH_THRESHOLD && score > bestScore) {
                bestScore = score
                bestIdx = i
            }
        }
        return if (bestIdx >= 0) bestIdx else null
    }

    private fun stripGluedOptionLetter(normText: String): String? {
        if (normText.length <= 1) return null
        val first = normText[0].uppercaseChar()
        if (first !in 'A'..'H') return null
        val rest = normText.substring(1).trim()
        return rest.ifEmpty { null }
    }

    private fun normalizePlainItems(answers: List<String>, isJudgement: Boolean): List<String> {
        val out = LinkedHashSet<String>()
        for (raw in answers) {
            val formatted = if (isJudgement) {
                canonicalizeJudgement(raw) ?: normalizeAnswerText(raw)
            } else {
                normalizeAnswerText(raw)
            }
            if (formatted.isNotEmpty()) {
                out.add(formatted)
            }
        }
        return out.toList()
    }

    private fun pickMajorityIndices(votes: List<List<Int>>): List<Int> {
        val counts = LinkedHashMap<List<Int>, Int>()
        for (v in votes) {
            counts[v] = (counts[v] ?: 0) + 1
        }
        return counts.entries.maxByOrNull { it.value }?.key ?: votes.first()
    }

    private fun pickMajorityTexts(votes: List<List<String>>): List<String> {
        val counts = LinkedHashMap<List<String>, Pair<Int, List<String>>>()
        for (items in votes) {
            val key = items.map { normalizeComparisonKey(it) }
            val existing = counts[key]
            if (existing == null) {
                counts[key] = 1 to items
            } else {
                counts[key] = (existing.first + 1) to existing.second
            }
        }
        return counts.values.maxByOrNull { it.first }?.second ?: votes.first()
    }

    private fun isJudgementOptions(options: List<String>): Boolean {
        if (options.size != 2) return false
        val first = canonicalizeJudgement(options[0]) ?: return false
        val second = canonicalizeJudgement(options[1]) ?: return false
        return first != second
    }

    private fun normalizeComparisonKey(src: String): String {
        val norm = normalizeAnswerText(src)
        if (norm.isEmpty()) return ""
        return norm.replace(" ", "").lowercase()
    }
}
