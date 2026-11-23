package com.scluzlep.searchcx.tiku

/**
 * Formats question-bank search results into clean Markdown for overlay display
 * and concise structured reference context for downstream AI prompts.
 */
object TikuMarkdownFormatter {

    fun formatForDisplay(
        results: List<TikuSearchResult>,
        modeLabel: String = TikuMode.DIRECT_BUILTIN.displayName
    ): String {
        val label = modeLabel.trim()
        val header = if (label.isNotEmpty()) {
            "### 题库检索 · $label"
        } else {
            "### 题库检索"
        }
        if (results.isEmpty()) {
            return "$header\n\n未提取到可检索的题目。"
        }

        val hitCount = results.count { it.isHit() }
        val sb = StringBuilder()
        sb.append(header).append('\n')
        sb.append("共 ").append(results.size).append(" 题，命中 ").append(hitCount).append(" 题\n")

        for ((index, result) in results.withIndex()) {
            val typeName = TikuQuestionType.byCode(result.type).displayName
            sb.append('\n')
            sb.append("**").append(index + 1).append(". ").append(typeName).append("** ")
            sb.append(result.question).append('\n')

            if (result.isHit()) {
                val answerDisplay = formatHitAnswerLine(result)
                sb.append("- **参考答案**：").append(answerDisplay).append('\n')
                if (result.matchedProviders.isNotEmpty()) {
                    val providerNames = result.matchedProviders.joinToString("、") { id ->
                        TikuProvider.byId(id)?.displayName ?: id
                    }
                    sb.append("- **来源**：").append(providerNames).append('\n')
                }
            } else {
                val reason = result.error?.trim().orEmpty()
                if (reason.isNotEmpty()) {
                    sb.append("- **状态**：未命中，").append(reason).append('\n')
                } else {
                    sb.append("- **状态**：未命中\n")
                }
            }
        }
        return sb.toString().trimEnd()
    }

    fun formatForAiContext(results: List<TikuSearchResult>): String {
        if (results.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("[题库预检索结果]\n")
        for ((index, result) in results.withIndex()) {
            val typeName = TikuQuestionType.byCode(result.type).displayName
            sb.append('\n')
            sb.append("题目 ").append(index + 1)
                .append(" [").append(typeName).append("]：")
                .append(result.question).append('\n')

            if (result.options.isNotEmpty()) {
                val optsText = result.options.mapIndexed { idx, opt ->
                    val letter = ('A' + idx)
                    "$letter. $opt"
                }.joinToString(" | ")
                sb.append("选项：").append(optsText).append('\n')
            }

            if (result.isHit()) {
                sb.append("题库命中答案：").append(formatHitAnswerLine(result)).append('\n')
                if (result.matchedProviders.isNotEmpty()) {
                    val providerNames = result.matchedProviders.joinToString("、") { id ->
                        TikuProvider.byId(id)?.displayName ?: id
                    }
                    sb.append("命中来源：").append(providerNames).append('\n')
                }
                if (result.allAnswers.size > 1) {
                    val rawSummary = result.allAnswers.joinToString(" / ") { it.joinToString("#") }
                    sb.append("各源原始返回：").append(rawSummary).append('\n')
                }
            } else {
                val reason = result.error?.trim().orEmpty()
                if (reason.isNotEmpty()) {
                    sb.append("题库状态：未命中，").append(reason).append('\n')
                } else {
                    sb.append("题库状态：未命中\n")
                }
            }
        }
        return sb.toString().trim()
    }

    private fun formatHitAnswerLine(result: TikuSearchResult): String {
        val textAnswers = result.bestAnswer.ifEmpty {
            result.answerText.split('#').map { it.trim() }.filter { it.isNotEmpty() }
        }
        val joinedText = textAnswers.joinToString("；")
        return when {
            result.answerKeyText.isNotBlank() && joinedText.isNotBlank() ->
                "${result.answerKeyText} · $joinedText"
            result.answerKeyText.isNotBlank() -> result.answerKeyText
            joinedText.isNotBlank() -> joinedText
            else -> result.answerText
        }
    }
}
