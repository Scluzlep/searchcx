package com.scluzlep.searchcx

import com.scluzlep.searchcx.data.AppConfig
import com.scluzlep.searchcx.data.CollapsedBallStyle
import com.scluzlep.searchcx.data.OverlayPrefs
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
}
