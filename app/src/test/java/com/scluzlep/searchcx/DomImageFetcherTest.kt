package com.scluzlep.searchcx.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomImageFetcherTest {

    @Test
    fun parsesValidAndInvalidDataUrls() {
        val valid = DomImageFetcher.parseDataUrl("data:image/png;charset=utf-8;base64,QUJD")
        assertNotNull(valid)
        assertEquals("image/png", valid!!.mimeType)
        assertEquals("ABC", String(valid.bytes, Charsets.US_ASCII))

        assertNull(DomImageFetcher.parseDataUrl("https://example.com/a.png"))
        assertNull(DomImageFetcher.parseDataUrl("data:image/png;base64,"))
        assertNull(DomImageFetcher.parseDataUrl("data:image/png;base64,!!!invalid!!!"))
    }

    @Test
    fun upscalesTinyFormulaImagesAndCapsLargeDiagrams() {
        val (wSmall, hSmall) = DomImageFetcher.computeTargetSize(20, 14)
        assertEquals(57, wSmall)
        assertEquals(40, hSmall)

        val (wTiny, hTiny) = DomImageFetcher.computeTargetSize(12, 10)
        assertEquals(36, wTiny)
        assertEquals(30, hTiny)

        val (wNormal, hNormal) = DomImageFetcher.computeTargetSize(300, 120)
        assertEquals(300, wNormal)
        assertEquals(120, hNormal)

        val (wLarge, hLarge) = DomImageFetcher.computeTargetSize(2560, 1280)
        assertEquals(1280, wLarge)
        assertEquals(640, hLarge)
    }

    @Test
    fun buildsRefererAndMappingHintCleanly() {
        assertEquals(
            "https://mooc1.chaoxing.com/",
            DomImageFetcher.buildReferer(
                imageUrl = "https://p.ananas.chaoxing.com/star3/origin/a.png",
                pageUrl = "https://mooc1.chaoxing.com/mycourse/studentstudy?chapterId=1"
            )
        )
        assertEquals(
            "http://localhost:8080/",
            DomImageFetcher.buildReferer(
                imageUrl = "http://localhost:8080/img/eq.png",
                pageUrl = ""
            )
        )

        val hint = DomImageFetcher.buildImageMappingHint(
            listOf(
                ResolvedDomImage(index = 1, chatImage = ChatImage("image/png", "AA"), isFormula = true, alt = "f(x)"),
                ResolvedDomImage(index = 2, chatImage = ChatImage("image/jpeg", "BB"), isFormula = false, alt = "")
            )
        )
        assertTrue(hint.contains("[图片1]：第 1 张附图 · 公式图 · alt: f(x)"))
        assertTrue(hint.contains("[图片2]：第 2 张附图 · 题图"))
    }
}
