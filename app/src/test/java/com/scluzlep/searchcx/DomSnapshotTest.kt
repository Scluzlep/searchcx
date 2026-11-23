package com.scluzlep.searchcx.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DomSnapshotTest {

    @Test
    fun roundTripAndSelectionPriority() {
        val snap = DomSnapshot(
            sourcePackage = "com.example.browser",
            url = "https://example.com/page",
            title = "Sample Page",
            selectedText = "用户选中的题目片段",
            bodyText = "整页完整正文内容",
            crossOriginIframeCount = 1,
            capturedAtMs = 1700000000000L,
            error = null
        )

        val restored = DomSnapshot.fromJson(snap.toJson())
        assertEquals(snap, restored)
        assertTrue(restored.isSelectionMode())
        assertEquals("用户选中的题目片段", restored.effectiveContextText())
        assertTrue(restored.warningMessage()!!.contains("1 个跨域 iframe"))
    }

    @Test
    fun fallsBackToBodyWhenSelectionIsBlank() {
        val snap = DomSnapshot(
            sourcePackage = "com.example.app",
            url = "https://example.com",
            title = "Title",
            selectedText = "   ",
            bodyText = "  可见正文内容  ",
            crossOriginIframeCount = 0,
            capturedAtMs = 123L
        )
        assertEquals("可见正文内容", snap.effectiveContextText())
        assertNull(snap.warningMessage())
    }

    @Test
    fun failsFastWhenErrorOrCrossOriginEmpty() {
        val errSnap = DomSnapshot.errorSnapshot("com.native.app", "当前前台进程未检测到活跃 WebView")
        try {
            errSnap.effectiveContextText()
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("未检测到活跃 WebView"))
        }

        val emptyIframeSnap = DomSnapshot(
            sourcePackage = "com.example.app",
            url = "https://example.com",
            title = "Frame Host",
            selectedText = "",
            bodyText = "",
            crossOriginIframeCount = 2,
            capturedAtMs = 456L
        )
        try {
            emptyIframeSnap.effectiveContextText()
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("2 个跨域 iframe"))
        }
    }

    @Test
    fun roundTripsExtractedQuizItemsAcrossAllQuestionTypes() {
        val quizItems = listOf(
            DomQuizItem(
                question = "下列哪项是 HTTP 幂等方法？",
                options = listOf("GET", "POST", "CONNECT"),
                type = 0
            ),
            DomQuizItem(
                question = "以下属于 Kotlin 协程调度器的有哪些？",
                options = listOf("Dispatchers.Main", "Dispatchers.IO", "Dispatchers.Default"),
                type = 1
            ),
            DomQuizItem(
                question = "TCP 三次握手建立连接，____ 次挥手断开连接。",
                options = emptyList(),
                type = 2
            ),
            DomQuizItem(
                question = "Java 中 String 是不可变类。",
                options = listOf("正确", "错误"),
                type = 3
            ),
            DomQuizItem(
                question = "简述 B+ 树相比 B 树在数据库索引中的优势。",
                options = emptyList(),
                type = 4
            )
        )
        val snap = DomSnapshot(
            sourcePackage = "com.chaoxing.mobile",
            url = "https://mooc.example.com/work",
            title = "章节测验",
            selectedText = "",
            bodyText = "1. 下列哪项是 HTTP 幂等方法？",
            crossOriginIframeCount = 0,
            capturedAtMs = 1700000123456L,
            error = null,
            extractedQuestions = quizItems
        )

        val restored = DomSnapshot.fromJson(snap.toJson())
        assertEquals(snap, restored)
        assertEquals(5, restored.extractedQuestions.size)
        assertEquals(listOf("GET", "POST", "CONNECT"), restored.extractedQuestions[0].options)
        assertEquals(3, restored.extractedQuestions[3].type)
        assertTrue(restored.extractedQuestions[4].options.isEmpty())
    }

    @Test
    fun parsesLegacyJsonWithoutExtractedQuestionsAndEnforcesBounds() {
        val legacyJson = """
            {
              "sourcePackage": "com.example.legacy",
              "url": "https://example.com/old",
              "title": "Legacy Snapshot",
              "selectedText": "",
              "bodyText": "Legacy body text",
              "crossOriginIframeCount": 0,
              "capturedAtMs": 999
            }
        """.trimIndent()
        val legacyRestored = DomSnapshot.fromJson(legacyJson)
        assertEquals("Legacy body text", legacyRestored.bodyText)
        assertTrue(legacyRestored.extractedQuestions.isEmpty())

        val longQuestion = "Q".repeat(2500)
        val manyOptions = (1..20).map { "\"Option $it\"" }.joinToString(",")
        val manyItems = (1..20).joinToString(",") { idx ->
            """{"question":"Question $idx","options":["A","B"],"type":99}"""
        }
        val boundedJson = """
            {
              "sourcePackage": "com.example.app",
              "url": "https://example.com",
              "title": "Bounded",
              "selectedText": "",
              "bodyText": "text",
              "crossOriginIframeCount": 0,
              "capturedAtMs": 1000,
              "extractedQuestions": [
                {"question": "", "options": ["A"], "type": 0},
                {"question": "  $longQuestion  ", "options": [$manyOptions], "type": -3},
                $manyItems
              ]
            }
        """.trimIndent()
        val bounded = DomSnapshot.fromJson(boundedJson)
        assertEquals(DomSnapshot.MAX_EXTRACTED_QUESTIONS, bounded.extractedQuestions.size)
        assertEquals(DomSnapshot.MAX_QUESTION_CHARS, bounded.extractedQuestions[0].question.length)
        assertEquals(DomSnapshot.MAX_OPTIONS_PER_QUESTION, bounded.extractedQuestions[0].options.size)
        assertEquals(0, bounded.extractedQuestions[0].type)
        assertEquals(4, bounded.extractedQuestions[1].type)
    }
}
