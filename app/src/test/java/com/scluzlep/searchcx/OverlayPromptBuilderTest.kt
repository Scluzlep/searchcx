package com.scluzlep.searchcx

import com.scluzlep.searchcx.data.OverlayPrefs
import com.scluzlep.searchcx.overlay.DomImagePreviewItem
import com.scluzlep.searchcx.overlay.DomQuestionChipItem
import com.scluzlep.searchcx.overlay.OverlayPromptBuilder
import com.scluzlep.searchcx.overlay.OverlayUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayPromptBuilderTest {

    @Test
    fun manualInputWithDomHidesDomFromVisibleTextAndAttachesContext() {
        val rawDom = "<html>题目：求函数 f(x)=x^2 在 x=2 处的导数。\n选项 A. 2  B. 4  C. 8</html>"
        val built = OverlayPromptBuilder.buildManual(
            rawInput = "  这道题选哪个？ ",
            rawContextText = "  $rawDom  ",
            hasImage = false
        )

        assertNotNull(built)
        built!!
        assertEquals("这道题选哪个？", built.visibleUserText)
        assertFalse(built.visibleUserText.contains(rawDom))
        assertEquals("DOM HTML", built.contextBadge)
        assertEquals("这道题选哪个？\n\n[页面上下文]\n$rawDom", built.apiPrompt)
    }

    @Test
    fun emptyInputWithDomUsesConciseVisibleLabelAndQuickAskPrompt() {
        val rawDom = "微积分第二章习题：计算定积分 int_0^1 x dx"
        val quickPrompt = "请直接解析以下内容，给出清晰步骤与最终结论："
        val built = OverlayPromptBuilder.buildManual(
            rawInput = "   ",
            rawContextText = rawDom,
            hasImage = false,
            quickAskPrompt = quickPrompt
        )

        assertNotNull(built)
        built!!
        assertEquals("解析网页内容", built.visibleUserText)
        assertFalse(built.visibleUserText.contains(rawDom))
        assertEquals("DOM HTML", built.contextBadge)
        assertEquals("$quickPrompt\n\n[页面上下文]\n$rawDom", built.apiPrompt)
    }

    @Test
    fun quickActionsWithDomShowDisplayLabelAndKeepDomInApiPrompt() {
        val rawDom = "Linear algebra question: find eigenvalues of [[2, 0], [0, 3]]"
        val actions = listOf(
            "直接解题" to "请直接解答以下题目或问题，给出关键推导步骤与最终结论：",
            "总结要点" to "请提取并条理化总结以下内容的核心要点：",
            "准确翻译" to "请将以下内容准确翻译为中文：",
            "解析网页内容" to "请直接解析以下内容，给出清晰步骤与最终结论："
        )

        for ((label, instruction) in actions) {
            val built = OverlayPromptBuilder.buildQuickAction(
                displayLabel = label,
                instruction = instruction,
                rawContextText = rawDom,
                hasImage = false
            )
            assertNotNull(built)
            built!!
            assertEquals(label, built.visibleUserText)
            assertFalse(built.visibleUserText.contains(rawDom))
            assertEquals("DOM HTML", built.contextBadge)
            assertEquals("$instruction\n\n[页面上下文]\n$rawDom", built.apiPrompt)
        }

        val rawOnly = OverlayPromptBuilder.buildQuickAction(
            displayLabel = "仅发原文",
            instruction = "",
            rawContextText = rawDom,
            hasImage = false
        )
        assertNotNull(rawOnly)
        rawOnly!!
        assertEquals("仅发原文", rawOnly.visibleUserText)
        assertFalse(rawOnly.visibleUserText.contains(rawDom))
        assertEquals("DOM HTML", rawOnly.contextBadge)
        assertEquals(rawDom, rawOnly.apiPrompt)
    }

    @Test
    fun clipboardContextUsesClipboardQuoteLabelWhileDomUsesDomHtml() {
        assertEquals("剪贴板文本", OverlayPromptBuilder.quoteBadgeForStatus("已粘贴"))
        assertEquals("题目内容", OverlayPromptBuilder.quoteBadgeForStatus("已抓取 · 题目"))
        assertEquals("页面选区", OverlayPromptBuilder.quoteBadgeForStatus("已抓取 · 选区"))
        assertEquals("DOM HTML", OverlayPromptBuilder.quoteBadgeForStatus("已抓取 · 正文"))
        assertEquals("DOM HTML", OverlayPromptBuilder.quoteBadgeForStatus("等待采集"))
        assertEquals(
            "题目内容 · 2 题 · 3 图",
            OverlayPromptBuilder.formatQuoteBadge("题目内容", questionCount = 2, imageCount = 3)
        )
        assertEquals(
            "已知函数 满足极限条件",
            OverlayPromptBuilder.sanitizeForTikuSearch("已知函数 [图片1] 满足极限条件 [图片2]")
        )

        val clipText = "剪贴板里的题干内容"
        val manualClip = OverlayPromptBuilder.buildManual(
            rawInput = "帮我检查",
            rawContextText = clipText,
            hasImage = false,
            contextLabel = OverlayPromptBuilder.quoteBadgeForStatus("已粘贴")
        )!!
        assertEquals("帮我检查", manualClip.visibleUserText)
        assertEquals("剪贴板文本", manualClip.contextBadge)
        assertEquals("帮我检查\n\n[页面上下文]\n$clipText", manualClip.apiPrompt)

        val quickClip = OverlayPromptBuilder.buildQuickAction(
            displayLabel = "总结要点",
            instruction = "请总结：",
            rawContextText = clipText,
            hasImage = false,
            contextLabel = OverlayPromptBuilder.quoteBadgeForStatus("已粘贴")
        )!!
        assertEquals("总结要点", quickClip.visibleUserText)
        assertEquals("剪贴板文本", quickClip.contextBadge)
        assertEquals("请总结：\n\n[页面上下文]\n$clipText", quickClip.apiPrompt)
    }

    @Test
    fun imageOnlySendWorksForManualAndQuickActionWithoutWebBadge() {
        val manualImg = OverlayPromptBuilder.buildManual(
            rawInput = "",
            rawContextText = "",
            hasImage = true,
            quickAskPrompt = "请直接解析以下内容："
        )
        assertNotNull(manualImg)
        manualImg!!
        assertEquals("解析截图内容", manualImg.visibleUserText)
        assertEquals("", manualImg.contextBadge)
        assertEquals("请直接解析以下内容：", manualImg.apiPrompt)

        val quickSolveImg = OverlayPromptBuilder.buildQuickAction(
            displayLabel = "直接解题",
            instruction = "请直接解答以下题目或问题，给出关键推导步骤与最终结论：",
            rawContextText = "",
            hasImage = true
        )
        assertNotNull(quickSolveImg)
        quickSolveImg!!
        assertEquals("直接解题", quickSolveImg.visibleUserText)
        assertEquals("", quickSolveImg.contextBadge)
        assertEquals("请直接解答以下题目或问题，给出关键推导步骤与最终结论：", quickSolveImg.apiPrompt)

        val quickRawImg = OverlayPromptBuilder.buildQuickAction(
            displayLabel = "仅发原文",
            instruction = "",
            rawContextText = "",
            hasImage = true
        )
        assertNotNull(quickRawImg)
        quickRawImg!!
        assertEquals("仅发原文", quickRawImg.visibleUserText)
        assertEquals("", quickRawImg.contextBadge)
        assertEquals("", quickRawImg.apiPrompt)

        assertNull(OverlayPromptBuilder.buildManual("", "", hasImage = false))
        assertNull(OverlayPromptBuilder.buildQuickAction("直接解题", "解答", "", hasImage = false))
    }

    @Test
    fun multiTurnDomAttachmentAlwaysIncludesNewDomWithoutLeakingToVisibleText() {
        val domTurn1 = "第一页 DOM：牛顿第二定律 F = ma"
        val domTurn3 = "第二页 DOM：动能定理 W = ΔEk"

        val turn1 = OverlayPromptBuilder.buildManual(
            rawInput = "解释一下公式含义",
            rawContextText = domTurn1,
            hasImage = false
        )!!
        assertEquals("解释一下公式含义", turn1.visibleUserText)
        assertEquals("DOM HTML", turn1.contextBadge)
        assertTrue(turn1.apiPrompt.contains(domTurn1))
        assertFalse(turn1.visibleUserText.contains(domTurn1))

        val turn2 = OverlayPromptBuilder.buildManual(
            rawInput = "单位是什么？",
            rawContextText = "",
            hasImage = false
        )!!
        assertEquals("单位是什么？", turn2.visibleUserText)
        assertEquals("", turn2.contextBadge)
        assertEquals("单位是什么？", turn2.apiPrompt)

        // Follow-up turn after switching webpage and reading new DOM must still attach domTurn3.
        val turn3 = OverlayPromptBuilder.buildManual(
            rawInput = "再结合这页讲讲",
            rawContextText = domTurn3,
            hasImage = false
        )!!
        assertEquals("再结合这页讲讲", turn3.visibleUserText)
        assertEquals("DOM HTML", turn3.contextBadge)
        assertEquals("再结合这页讲讲\n\n[页面上下文]\n$domTurn3", turn3.apiPrompt)
        assertFalse(turn3.visibleUserText.contains(domTurn3))

        val turn4EmptyInput = OverlayPromptBuilder.buildManual(
            rawInput = "",
            rawContextText = domTurn3,
            hasImage = false,
            quickAskPrompt = "请解析："
        )!!
        assertEquals("解析网页内容", turn4EmptyInput.visibleUserText)
        assertEquals("DOM HTML", turn4EmptyInput.contextBadge)
        assertEquals("请解析：\n\n[页面上下文]\n$domTurn3", turn4EmptyInput.apiPrompt)
        assertFalse(turn4EmptyInput.visibleUserText.contains(domTurn3))
    }

    @Test
    fun quoteBadgeForStatusHandlesAllDomCaptureModesAndClipboard() {
        assertEquals(
            OverlayPromptBuilder.CONTEXT_BADGE_DOM,
            OverlayPromptBuilder.quoteBadgeForStatus("已抓取 · 正文")
        )
        assertEquals(
            OverlayPromptBuilder.CONTEXT_BADGE_DOM,
            OverlayPromptBuilder.quoteBadgeForStatus("  已抓取 · 正文  ")
        )
        assertEquals(
            OverlayPromptBuilder.CONTEXT_BADGE_QUESTIONS,
            OverlayPromptBuilder.quoteBadgeForStatus("已抓取 · 题目")
        )
        assertEquals(
            OverlayPromptBuilder.CONTEXT_BADGE_SELECTION,
            OverlayPromptBuilder.quoteBadgeForStatus("已抓取 · 选区")
        )
        assertEquals(
            OverlayPromptBuilder.CONTEXT_BADGE_CLIPBOARD,
            OverlayPromptBuilder.quoteBadgeForStatus("已粘贴")
        )
        assertEquals(
            OverlayPromptBuilder.CONTEXT_BADGE_DOM,
            OverlayPromptBuilder.quoteBadgeForStatus("")
        )
    }

    @Test
    fun formatQuoteBadgeFormatsOptionalQuestionAndImageCounts() {
        assertEquals("DOM HTML", OverlayPromptBuilder.formatQuoteBadge("DOM HTML"))
        assertEquals(
            "题目内容 · 2 题",
            OverlayPromptBuilder.formatQuoteBadge("题目内容", questionCount = 2)
        )
        assertEquals(
            "DOM HTML · 3 图",
            OverlayPromptBuilder.formatQuoteBadge("DOM HTML", imageCount = 3)
        )
        assertEquals(
            "页面选区 · 1 题 · 4 图",
            OverlayPromptBuilder.formatQuoteBadge("页面选区", questionCount = 1, imageCount = 4)
        )
        assertEquals(
            "DOM HTML · 2 题 · 1 图",
            OverlayPromptBuilder.formatQuoteBadge("   ", questionCount = 2, imageCount = 1)
        )
    }

    @Test
    fun sanitizeForTikuSearchRemovesImagePlaceholdersAndNormalizesWhitespace() {
        assertEquals(
            "已知函数 f(x) 且 连续",
            OverlayPromptBuilder.sanitizeForTikuSearch("  [图片1] 已知函数 f(x) [图片12] 且 \t [图片] 连续  ")
        )
        assertEquals("", OverlayPromptBuilder.sanitizeForTikuSearch("  [图片1] \t [图片2]  "))
        assertEquals(
            "已知导数定义\nA. 1\nB. 2",
            OverlayPromptBuilder.sanitizeForTikuSearch("已知导数定义\nA. 1\nB. 2")
        )
    }

    @Test
    fun overlayUiStateAndPreviewModelsHaveExpectedDefaults() {
        val state = OverlayUiState()
        assertFalse(state.isContextPreviewExpanded)
        assertNull(state.activeContextSource)
        assertTrue(state.availableContextSources.isEmpty())
        assertTrue(state.questionChips.isEmpty())
        assertTrue(state.domImagePreviews.isEmpty())
        assertFalse(state.hasObfuscatedFont)

        val imgItem = DomImagePreviewItem(
            index = 1,
            bitmap = null,
            isFormula = true,
            label = "公式1"
        )
        assertTrue(imgItem.included)

        val qChip = DomQuestionChipItem(
            index = 0,
            label = "第1题",
            preview = "题干",
            selected = true
        )
        assertEquals(0, qChip.index)
        assertTrue(qChip.selected)
    }

    @Test
    fun defaultQuickAskPromptContainsQuickLookupAndExplanationForEmptyInputDom() {
        assertEquals(OverlayPrefs.DEFAULT_QUICK_ASK_PROMPT, OverlayPromptBuilder.DEFAULT_QUICK_ASK_PROMPT)
        assertTrue(OverlayPromptBuilder.DEFAULT_QUICK_ASK_PROMPT.contains("速查"))
        assertTrue(OverlayPromptBuilder.DEFAULT_QUICK_ASK_PROMPT.contains("解析"))

        val rawDom = "题目：已知 f(x)=x^3，求 f'(2)。"
        val built = OverlayPromptBuilder.buildManual(
            rawInput = "   ",
            rawContextText = rawDom,
            hasImage = false
        )
        assertNotNull(built)
        built!!
        assertEquals("解析网页内容", built.visibleUserText)
        assertEquals("DOM HTML", built.contextBadge)
        assertEquals(
            "${OverlayPromptBuilder.DEFAULT_QUICK_ASK_PROMPT}\n\n[页面上下文]\n$rawDom",
            built.apiPrompt
        )
    }
}
