package com.scluzlep.searchcx.net

import com.scluzlep.searchcx.data.DomImageItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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
        assertFalse(hint.contains("用户截屏框选图像"))

        val hintZero = DomImageFetcher.buildImageMappingHint(
            listOf(
                ResolvedDomImage(index = 1, chatImage = ChatImage("image/png", "AA"), isFormula = true, alt = "f(x)"),
                ResolvedDomImage(index = 2, chatImage = ChatImage("image/jpeg", "BB"), isFormula = false, alt = "")
            ),
            attachmentOffset = 0
        )
        assertEquals(hint, hintZero)
    }

    @Test
    fun buildsMappingHintWithAttachmentOffsets() {
        val resolved = listOf(
            ResolvedDomImage(index = 1, chatImage = ChatImage("image/png", "AA"), isFormula = true, alt = "x^2"),
            ResolvedDomImage(index = 2, chatImage = ChatImage("image/jpeg", "BB"), isFormula = false, alt = "")
        )

        val hintOffsetOne = DomImageFetcher.buildImageMappingHint(resolved, attachmentOffset = 1)
        val linesOne = hintOffsetOne.lines()
        assertEquals("[图片索引对应关系]", linesOne[0])
        assertEquals("- 第 1 张附图：用户截屏框选图像", linesOne[1])
        assertEquals("- [图片1]：第 2 张附图 · 公式图 · alt: x^2", linesOne[2])
        assertEquals("- [图片2]：第 3 张附图 · 题图", linesOne[3])

        val hintOffsetTwo = DomImageFetcher.buildImageMappingHint(resolved, attachmentOffset = 2)
        val linesTwo = hintOffsetTwo.lines()
        assertEquals("[图片索引对应关系]", linesTwo[0])
        assertEquals("- 第 1-2 张附图：用户截屏框选图像", linesTwo[1])
        assertEquals("- [图片1]：第 3 张附图 · 公式图 · alt: x^2", linesTwo[2])
        assertEquals("- [图片2]：第 4 张附图 · 题图", linesTwo[3])

        assertEquals("", DomImageFetcher.buildImageMappingHint(emptyList(), attachmentOffset = 1))
        assertEquals("", DomImageFetcher.buildImageMappingHint(emptyList(), attachmentOffset = 2))
    }

    @Test
    fun computesThumbnailTargetSizeWithoutUpscaling() {
        assertEquals(120 to 80, DomImageFetcher.computeThumbnailTargetSize(120, 80, 180))
        assertEquals(180 to 180, DomImageFetcher.computeThumbnailTargetSize(180, 180, 180))
        assertEquals(180 to 180, DomImageFetcher.computeThumbnailTargetSize(360, 360, 180))
        assertEquals(180 to 90, DomImageFetcher.computeThumbnailTargetSize(1800, 900, 180))
        assertEquals(90 to 180, DomImageFetcher.computeThumbnailTargetSize(450, 900, 180))
    }

    @Test
    fun decodeThumbnailHandlesNonHttpErrorsAndCancellationCleanly() = runBlocking {
        val calls = AtomicInteger(0)
        val lastReferer = AtomicReference<String?>(null)
        val httpErrorClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                calls.incrementAndGet()
                lastReferer.set(chain.request().header("Referer"))
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("Not Found")
                    .body(ByteArray(0).toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()
        val errorFetcher = DomImageFetcher(httpErrorClient)

        assertNull(
            errorFetcher.decodeThumbnail(
                DomImageItem(index = 1, url = "file:///sdcard/secret.png"),
                pageUrl = "https://mooc1.chaoxing.com/work"
            )
        )
        assertNull(
            errorFetcher.decodeThumbnail(
                DomImageItem(index = 1, url = "https://cdn.example.com/a.png"),
                pageUrl = "https://mooc1.chaoxing.com/work",
                maxSidePx = 0
            )
        )
        assertEquals(0, calls.get())

        assertNull(
            errorFetcher.decodeThumbnail(
                DomImageItem(index = 1, url = "https://cdn.example.com/a.png"),
                pageUrl = "https://mooc1.chaoxing.com/work"
            )
        )
        assertEquals(1, calls.get())
        assertEquals("https://mooc1.chaoxing.com/", lastReferer.get())

        val ioFailClient = OkHttpClient.Builder()
            .addInterceptor { throw IOException("network down") }
            .build()
        assertNull(
            DomImageFetcher(ioFailClient).decodeThumbnail(
                DomImageItem(index = 1, url = "https://cdn.example.com/a.png"),
                pageUrl = ""
            )
        )

        val jobRef = AtomicReference<Job?>(null)
        val caughtCancel = AtomicBoolean(false)
        val cancelClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                jobRef.get()?.cancel()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(byteArrayOf(1, 2, 3).toResponseBody("image/png".toMediaType()))
                    .build()
            }
            .build()
        val job = launch(start = CoroutineStart.LAZY) {
            try {
                DomImageFetcher(cancelClient).decodeThumbnail(
                    DomImageItem(index = 1, url = "https://cdn.example.com/a.png"),
                    pageUrl = ""
                )
            } catch (e: CancellationException) {
                caughtCancel.set(true)
                throw e
            }
        }
        jobRef.set(job)
        job.start()
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(caughtCancel.get())
    }
}
