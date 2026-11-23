package com.scluzlep.searchcx.data

import com.scluzlep.searchcx.ipc.DomChunkAssembler
import com.scluzlep.searchcx.ipc.DomIpcContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    @Test
    fun prioritizesSelectionThenVisibleQuestionsThenFullDomFallbackAndPrunesImages() {
        val visibleQuestion = DomQuizItem(
            question = "求函数 \$f(x)=\\frac{x^2}{2}\$ 的导数 [图片2]",
            options = listOf("x", "2x"),
            type = 0,
            inViewport = true,
            imageIndices = listOf(2)
        )
        val offscreenQuestion = DomQuizItem(
            question = "离屏第一题 [图片1]",
            options = listOf("甲", "乙"),
            type = 0,
            inViewport = false,
            imageIndices = listOf(1)
        )
        val allImgs = listOf(
            DomImageItem(index = 1, url = "https://cdn.example.com/offscreen.png", isFormula = false),
            DomImageItem(index = 2, url = "https://cdn.example.com/formula.png", width = 42, height = 18, isFormula = true),
            DomImageItem(index = 3, url = "https://cdn.example.com/footer_banner.png", isFormula = false)
        )

        // Priority 1: non-blank selection overrides questions & body
        val selectionSnap = DomSnapshot(
            sourcePackage = "com.example.browser",
            url = "https://exam.example.com/1",
            title = "测试页",
            selectedText = "用户选中文本 [图片3]",
            bodyText = "整页完整正文 导航条 [图片1] [图片2] [图片3]",
            crossOriginIframeCount = 0,
            capturedAtMs = 2000L,
            extractedQuestions = listOf(offscreenQuestion, visibleQuestion),
            images = allImgs
        )
        assertEquals(DomContextSource.SELECTION, selectionSnap.activeContextSource())
        assertEquals("用户选中文本 [图片3]", selectionSnap.effectiveContextText())
        assertEquals(listOf(3), selectionSnap.activeImages().map { it.index })

        // Priority 2: structured viewport questions (no full DOM dump)
        val questionSnap = selectionSnap.copy(selectedText = "")
        assertEquals(DomContextSource.QUESTIONS, questionSnap.activeContextSource())
        val ctxText = questionSnap.effectiveContextText()
        assertTrue(ctxText.contains("求函数 \$f(x)=\\frac{x^2}{2}\$ 的导数 [图片2]"))
        assertTrue(ctxText.contains("A. x"))
        assertTrue(!ctxText.contains("离屏第一题"))
        assertTrue(!ctxText.contains("导航条"))
        assertEquals(listOf(2), questionSnap.activeImages().map { it.index })

        // Round-trip preserves images & viewport flags
        val restored = DomSnapshot.fromJson(questionSnap.toJson())
        assertEquals(questionSnap, restored)

        // Priority 3: full DOM used strictly as fallback when neither selection nor questions exist
        val fallbackSnap = questionSnap.copy(extractedQuestions = emptyList())
        assertEquals(DomContextSource.FULL_DOM, fallbackSnap.activeContextSource())
        assertEquals("整页完整正文 导航条 [图片1] [图片2] [图片3]", fallbackSnap.effectiveContextText())
        assertEquals(listOf(1, 2, 3), fallbackSnap.activeImages().map { it.index })
    }

    @Test
    fun enforcesInlineImagePayloadCapWhileKeepingRemoteUrl() {
        val hugeDataUrl = "data:image/png;base64," + "A".repeat(DomSnapshot.MAX_INLINE_DATA_URL_CHARS + 100)
        val json = """
            {
              "sourcePackage": "com.example.app",
              "url": "https://example.com",
              "title": "Images",
              "selectedText": "",
              "bodyText": "题目 [图片1]",
              "crossOriginIframeCount": 0,
              "capturedAtMs": 3000,
              "images": [
                {"index": 1, "url": "https://example.com/a.png", "dataUrl": "$hugeDataUrl", "isFormula": true},
                {"index": 2, "url": "", "dataUrl": "$hugeDataUrl", "isFormula": false}
              ]
            }
        """.trimIndent()
        val parsed = DomSnapshot.fromJson(json)
        assertEquals(1, parsed.images.size)
        assertEquals("https://example.com/a.png", parsed.images[0].url)
        assertEquals("", parsed.images[0].dataUrl)
        assertTrue(parsed.images[0].isFormula)
    }

    @Test
    fun chunkAssemblerSplitsAndReassemblesOutOfOrderChunks() {
        val assembler = DomChunkAssembler()
        val largePayload = "X".repeat(DomIpcContract.MAX_CHUNK_CHARS * 2 + 123)
        val largeChunks = DomChunkAssembler.splitPayload(largePayload)
        assertEquals(3, largeChunks.size)
        assertEquals(DomIpcContract.MAX_CHUNK_CHARS, largeChunks[0].length)
        assertEquals(DomIpcContract.MAX_CHUNK_CHARS, largeChunks[1].length)
        assertEquals(123, largeChunks[2].length)

        // Out-of-order reassembly + duplicate chunk safety
        assertNull(assembler.offer("req-large", 2, largeChunks.size, largeChunks[2]))
        assertNull(assembler.offer("req-large", 0, largeChunks.size, largeChunks[0]))
        assertNull(assembler.offer("req-large", 2, largeChunks.size, largeChunks[2]))
        val assembled = assembler.offer("req-large", 1, largeChunks.size, largeChunks[1])
        assertEquals(largePayload, assembled)

        // Bucket is removed after completion, so reusing the requestId starts fresh
        val smallChunks = assembler.splitPayload("abcdefghij", maxChunkChars = 4)
        assertEquals(listOf("abcd", "efgh", "ij"), smallChunks)
        assertNull(assembler.offer("req-large", 0, 3, smallChunks[0]))
        assertNull(assembler.offer("req-large", 2, 3, smallChunks[2]))
        assertEquals("abcdefghij", assembler.offer("req-large", 1, 3, smallChunks[1]))
    }

    @Test
    fun chunkAssemblerHandlesSingleChunkExpiryAndRejections() {
        var now = 1_000L
        val assembler = DomChunkAssembler(ttlMs = 5_000L, nowMs = { now })

        // Single chunk immediate pass-through
        assertEquals("{\"ok\":true}", assembler.offer("req-single", 0, 1, "{\"ok\":true}"))
        assertEquals("legacy-single", assembler.offer("", 0, 1, "legacy-single"))

        // Validation rejections
        assertNull(assembler.offer(" ", 0, 2, "a"))
        assertNull(assembler.offer("req-bad-total", 0, DomIpcContract.MAX_CHUNK_COUNT + 1, "a"))
        assertNull(assembler.offer("req-bad-idx", 2, 2, "a"))
        assertNull(assembler.offer("req-neg-idx", -1, 2, "a"))

        // Oversize assembled payload rejection
        val halfPlus = "B".repeat(DomIpcContract.MAX_ASSEMBLED_PAYLOAD_CHARS / 2 + 1)
        assertNull(assembler.offer("req-huge", 0, 2, halfPlus))
        assertNull(assembler.offer("req-huge", 1, 2, halfPlus))

        // TTL expiry evicts stale partial buckets
        assertNull(assembler.offer("req-ttl", 0, 2, "part0-"))
        now += 5_001L
        // Stale chunk 0 was evicted, so chunk 1 alone starts a fresh bucket and returns null
        assertNull(assembler.offer("req-ttl", 1, 2, "part1"))
        // Sending chunk 0 now completes the fresh bucket
        assertEquals("part0-part1", assembler.offer("req-ttl", 0, 2, "part0-"))

        // Confirm an expired partial bucket stays discarded when only the second chunk arrives late
        assertNull(assembler.offer("req-ttl-2", 0, 2, "stale-"))
        now += 5_001L
        assertNull(assembler.offer("req-ttl-2", 1, 2, "late"))
    }

    @Test
    fun obfuscatedFontRoundTripAndWarningMessage() {
        val obfSnap = DomSnapshot(
            sourcePackage = "com.chaoxing.mobile",
            url = "https://mooc1.chaoxing.com/work",
            title = "章节测试",
            selectedText = "",
            bodyText = "正文",
            crossOriginIframeCount = 0,
            capturedAtMs = 5_000L,
            hasObfuscatedFont = true
        )
        val restored = DomSnapshot.fromJson(obfSnap.toJson())
        assertTrue(restored.hasObfuscatedFont)
        assertEquals(obfSnap, restored)

        val obfWarning = restored.warningMessage()
        assertNotNull(obfWarning)
        assertTrue(obfWarning!!.contains("混淆字体"))
        assertTrue(obfWarning.contains("截屏框选"))

        val combinedWarning = restored.copy(crossOriginIframeCount = 2).warningMessage()!!
        assertTrue(combinedWarning.contains("混淆字体"))
        assertTrue(combinedWarning.contains("2 个跨域 iframe"))

        val errorPriority = restored.copy(error = "抓取失败").warningMessage()
        assertEquals("抓取失败", errorPriority)

        val legacyJson = """
            {
              "sourcePackage": "com.example",
              "url": "https://example.com",
              "title": "Old",
              "selectedText": "",
              "bodyText": "hello",
              "crossOriginIframeCount": 0,
              "capturedAtMs": 1
            }
        """.trimIndent()
        assertFalse(DomSnapshot.fromJson(legacyJson).hasObfuscatedFont)
    }

    @Test
    fun contextSourcesQuestionSelectionAndImageExclusions() {
        val q0 = DomQuizItem(
            question = "第一题 [图片1]",
            options = listOf("A1", "B1"),
            type = 0,
            inViewport = false,
            imageIndices = listOf(1)
        )
        val q1 = DomQuizItem(
            question = "第二题 [图片2]",
            options = listOf("A2", "B2"),
            type = 0,
            inViewport = true,
            imageIndices = listOf(2)
        )
        val q2 = DomQuizItem(
            question = "第三题 [图片4]",
            options = listOf("A3", "B3"),
            type = 1,
            inViewport = true,
            imageIndices = listOf(4)
        )
        val imgs = listOf(
            DomImageItem(index = 1, url = "https://example.com/1.png"),
            DomImageItem(index = 2, url = "https://example.com/2.png"),
            DomImageItem(index = 3, url = "https://example.com/3.png"),
            DomImageItem(index = 4, url = "https://example.com/4.png")
        )
        val snap = DomSnapshot(
            sourcePackage = "com.example.browser",
            url = "https://example.com/exam",
            title = "测验",
            selectedText = "选中题干 [图片3]",
            bodyText = "整页正文 [图片1] [图片2] [图片3] [图片4]",
            crossOriginIframeCount = 0,
            capturedAtMs = 6_000L,
            extractedQuestions = listOf(q0, q1, q2),
            images = imgs
        )

        assertEquals(
            listOf(DomContextSource.SELECTION, DomContextSource.QUESTIONS, DomContextSource.FULL_DOM),
            snap.availableContextSources()
        )
        assertEquals(
            listOf(DomContextSource.QUESTIONS, DomContextSource.FULL_DOM),
            snap.copy(selectedText = "").availableContextSources()
        )
        assertTrue(
            snap.copy(selectedText = "", extractedQuestions = emptyList(), bodyText = " ")
                .availableContextSources().isEmpty()
        )

        // questionsForSelection(null) uses preferredQuestions(); explicit indices index all valid items sorted/deduped
        assertEquals(listOf(q1, q2), snap.questionsForSelection(null))
        assertEquals(listOf(q0, q2), snap.questionsForSelection(setOf(2, 0, -1, 99)))
        assertTrue(snap.questionsForSelection(emptySet()).isEmpty())

        // effectiveContextText with sourceOverride & selectedQuestionIndices
        val pickedQuestionsText = snap.effectiveContextText(
            sourceOverride = DomContextSource.QUESTIONS,
            selectedQuestionIndices = setOf(0, 2)
        )
        assertTrue(pickedQuestionsText.contains("第一题 [图片1]"))
        assertTrue(pickedQuestionsText.contains("第三题 [图片4]"))
        assertFalse(pickedQuestionsText.contains("第二题"))

        assertEquals(
            "整页正文 [图片1] [图片2] [图片3] [图片4]",
            snap.effectiveContextText(sourceOverride = DomContextSource.FULL_DOM)
        )

        // Graceful fallback when sourceOverride has blank text
        val noSelectionSnap = snap.copy(selectedText = "")
        val fallbackFromSel = noSelectionSnap.effectiveContextText(sourceOverride = DomContextSource.SELECTION)
        assertTrue(fallbackFromSel.contains("第二题"))
        assertFalse(fallbackFromSel.contains("第一题"))

        val noBodySnap = snap.copy(bodyText = "", extractedQuestions = emptyList())
        assertEquals(
            "选中题干 [图片3]",
            noBodySnap.effectiveContextText(sourceOverride = DomContextSource.FULL_DOM)
        )

        // activeImages with sourceOverride, selectedQuestionIndices, and excludedImageIndices
        assertEquals(
            listOf(2),
            snap.activeImages(
                sourceOverride = DomContextSource.QUESTIONS,
                selectedQuestionIndices = setOf(0, 1),
                excludedImageIndices = setOf(1)
            ).map { it.index }
        )
        assertEquals(
            listOf(1, 3),
            snap.activeImages(
                sourceOverride = DomContextSource.FULL_DOM,
                excludedImageIndices = setOf(2, 4)
            ).map { it.index }
        )
    }
}
