package com.scluzlep.searchcx.net

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun prunesOlderHistoryImagesAndAvoidsDuplicateTag() {
        assertEquals(2, OpenAiSseParser.DEFAULT_RECENT_IMAGE_TURNS)

        val img1 = ChatTurn("user", "第一题", imageBase64Jpeg = "SU1HMQ==")
        val ans1 = ChatTurn("assistant", "第一题答案")
        val img2 = ChatTurn("user", "第二题", images = listOf(ChatImage("image/png", "SU1HMg==")))
        val ans2 = ChatTurn("assistant", "第二题答案")
        val img3 = ChatTurn("user", "", imageBase64Jpeg = "SU1HMw==")
        val ans3 = ChatTurn("assistant", "第三题答案")
        val img4 = ChatTurn(
            "user",
            "第四题",
            imageBase64Jpeg = "SU1HNEE=",
            images = listOf(ChatImage("image/png", "SU1HNEI="))
        )

        val oneImageHistory = listOf(img1, ans1)
        assertEquals(oneImageHistory, OpenAiSseParser.pruneHistoryImages(oneImageHistory))

        val twoImageHistory = listOf(img1, ans1, img2, ans2)
        assertEquals(twoImageHistory, OpenAiSseParser.pruneHistoryImages(twoImageHistory))

        val threeImageHistory = listOf(img1, ans1, img2, ans2, img3)
        val prunedThree = OpenAiSseParser.pruneHistoryImages(threeImageHistory)
        assertEquals(5, prunedThree.size)
        assertNull(prunedThree[0].imageBase64Jpeg)
        assertTrue(prunedThree[0].images.isEmpty())
        assertEquals("第一题\n[早期轮次附图已省略]", prunedThree[0].content)
        assertEquals(ans1, prunedThree[1])
        assertEquals(img2, prunedThree[2])
        assertEquals(ans2, prunedThree[3])
        assertEquals(img3, prunedThree[4])

        val fourImageHistory = listOf(img1, ans1, img2, ans2, img3, ans3, img4)
        val prunedFour = OpenAiSseParser.pruneHistoryImages(fourImageHistory)
        assertEquals("第一题\n[早期轮次附图已省略]", prunedFour[0].content)
        assertNull(prunedFour[0].imageBase64Jpeg)
        assertEquals(ans1, prunedFour[1])
        assertEquals("第二题\n[早期轮次附图已省略]", prunedFour[2].content)
        assertTrue(prunedFour[2].images.isEmpty())
        assertEquals(ans2, prunedFour[3])
        assertEquals(img3, prunedFour[4])
        assertEquals(ans3, prunedFour[5])
        assertEquals(img4, prunedFour[6])

        // Blank content in older turn gets omission tag without leading newline.
        val blankOlderHistory = listOf(img3, ans1, img2, img4)
        val prunedBlank = OpenAiSseParser.pruneHistoryImages(blankOlderHistory)
        assertEquals("[早期轮次附图已省略]", prunedBlank[0].content)
        assertNull(prunedBlank[0].imageBase64Jpeg)

        // maxImageTurnsToKeep = 0 strips all image turns while leaving text turns untouched.
        val strippedAll = OpenAiSseParser.pruneHistoryImages(threeImageHistory, maxImageTurnsToKeep = 0)
        assertTrue(strippedAll[0].allImages().isEmpty())
        assertEquals("第一题\n[早期轮次附图已省略]", strippedAll[0].content)
        assertEquals(ans1, strippedAll[1])
        assertTrue(strippedAll[2].allImages().isEmpty())
        assertEquals("第二题\n[早期轮次附图已省略]", strippedAll[2].content)
        assertEquals(ans2, strippedAll[3])
        assertTrue(strippedAll[4].allImages().isEmpty())
        assertEquals("[早期轮次附图已省略]", strippedAll[4].content)

        // Avoids duplicate omission marker when already present.
        val preTagged = ChatTurn("user", "第一题\n[早期轮次附图已省略]", imageBase64Jpeg = "QUJD")
        val prunedDuplicate = OpenAiSseParser.pruneHistoryImages(listOf(preTagged, img2, img4))
        assertEquals("第一题\n[早期轮次附图已省略]", prunedDuplicate[0].content)
        assertNull(prunedDuplicate[0].imageBase64Jpeg)
    }

    @Test
    fun payloadBuildersOmitOlderImagesAndKeepRecentTwoImageTurns() {
        val history = listOf(
            ChatTurn("user", "第一轮问题", imageBase64Jpeg = "T0xEMQ=="),
            ChatTurn("assistant", "第一轮回答"),
            ChatTurn("user", "第二轮问题", images = listOf(ChatImage("image/png", "TkVXMg=="))),
            ChatTurn("assistant", "第二轮回答"),
            ChatTurn("user", "第三轮问题", imageBase64Jpeg = "TkVXMw==")
        )

        val chatPayload = OpenAiSseParser.buildChatCompletionsPayload(
            model = "gpt-4o",
            systemPrompt = "System",
            history = history,
            temperature = 0.2
        )
        val chatMessages = chatPayload.getJSONArray("messages")
        assertEquals(6, chatMessages.length())
        // Index 1 is older image turn -> plain text string with omission tag, no JSONArray parts.
        val chatTurn1 = chatMessages.getJSONObject(1)
        assertTrue(chatTurn1.get("content") is String)
        assertEquals("第一轮问题\n[早期轮次附图已省略]", chatTurn1.getString("content"))
        assertEquals("第一轮回答", chatMessages.getJSONObject(2).getString("content"))
        // Latest 2 image turns retain image_url arrays.
        val chatTurn2Parts = chatMessages.getJSONObject(3).get("content") as JSONArray
        assertEquals("image_url", chatTurn2Parts.getJSONObject(1).getString("type"))
        assertEquals(
            "data:image/png;base64,TkVXMg==",
            chatTurn2Parts.getJSONObject(1).getJSONObject("image_url").getString("url")
        )
        val chatTurn3Parts = chatMessages.getJSONObject(5).get("content") as JSONArray
        assertEquals("image_url", chatTurn3Parts.getJSONObject(1).getString("type"))
        assertEquals(
            "data:image/jpeg;base64,TkVXMw==",
            chatTurn3Parts.getJSONObject(1).getJSONObject("image_url").getString("url")
        )

        val responsesPayload = OpenAiSseParser.buildResponsesPayload(
            model = "gpt-5.1-codex",
            systemPrompt = "Dev",
            history = history,
            temperature = null,
            isCodex = true
        )
        val respInput = responsesPayload.getJSONArray("input")
        assertEquals(6, respInput.length())
        val respTurn1 = respInput.getJSONObject(1)
        assertTrue(respTurn1.get("content") is String)
        assertEquals("第一轮问题\n[早期轮次附图已省略]", respTurn1.getString("content"))
        assertFalse(respTurn1.getString("content").contains("T0xEMQ=="))
        val respTurn2Parts = respInput.getJSONObject(3).get("content") as JSONArray
        assertEquals("input_image", respTurn2Parts.getJSONObject(1).getString("type"))
        assertEquals("data:image/png;base64,TkVXMg==", respTurn2Parts.getJSONObject(1).getString("image_url"))
        val respTurn3Parts = respInput.getJSONObject(5).get("content") as JSONArray
        assertEquals("input_image", respTurn3Parts.getJSONObject(1).getString("type"))
        assertEquals("data:image/jpeg;base64,TkVXMw==", respTurn3Parts.getJSONObject(1).getString("image_url"))
    }
}
