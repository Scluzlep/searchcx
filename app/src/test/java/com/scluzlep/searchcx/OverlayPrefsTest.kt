package com.scluzlep.searchcx

import com.scluzlep.searchcx.data.AppConfig
import com.scluzlep.searchcx.data.CollapsedBallStyle
import com.scluzlep.searchcx.data.OverlayPrefs
import com.scluzlep.searchcx.data.OverlayThemeMode
import com.scluzlep.searchcx.data.OverlayWindowGeometry
import com.scluzlep.searchcx.data.ResizeEdge
import com.scluzlep.searchcx.data.WindowBoundsPx
import com.scluzlep.searchcx.tiku.TikuConfig
import com.scluzlep.searchcx.tiku.TikuMode
import com.scluzlep.searchcx.tiku.TikuProvider
import com.scluzlep.searchcx.tiku.TikuProviderTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayPrefsTest {

    @Test
    fun roundTripsCollapsedBallStyles() {
        val dotPrefs = OverlayPrefs(
            collapsedBallStyle = CollapsedBallStyle.MINI_DOT,
            ballDockRight = false,
            panelOpacity = 0.75f
        )
        val restoredDot = OverlayPrefs.fromJson(dotPrefs.toJson())
        assertEquals(CollapsedBallStyle.MINI_DOT, restoredDot.collapsedBallStyle)
        assertEquals(false, restoredDot.ballDockRight)
        assertEquals(0.75f, restoredDot.panelOpacity, 0.001f)

        val btnPrefs = OverlayPrefs(collapsedBallStyle = CollapsedBallStyle.BUTTON)
        val restoredBtn = OverlayPrefs.fromJson(btnPrefs.toJson())
        assertEquals(CollapsedBallStyle.BUTTON, restoredBtn.collapsedBallStyle)
    }

    @Test
    fun defaultsToButtonWhenMissingOrUnknownStyle() {
        val missingStyle = OverlayPrefs.fromJson("""{"panelOpacity":0.9,"ballDockRight":true}""")
        assertEquals(CollapsedBallStyle.BUTTON, missingStyle.collapsedBallStyle)

        val unknownStyle = OverlayPrefs.fromJson("""{"collapsedBallStyle":"LEGACY_UNKNOWN"}""")
        assertEquals(CollapsedBallStyle.BUTTON, unknownStyle.collapsedBallStyle)
    }

    @Test
    fun appConfigDefaultsToBuiltinFreeProvidersAndRoundTripsTikuConfig() {
        val defaults = AppConfig()
        assertTrue(defaults.tikuConfig.enabled)
        assertEquals(TikuMode.DIRECT_BUILTIN, defaults.tikuConfig.mode)
        assertTrue(defaults.tikuConfig.autoQueryBeforeAi)
        assertFalse(defaults.tikuConfig.skipAiOnHit)
        assertEquals(TikuProvider.defaultFreeIds(), defaults.tikuConfig.enabledProviders)
        assertEquals(TikuConfig.DEFAULT_REMOTE_URL, defaults.tikuConfig.remoteBaseUrl)

        val custom = defaults.copy(
            tikuConfig = TikuConfig(
                enabled = true,
                mode = TikuMode.REMOTE_ADAPTER,
                remoteBaseUrl = "http://10.0.2.2:8060/adapter-service/search",
                autoQueryBeforeAi = false,
                skipAiOnHit = true,
                enabledProviders = setOf("icodef", "local", "enncy"),
                tokens = TikuProviderTokens(
                    icodefToken = "ic-1",
                    enncyToken = "en-2"
                )
            )
        )
        val encoded = custom.tikuConfig.toJson { "enc:$it" }
        val decoded = TikuConfig.fromJson(encoded) { it.removePrefix("enc:") }
        assertEquals(custom.tikuConfig, decoded)
    }

    @Test
    fun defaultPromptsIncludeQuickLookupAndExplanationAndMigrateLegacyDefaults() {
        val defaultConfig = AppConfig()
        assertTrue(defaultConfig.systemPrompt.contains("速查"))
        assertTrue(defaultConfig.systemPrompt.contains("解析"))

        val defaultPrefs = OverlayPrefs()
        assertTrue(defaultPrefs.quickAskPrompt.contains("速查"))
        assertTrue(defaultPrefs.quickAskPrompt.contains("解析"))

        assertEquals(
            AppConfig.DEFAULT_SYSTEM_PROMPT,
            AppConfig.migrateDefaultSystemPrompt(null)
        )
        assertEquals(
            AppConfig.DEFAULT_SYSTEM_PROMPT,
            AppConfig.migrateDefaultSystemPrompt("   ")
        )
        assertEquals(
            AppConfig.DEFAULT_SYSTEM_PROMPT,
            AppConfig.migrateDefaultSystemPrompt(AppConfig.LEGACY_DEFAULT_SYSTEM_PROMPT)
        )
        assertEquals(
            "请用英文回答",
            AppConfig.migrateDefaultSystemPrompt("请用英文回答")
        )

        val migratedFromMissing = OverlayPrefs.fromJson("""{"panelOpacity":0.9}""")
        assertEquals(OverlayPrefs.DEFAULT_QUICK_ASK_PROMPT, migratedFromMissing.quickAskPrompt)

        val migratedFromBlank = OverlayPrefs.fromJson("""{"quickAskPrompt":"  "}""")
        assertEquals(OverlayPrefs.DEFAULT_QUICK_ASK_PROMPT, migratedFromBlank.quickAskPrompt)

        val migratedFromLegacy = OverlayPrefs.fromJson(
            """{"quickAskPrompt":"${OverlayPrefs.LEGACY_DEFAULT_QUICK_ASK_PROMPT}"}"""
        )
        assertEquals(OverlayPrefs.DEFAULT_QUICK_ASK_PROMPT, migratedFromLegacy.quickAskPrompt)

        val preservedCustom = OverlayPrefs.fromJson("""{"quickAskPrompt":"只输出最终选项"}""")
        assertEquals("只输出最终选项", preservedCustom.quickAskPrompt)
    }

    @Test
    fun roundTripsThemeAndWindowGeometryWithClampingAndFallbacks() {
        val prefs = OverlayPrefs(
            themeMode = OverlayThemeMode.DARK,
            panelXFraction = -0.25f,
            panelYFraction = 0.18f,
            panelWidthDp = 380,
            panelHeightDp = 540,
            ballDocked = false,
            ballXFraction = 0.42f
        )
        val restored = OverlayPrefs.fromJson(prefs.toJson())
        assertEquals(OverlayThemeMode.DARK, restored.themeMode)
        assertEquals(-0.25f, restored.panelXFraction, 0.001f)
        assertEquals(0.18f, restored.panelYFraction, 0.001f)
        assertEquals(380, restored.panelWidthDp)
        assertEquals(540, restored.panelHeightDp)
        assertFalse(restored.ballDocked)
        assertEquals(0.42f, restored.ballXFraction, 0.001f)

        val fallback = OverlayPrefs.fromJson("""{"themeMode":"UNKNOWN_THEME","panelWidthDp":-10,"panelHeightDp":0,"panelXFraction":-8.0,"panelYFraction":-6.0,"ballXFraction":-9.0}""")
        assertEquals(OverlayThemeMode.FOLLOW_SYSTEM, fallback.themeMode)
        assertEquals(0, fallback.panelWidthDp)
        assertEquals(0, fallback.panelHeightDp)
        assertEquals(OverlayPrefs.UNSET_FRACTION, fallback.panelXFraction, 0.001f)
        assertEquals(OverlayPrefs.UNSET_FRACTION, fallback.panelYFraction, 0.001f)
        assertTrue(fallback.ballDocked)
        assertEquals(OverlayPrefs.UNSET_FRACTION, fallback.ballXFraction, 0.001f)

        val clamped = OverlayPrefs.fromJson("""{"themeMode":"LIGHT","panelWidthDp":100,"panelHeightDp":3000,"panelXFraction":-2.5,"panelYFraction":2.0,"ballDocked":false,"ballXFraction":-0.5}""")
        assertEquals(OverlayThemeMode.LIGHT, clamped.themeMode)
        assertEquals(220, clamped.panelWidthDp)
        assertEquals(1800, clamped.panelHeightDp)
        assertEquals(-0.95f, clamped.panelXFraction, 0.001f)
        assertEquals(0.96f, clamped.panelYFraction, 0.001f)
        assertFalse(clamped.ballDocked)
        assertEquals(0f, clamped.ballXFraction, 0.001f)

        val minHeightMaxWidth = OverlayPrefs.fromJson("""{"panelWidthDp":2000,"panelHeightDp":80,"panelXFraction":2.0,"panelYFraction":-0.5,"ballXFraction":1.5}""")
        assertEquals(1400, minHeightMaxWidth.panelWidthDp)
        assertEquals(220, minHeightMaxWidth.panelHeightDp)
        assertEquals(0.98f, minHeightMaxWidth.panelXFraction, 0.001f)
        assertEquals(0f, minHeightMaxWidth.panelYFraction, 0.001f)
        assertEquals(0.98f, minHeightMaxWidth.ballXFraction, 0.001f)
    }

    @Test
    fun withResetWindowBoundsClearsGeometryWhilePreservingOtherPrefs() {
        val configured = OverlayPrefs(
            themeMode = OverlayThemeMode.DARK,
            panelOpacity = 0.75f,
            panelExpandedTall = true,
            panelXFraction = 0.12f,
            panelYFraction = 0.28f,
            panelWidthDp = 360,
            panelHeightDp = 520,
            ballDocked = false,
            ballXFraction = 0.48f,
            ballYFraction = 0.55f,
            quickAskPrompt = "自定义指令"
        )
        val reset = configured.withResetWindowBounds()
        assertEquals(OverlayPrefs.UNSET_FRACTION, reset.panelXFraction, 0.001f)
        assertEquals(OverlayPrefs.UNSET_FRACTION, reset.panelYFraction, 0.001f)
        assertEquals(0, reset.panelWidthDp)
        assertEquals(0, reset.panelHeightDp)
        assertFalse(reset.panelExpandedTall)

        assertEquals(OverlayThemeMode.DARK, reset.themeMode)
        assertEquals(0.75f, reset.panelOpacity, 0.001f)
        assertFalse(reset.ballDocked)
        assertEquals(0.48f, reset.ballXFraction, 0.001f)
        assertEquals(0.55f, reset.ballYFraction, 0.001f)
        assertEquals("自定义指令", reset.quickAskPrompt)
    }

    @Test
    fun overlayWindowGeometryResolvesDragsResizesAndDetectsBallEdgeSnap() {
        val screenW = 1080
        val screenH = 2400
        val density = 3.0f

        val defaultBounds = OverlayWindowGeometry.resolvePanelBounds(OverlayPrefs(), screenW, screenH, density)
        assertEquals(1004, defaultBounds.width)
        assertEquals(1200, defaultBounds.height)
        assertEquals(38, defaultBounds.x)
        assertEquals(168, defaultBounds.y)

        val tallBounds = OverlayWindowGeometry.resolvePanelBounds(
            OverlayPrefs(panelExpandedTall = true),
            screenW,
            screenH,
            density
        )
        assertEquals(1776, tallBounds.height)

        val restoredBounds = OverlayWindowGeometry.resolvePanelBounds(
            OverlayPrefs(
                panelXFraction = -0.25f,
                panelYFraction = 0.25f,
                panelWidthDp = 320,
                panelHeightDp = 400
            ),
            screenW,
            screenH,
            density
        )
        assertEquals(960, restoredBounds.width)
        assertEquals(1200, restoredBounds.height)
        assertEquals(-270, restoredBounds.x)
        assertEquals(600, restoredBounds.y)

        val draggedLeft = OverlayWindowGeometry.applyPanelDrag(defaultBounds, -500, 300, screenW, screenH, density)
        assertEquals(-462, draggedLeft.x)
        assertEquals(468, draggedLeft.y)
        assertEquals(defaultBounds.width, draggedLeft.width)
        assertEquals(defaultBounds.height, draggedLeft.height)

        val draggedExtremeLeftUp = OverlayWindowGeometry.applyPanelDrag(defaultBounds, -2000, -500, screenW, screenH, density)
        assertEquals(192 - defaultBounds.width, draggedExtremeLeftUp.x)
        assertEquals(0, draggedExtremeLeftUp.y)

        val draggedExtremeRightDown = OverlayWindowGeometry.applyPanelDrag(defaultBounds, 2000, 3000, screenW, screenH, density)
        assertEquals(screenW - 192, draggedExtremeRightDown.x)
        assertEquals(screenH - 192, draggedExtremeRightDown.y)

        val base = WindowBoundsPx(x = 120, y = 200, width = 880, height = 1000)
        val brShrunkMin = OverlayWindowGeometry.applyPanelResize(
            base,
            ResizeEdge.BOTTOM_RIGHT,
            dx = -400,
            dy = -500,
            screenW = screenW,
            screenH = screenH,
            density = density
        )
        assertEquals(744, brShrunkMin.width)
        assertEquals(780, brShrunkMin.height)
        assertEquals(120, brShrunkMin.x)
        assertEquals(200, brShrunkMin.y)

        val brGrownMax = OverlayWindowGeometry.applyPanelResize(
            base,
            ResizeEdge.BOTTOM_RIGHT,
            dx = 500,
            dy = 2000,
            screenW = screenW,
            screenH = screenH,
            density = density
        )
        assertEquals(1044, brGrownMax.width)
        assertEquals(2292, brGrownMax.height)

        val blExpanded = OverlayWindowGeometry.applyPanelResize(
            base,
            ResizeEdge.BOTTOM_LEFT,
            dx = -100,
            dy = 150,
            screenW = screenW,
            screenH = screenH,
            density = density
        )
        assertEquals(980, blExpanded.width)
        assertEquals(20, blExpanded.x)
        assertEquals(1000, blExpanded.x + blExpanded.width)
        assertEquals(1150, blExpanded.height)

        val blClampedMin = OverlayWindowGeometry.applyPanelResize(
            base,
            ResizeEdge.BOTTOM_LEFT,
            dx = 400,
            dy = -500,
            screenW = screenW,
            screenH = screenH,
            density = density
        )
        assertEquals(744, blClampedMin.width)
        assertEquals(256, blClampedMin.x)
        assertEquals(1000, blClampedMin.x + blClampedMin.width)
        assertEquals(780, blClampedMin.height)

        val blClampedMax = OverlayWindowGeometry.applyPanelResize(
            base,
            ResizeEdge.BOTTOM_LEFT,
            dx = -400,
            dy = 0,
            screenW = screenW,
            screenH = screenH,
            density = density
        )
        assertEquals(1044, blClampedMax.width)
        assertEquals(-44, blClampedMax.x)
        assertEquals(1000, blClampedMax.x + blClampedMax.width)

        val bottomOnly = OverlayWindowGeometry.applyPanelResize(
            base,
            ResizeEdge.BOTTOM,
            dx = 150,
            dy = 300,
            screenW = screenW,
            screenH = screenH,
            density = density
        )
        assertEquals(base.x, bottomOnly.x)
        assertEquals(base.width, bottomOnly.width)
        assertEquals(1300, bottomOnly.height)

        assertTrue(OverlayWindowGeometry.shouldSnapBallToEdge(0, 144, screenW, density))
        assertTrue(OverlayWindowGeometry.shouldSnapBallToEdge(84, 144, screenW, density))
        assertFalse(OverlayWindowGeometry.shouldSnapBallToEdge(85, 144, screenW, density))
        assertFalse(OverlayWindowGeometry.shouldSnapBallToEdge(851, 144, screenW, density))
        assertTrue(OverlayWindowGeometry.shouldSnapBallToEdge(852, 144, screenW, density))
    }

    @Test
    fun overlayThemeModeLabelsAndNextCycleAreConsistent() {
        assertEquals("自动", OverlayThemeMode.FOLLOW_SYSTEM.displayName)
        assertEquals("自", OverlayThemeMode.FOLLOW_SYSTEM.shortLabel)
        assertEquals("浅色", OverlayThemeMode.LIGHT.displayName)
        assertEquals("浅", OverlayThemeMode.LIGHT.shortLabel)
        assertEquals("深色", OverlayThemeMode.DARK.displayName)
        assertEquals("深", OverlayThemeMode.DARK.shortLabel)

        assertEquals(OverlayThemeMode.LIGHT, OverlayThemeMode.FOLLOW_SYSTEM.next())
        assertEquals(OverlayThemeMode.DARK, OverlayThemeMode.LIGHT.next())
        assertEquals(OverlayThemeMode.FOLLOW_SYSTEM, OverlayThemeMode.DARK.next())
    }
}
