package com.scluzlep.searchcx.data

import org.json.JSONArray
import org.json.JSONObject

data class DomQuizItem(
    val question: String,
    val options: List<String> = emptyList(),
    val type: Int = 0
)

data class DomSnapshot(
    val sourcePackage: String,
    val url: String,
    val title: String,
    val selectedText: String,
    val bodyText: String,
    val crossOriginIframeCount: Int,
    val capturedAtMs: Long,
    val error: String? = null,
    val extractedQuestions: List<DomQuizItem> = emptyList()
) {
    fun hasError(): Boolean = !error.isNullOrBlank()

    fun isSelectionMode(): Boolean = selectedText.isNotBlank()

    /**
     * Prefers user-selected text when non-blank; otherwise falls back to visible body text.
     * Throws [IllegalStateException] if the snapshot carries an error or contains no readable text.
     */
    fun effectiveContextText(): String {
        if (!error.isNullOrBlank()) {
            throw IllegalStateException(error)
        }
        val preferred = if (selectedText.isNotBlank()) selectedText else bodyText
        val trimmed = preferred.trim()
        if (trimmed.isEmpty()) {
            val iframeHint = if (crossOriginIframeCount > 0) {
                "：检测到 $crossOriginIframeCount 个跨域 iframe，无法读取其内部内容"
            } else {
                ""
            }
            throw IllegalStateException("页面 DOM 文本为空$iframeHint")
        }
        return trimmed
    }

    fun warningMessage(): String? {
        if (!error.isNullOrBlank()) return error
        if (crossOriginIframeCount > 0) {
            return "提示：检测到 $crossOriginIframeCount 个跨域 iframe，仅提取到主文档可见文本。"
        }
        return null
    }

    fun toJson(): String {
        val quizArray = JSONArray()
        for (item in extractedQuestions) {
            val optArray = JSONArray()
            for (opt in item.options) {
                optArray.put(opt)
            }
            quizArray.put(
                JSONObject().apply {
                    put("question", item.question)
                    put("options", optArray)
                    put("type", item.type)
                }
            )
        }
        return JSONObject().apply {
            put("sourcePackage", sourcePackage)
            put("url", url)
            put("title", title)
            put("selectedText", selectedText)
            put("bodyText", bodyText)
            put("crossOriginIframeCount", crossOriginIframeCount)
            put("capturedAtMs", capturedAtMs)
            if (error != null) put("error", error)
            put("extractedQuestions", quizArray)
        }.toString()
    }

    companion object {
        const val MAX_EXTRACTED_QUESTIONS = 15
        const val MAX_QUESTION_CHARS = 2000
        const val MAX_OPTIONS_PER_QUESTION = 12

        fun parseQuizItems(array: JSONArray?): List<DomQuizItem> {
            if (array == null || array.length() == 0) return emptyList()
            val items = mutableListOf<DomQuizItem>()
            val seenStems = hashSetOf<String>()
            for (i in 0 until array.length()) {
                if (items.size >= MAX_EXTRACTED_QUESTIONS) break
                val obj = array.optJSONObject(i) ?: continue
                val question = obj.optString("question", "").trim().take(MAX_QUESTION_CHARS)
                if (question.isEmpty() || !seenStems.add(question)) continue

                val optArray = obj.optJSONArray("options")
                val options = if (optArray != null) {
                    buildList {
                        for (j in 0 until optArray.length()) {
                            if (size >= MAX_OPTIONS_PER_QUESTION) break
                            val opt = optArray.optString(j, "").trim()
                            if (opt.isNotEmpty()) add(opt)
                        }
                    }
                } else {
                    emptyList()
                }
                val type = obj.optInt("type", 0).coerceIn(0, 4)
                items.add(DomQuizItem(question = question, options = options, type = type))
            }
            return items
        }

        fun fromJson(raw: String): DomSnapshot {
            require(raw.isNotBlank()) { "DOM snapshot JSON must not be blank" }
            val obj = JSONObject(raw)
            val err = if (obj.has("error") && !obj.isNull("error")) {
                obj.getString("error")
            } else {
                null
            }
            val quizItems = parseQuizItems(obj.optJSONArray("extractedQuestions"))
            return DomSnapshot(
                sourcePackage = obj.optString("sourcePackage", ""),
                url = obj.optString("url", ""),
                title = obj.optString("title", ""),
                selectedText = obj.optString("selectedText", ""),
                bodyText = obj.optString("bodyText", ""),
                crossOriginIframeCount = obj.optInt("crossOriginIframeCount", 0),
                capturedAtMs = obj.optLong("capturedAtMs", 0L),
                error = err,
                extractedQuestions = quizItems
            )
        }

        fun errorSnapshot(sourcePackage: String, message: String, url: String = "", title: String = ""): DomSnapshot {
            require(message.isNotBlank()) { "Error message must not be blank" }
            return DomSnapshot(
                sourcePackage = sourcePackage,
                url = url,
                title = title,
                selectedText = "",
                bodyText = "",
                crossOriginIframeCount = 0,
                capturedAtMs = System.currentTimeMillis(),
                error = message,
                extractedQuestions = emptyList()
            )
        }
    }
}
