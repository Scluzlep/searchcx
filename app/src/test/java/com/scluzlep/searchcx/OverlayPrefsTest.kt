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
}
