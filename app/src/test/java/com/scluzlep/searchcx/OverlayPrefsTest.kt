package com.scluzlep.searchcx

import com.scluzlep.searchcx.data.CollapsedBallStyle
import com.scluzlep.searchcx.data.OverlayPrefs
import org.junit.Assert.assertEquals
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
}
