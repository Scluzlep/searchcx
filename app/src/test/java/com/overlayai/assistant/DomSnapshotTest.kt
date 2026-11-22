package com.overlayai.assistant.data

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
}
