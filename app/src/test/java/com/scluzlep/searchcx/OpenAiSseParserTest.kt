package com.scluzlep.searchcx.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OpenAiSseParserTest {

    @Suppress("DEPRECATION")
    @Test
    fun parsesStreamingDeltasReasoningAndDone() {
        assertNull(OpenAiSseParser.parseLine(""))
        assertNull(OpenAiSseParser.parseLine(": keepalive"))

        val e1 = OpenAiSseParser.parseLine(
            """data: {"id":"1","choices":[{"index":0,"delta":{"role":"assistant","content":"你好"}}]}"""
        )
        assertEquals(SseEvent.Delta("你好"), e1)

        val splitter = ThinkTagSplitter()
        val rEvents = OpenAiSseParser.parseChatCompletionsLine(
            """data: {"id":"2","choices":[{"index":0,"delta":{"reasoning_content":"先分析","content":"<thi"}}]}""",
            splitter
        ) + OpenAiSseParser.parseChatCompletionsLine(
            """data: {"id":"3","choices":[{"index":0,"delta":{"content":"nk>再推"}}]}""",
            splitter
        ) + OpenAiSseParser.parseChatCompletionsLine(
            """data: {"id":"4","choices":[{"index":0,"delta":{"content":"导</th"}}]}""",
            splitter
        ) + OpenAiSseParser.parseChatCompletionsLine(
            """data: {"id":"5","choices":[{"index":0,"delta":{"content":"ink>最终答案"}}]}""",
            splitter
        )

        val joinedReasoning = rEvents.filterIsInstance<StreamEvent.ReasoningDelta>()
            .joinToString("") { it.text }
        val joinedText = rEvents.filterIsInstance<StreamEvent.TextDelta>()
            .joinToString("") { it.text }
        assertEquals("先分析再推导", joinedReasoning)
        assertEquals("最终答案", joinedText)

        val eDone = OpenAiSseParser.parseLine("data: [DONE]")
        assertEquals(SseEvent.Done, eDone)
    }

    @Test
    fun parsesResponsesApiEventsAndMultimodalPayloads() {
        val rDelta = OpenAiSseParser.parseResponsesSseData(
            "response.output_text.delta",
            """{"type":"response.output_text.delta","delta":"Step 1"}"""
        )
        assertEquals(listOf(StreamEvent.TextDelta("Step 1")), rDelta)

        val rReason = OpenAiSseParser.parseResponsesSseData(
            "response.reasoning_summary_text.delta",
            """{"type":"response.reasoning_summary_text.delta","delta":"Check"}"""
        )
        assertEquals(listOf(StreamEvent.ReasoningDelta("Check")), rReason)

        val rDone = OpenAiSseParser.parseResponsesSseData(
            "response.completed",
            """{"type":"response.completed"}"""
        )
        assertEquals(listOf(StreamEvent.Done), rDone)

        val chatPayload = OpenAiSseParser.buildChatCompletionsPayload(
            model = "gpt-4o",
            systemPrompt = "Sys",
            history = listOf(ChatTurn("user", "看图解题", imageBase64Jpeg = "QUJD")),
            temperature = 0.2
        )
        val contentArr = chatPayload.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        assertEquals("image_url", contentArr.getJSONObject(1).getString("type"))
        assertTrue(
            contentArr.getJSONObject(1).getJSONObject("image_url").getString("url")
                .startsWith("data:image/jpeg;base64,QUJD")
        )

        val codexPayload = OpenAiSseParser.buildResponsesPayload(
            model = "gpt-5.1-codex",
            systemPrompt = "Dev prompt",
            history = listOf(ChatTurn("user", "解析截图", imageBase64Jpeg = "WFla")),
            temperature = null,
            isCodex = true
        )
        assertEquals("developer", codexPayload.getJSONArray("input").getJSONObject(0).getString("role"))
        assertEquals(false, codexPayload.getBoolean("store"))
        assertTrue(!codexPayload.has("temperature"))

        val multiTurn = ChatTurn(
            role = "user",
            content = "求 [图片1] 与 [图片2]",
            imageBase64Jpeg = "Q1JP",
            images = listOf(
                ChatImage(mimeType = "image/png", base64 = "RUEx"),
                ChatImage(mimeType = "image/jpeg", base64 = "RUFGMg==")
            )
        )
        val multiChat = OpenAiSseParser.buildChatCompletionsPayload(
            model = "gpt-4o",
            systemPrompt = "",
            history = listOf(multiTurn),
            temperature = 0.1
        )
        val multiChatContent = multiChat.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals(4, multiChatContent.length())
        assertEquals("data:image/jpeg;base64,Q1JP", multiChatContent.getJSONObject(1).getJSONObject("image_url").getString("url"))
        assertEquals("data:image/png;base64,RUEx", multiChatContent.getJSONObject(2).getJSONObject("image_url").getString("url"))
        assertEquals("data:image/jpeg;base64,RUFGMg==", multiChatContent.getJSONObject(3).getJSONObject("image_url").getString("url"))

        val multiResp = OpenAiSseParser.buildResponsesPayload(
            model = "gpt-5.1-codex",
            systemPrompt = "",
            history = listOf(multiTurn),
            temperature = null,
            isCodex = true
        )
        val multiRespContent = multiResp.getJSONArray("input").getJSONObject(0).getJSONArray("content")
        assertEquals(4, multiRespContent.length())
        assertEquals("input_image", multiRespContent.getJSONObject(2).getString("type"))
        assertEquals("data:image/png;base64,RUEx", multiRespContent.getJSONObject(2).getString("image_url"))
    }

    @Suppress("DEPRECATION")
    @Test
    fun parsesModelsAndThrowsOnUpstreamError() {
        val std = OpenAiSseParser.parseStandardModels("""{"data":[{"id":"gpt-4o-mini"},{"id":"gpt-4o"}]}""")
        assertEquals(listOf("gpt-4o", "gpt-4o-mini"), std)

        val codex = OpenAiSseParser.parseCodexModels(
            """{"models":[{"slug":"gpt-5.1-codex","visibility":"list"},{"slug":"hidden-internal","visibility":"hide"}]}"""
        )
        assertEquals(listOf("gpt-5.1-codex"), codex)

        try {
            OpenAiSseParser.parseLine(
                """data: {"error":{"message":"invalid_api_key","type":"invalid_request_error"}}"""
            )
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("invalid_api_key"))
        }

        val content = OpenAiSseParser.parseNonStreamingContent(
            """{"choices":[{"message":{"role":"assistant","content":"完成解析"}}]}"""
        )
        assertEquals("完成解析", content)
        assertEquals("HTTP 401: Unauthorized", OpenAiSseParser.formatHttpError(401, "  Unauthorized  "))
    }
}
