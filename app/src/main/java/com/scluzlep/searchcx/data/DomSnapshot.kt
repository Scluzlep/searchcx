package com.scluzlep.searchcx.data

import org.json.JSONArray
import org.json.JSONObject

enum class DomContextSource(val displayLabel: String) {
    SELECTION("选区"),
    QUESTIONS("题目"),
    FULL_DOM("正文")
}

data class DomImageItem(
    val index: Int,
    val url: String = "",
    val dataUrl: String = "",
    val alt: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val isFormula: Boolean = false
)

data class DomQuizItem(
    val question: String,
    val options: List<String> = emptyList(),
    val type: Int = 0,
    val inViewport: Boolean = true,
    val imageIndices: List<Int> = emptyList()
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
    val extractedQuestions: List<DomQuizItem> = emptyList(),
    val images: List<DomImageItem> = emptyList()
) {
    fun hasError(): Boolean = !error.isNullOrBlank()

    fun isSelectionMode(): Boolean = selectedText.isNotBlank()

    fun activeContextSource(): DomContextSource = when {
        selectedText.isNotBlank() -> DomContextSource.SELECTION
        extractedQuestions.any { it.question.isNotBlank() } -> DomContextSource.QUESTIONS
        else -> DomContextSource.FULL_DOM
    }

    fun preferredQuestions(): List<DomQuizItem> {
        val valid = extractedQuestions.filter { it.question.isNotBlank() }
        if (valid.isEmpty()) return emptyList()
        val visible = valid.filter { it.inViewport }
        return if (visible.isNotEmpty()) visible else valid
    }

    fun formatQuestionsText(questions: List<DomQuizItem> = preferredQuestions()): String {
        val valid = questions.filter { it.question.isNotBlank() }
        if (valid.isEmpty()) return ""
        val includeNumbers = valid.size > 1
        val sb = StringBuilder()
        for ((idx, item) in valid.withIndex()) {
            if (idx > 0) sb.append("\n\n")
            if (includeNumbers) {
                sb.append(idx + 1).append(". ")
            }
            sb.append('[').append(questionTypeLabel(item.type)).append("] ").append(item.question.trim())
            if (item.options.isNotEmpty()) {
                for ((optIdx, opt) in item.options.withIndex()) {
                    val cleanOpt = opt.trim()
                    if (cleanOpt.isEmpty()) continue
                    val letter = ('A' + optIdx)
                    sb.append('\n').append(letter).append(". ").append(cleanOpt)
                }
            }
        }
        return sb.toString().trim()
    }

    fun activeImages(): List<DomImageItem> {
        if (images.isEmpty()) return emptyList()
        val needed = LinkedHashSet<Int>()
        val source = activeContextSource()
        val scopeText = when (source) {
            DomContextSource.SELECTION -> selectedText
            DomContextSource.QUESTIONS -> {
                val chosen = preferredQuestions()
                chosen.forEach { needed.addAll(it.imageIndices) }
                formatQuestionsText(chosen)
            }
            DomContextSource.FULL_DOM -> bodyText
        }
        for (match in IMAGE_PLACEHOLDER_REGEX.findAll(scopeText)) {
            match.groupValues[1].toIntOrNull()?.let { needed.add(it) }
        }
        if (needed.isEmpty()) return emptyList()
        return images.filter { it.index in needed }.sortedBy { it.index }
    }

    /**
     * Resolves active context by priority:
     * 1) User-selected text, 2) Extracted quiz items, 3) Full page DOM text fallback only.
     * Throws [IllegalStateException] if the snapshot carries an error or contains no readable text.
     */
    fun effectiveContextText(): String {
        if (!error.isNullOrBlank()) {
            throw IllegalStateException(error)
        }
        val trimmed = when (activeContextSource()) {
            DomContextSource.SELECTION -> selectedText.trim()
            DomContextSource.QUESTIONS -> formatQuestionsText(preferredQuestions())
            DomContextSource.FULL_DOM -> bodyText.trim()
        }
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
            return "提示：检测到 $crossOriginIframeCount 个跨域 iframe，仅提取到同源文档内容。"
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
            val imgIdxArray = JSONArray()
            for (imgIdx in item.imageIndices) {
                imgIdxArray.put(imgIdx)
            }
            quizArray.put(
                JSONObject().apply {
                    put("question", item.question)
                    put("options", optArray)
                    put("type", item.type)
                    put("inViewport", item.inViewport)
                    put("imageIndices", imgIdxArray)
                }
            )
        }
        val imagesArray = JSONArray()
        for (img in images) {
            imagesArray.put(
                JSONObject().apply {
                    put("index", img.index)
                    put("url", img.url)
                    put("dataUrl", img.dataUrl)
                    put("alt", img.alt)
                    put("width", img.width)
                    put("height", img.height)
                    put("isFormula", img.isFormula)
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
            put("images", imagesArray)
        }.toString()
    }

    companion object {
        const val MAX_EXTRACTED_QUESTIONS = 15
        const val MAX_QUESTION_CHARS = 2000
        const val MAX_OPTIONS_PER_QUESTION = 12
        const val MAX_CAPTURED_IMAGES = 12
        const val MAX_INLINE_DATA_URL_CHARS = 90_000
        const val MAX_TOTAL_INLINE_DATA_CHARS = 220_000

        private val IMAGE_PLACEHOLDER_REGEX = Regex("""\[图片(\d+)\]""")

        fun questionTypeLabel(type: Int): String = when (type) {
            0 -> "单选题"
            1 -> "多选题"
            2 -> "填空题"
            3 -> "判断题"
            else -> "问答题"
        }

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
                val inViewport = obj.optBoolean("inViewport", true)
                val imgArray = obj.optJSONArray("imageIndices")
                val imageIndices = if (imgArray != null) {
                    buildList {
                        for (k in 0 until imgArray.length()) {
                            val idx = imgArray.optInt(k, -1)
                            if (idx > 0 && idx !in this) add(idx)
                        }
                    }
                } else {
                    emptyList()
                }
                items.add(
                    DomQuizItem(
                        question = question,
                        options = options,
                        type = type,
                        inViewport = inViewport,
                        imageIndices = imageIndices
                    )
                )
            }
            return items
        }

        fun parseImages(array: JSONArray?): List<DomImageItem> {
            if (array == null || array.length() == 0) return emptyList()
            val items = mutableListOf<DomImageItem>()
            val seenIndices = hashSetOf<Int>()
            var totalInlineChars = 0
            for (i in 0 until array.length()) {
                if (items.size >= MAX_CAPTURED_IMAGES) break
                val obj = array.optJSONObject(i) ?: continue
                val index = obj.optInt("index", -1)
                if (index <= 0 || !seenIndices.add(index)) continue

                val url = obj.optString("url", "").trim().take(2048)
                val rawDataUrl = obj.optString("dataUrl", "").trim()
                val safeDataUrl = if (
                    rawDataUrl.isNotEmpty() &&
                    rawDataUrl.length <= MAX_INLINE_DATA_URL_CHARS &&
                    totalInlineChars + rawDataUrl.length <= MAX_TOTAL_INLINE_DATA_CHARS
                ) {
                    totalInlineChars += rawDataUrl.length
                    rawDataUrl
                } else {
                    ""
                }
                if (url.isEmpty() && safeDataUrl.isEmpty()) continue

                items.add(
                    DomImageItem(
                        index = index,
                        url = url,
                        dataUrl = safeDataUrl,
                        alt = obj.optString("alt", "").trim().take(256),
                        width = obj.optInt("width", 0).coerceAtLeast(0),
                        height = obj.optInt("height", 0).coerceAtLeast(0),
                        isFormula = obj.optBoolean("isFormula", false)
                    )
                )
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
            val imageItems = parseImages(obj.optJSONArray("images"))
            return DomSnapshot(
                sourcePackage = obj.optString("sourcePackage", ""),
                url = obj.optString("url", ""),
                title = obj.optString("title", ""),
                selectedText = obj.optString("selectedText", ""),
                bodyText = obj.optString("bodyText", ""),
                crossOriginIframeCount = obj.optInt("crossOriginIframeCount", 0),
                capturedAtMs = obj.optLong("capturedAtMs", 0L),
                error = err,
                extractedQuestions = quizItems,
                images = imageItems
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
                extractedQuestions = emptyList(),
                images = emptyList()
            )
        }
    }
}
