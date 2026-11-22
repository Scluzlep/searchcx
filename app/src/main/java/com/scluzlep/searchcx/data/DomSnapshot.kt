package com.scluzlep.searchcx.data

import org.json.JSONObject

data class DomSnapshot(
    val sourcePackage: String,
    val url: String,
    val title: String,
    val selectedText: String,
    val bodyText: String,
    val crossOriginIframeCount: Int,
    val capturedAtMs: Long,
    val error: String? = null
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
        return JSONObject().apply {
            put("sourcePackage", sourcePackage)
            put("url", url)
            put("title", title)
            put("selectedText", selectedText)
            put("bodyText", bodyText)
            put("crossOriginIframeCount", crossOriginIframeCount)
            put("capturedAtMs", capturedAtMs)
            if (error != null) put("error", error)
        }.toString()
    }

    companion object {
        fun fromJson(raw: String): DomSnapshot {
            require(raw.isNotBlank()) { "DOM snapshot JSON must not be blank" }
            val obj = JSONObject(raw)
            val err = if (obj.has("error") && !obj.isNull("error")) {
                obj.getString("error")
            } else {
                null
            }
            return DomSnapshot(
                sourcePackage = obj.optString("sourcePackage", ""),
                url = obj.optString("url", ""),
                title = obj.optString("title", ""),
                selectedText = obj.optString("selectedText", ""),
                bodyText = obj.optString("bodyText", ""),
                crossOriginIframeCount = obj.optInt("crossOriginIframeCount", 0),
                capturedAtMs = obj.optLong("capturedAtMs", 0L),
                error = err
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
                error = message
            )
        }
    }
}
