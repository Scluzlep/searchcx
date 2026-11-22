package com.overlayai.assistant.ui

import com.overlayai.assistant.overlay.ImageCropUtils
import com.overlayai.assistant.overlay.NormalizedCropRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    @Test
    fun splitsThinkTagsCleanlyIncludingUnclosedStreamingTag() {
        val (r1, b1) = MarkdownParser.splitThinkTags("<think>step 1\nstep 2</think>\nFinal answer")
        assertEquals("step 1\nstep 2", r1)
        assertEquals("Final answer", b1)

        val (r2, b2) = MarkdownParser.splitThinkTags("<think>still thinking...")
        assertEquals("still thinking...", r2)
        assertEquals("", b2)
    }

    @Test
    fun parsesHeadingsCodeTablesMathAndLists() {
        val md = """
            ## 解题思路
            考虑公式 ${'$'}\frac{x^2 + 1}{\sqrt{y}} \le \infty${'$'} 与 **重要结论**。

            $$
            \sum_{n=1}^{\infty} \frac{1}{n^2} = \frac{\pi^2}{6}
            $$

            | 变量 | 含义 |
            | --- | --- |
            | x | 输入 |

            ```kotlin
            fun add(a: Int, b: Int) = a + b
        """.trimIndent()

        val blocks = MarkdownParser.parse(md)
        assertTrue(blocks[0] is MdBlock.Heading)
        assertTrue(blocks[1] is MdBlock.Paragraph)
        val para = blocks[1] as MdBlock.Paragraph
        assertTrue(para.inlines.any { it is MdInline.InlineMath && it.pretty.contains("√(y)") && it.pretty.contains("≤") })
        assertTrue(para.inlines.any { it is MdInline.Bold && it.text == "重要结论" })

        val mathBlock = blocks[2] as MdBlock.MathBlock
        assertTrue(mathBlock.pretty.contains("∑"))
        assertTrue(mathBlock.pretty.contains("π"))

        val table = blocks[3] as MdBlock.Table
        assertEquals(listOf("变量", "含义"), table.headers)
        assertEquals(listOf(listOf("x", "输入")), table.rows)

        val code = blocks[4] as MdBlock.CodeBlock
        assertEquals("kotlin", code.language)
        assertEquals(false, code.closed)
        assertTrue(code.code.contains("fun add"))
    }

    @Test
    fun clampsCropBoundsSafely() {
        val clamped = ImageCropUtils.clampRect(NormalizedCropRect(-0.2f, 0.99f, 0.1f, 1.5f), minSpan = 0.06f)
        assertEquals(0f, clamped.left, 0.0001f)
        assertTrue(clamped.right >= clamped.left + 0.06f)
        assertTrue(clamped.bottom <= 1.0f)

        val px = ImageCropUtils.toPixelBounds(NormalizedCropRect(0.1f, 0.2f, 0.5f, 0.6f), 1000, 2000)
        assertEquals(100, px.left)
        assertEquals(400, px.top)
        assertEquals(400, px.width)
        assertEquals(800, px.height)
    }
}
