package com.odininputmirror.ui

import com.odininputmirror.domain.model.ControlRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PadCaptureTest {
    @Test
    fun `only a real external pad is captured`() {
        assertTrue(isCaptureSource("8BitDo Ultimate 2", isVirtual = false, isGamepad = true, internalPadName = "Odin Controller"))
        // The handheld's own pad drives the editor; taking its presses would trap the overlay.
        assertFalse(isCaptureSource("Odin Controller", isVirtual = false, isGamepad = true, internalPadName = "odin controller"))
        assertFalse(isCaptureSource("8BitDo", isVirtual = true, isGamepad = true, internalPadName = null))
        // Volume keys and the like are keyboards, not pads.
        assertFalse(isCaptureSource("gpio-keys", isVirtual = false, isGamepad = false, internalPadName = null))
        assertFalse(isCaptureSource(null, isVirtual = false, isGamepad = true, internalPadName = null))
    }

    @Test
    fun `a button binds by its raw evdev code on the first press only`() {
        assertEquals(ControlRef.button(0x131), buttonSource(0x131, isDown = true, repeatCount = 0))
        assertEquals(null, buttonSource(0x131, isDown = false, repeatCount = 0))
        assertEquals(null, buttonSource(0x131, isDown = true, repeatCount = 1))
        // Synthetic key events (the Activity's own D-pad taps) carry no scan code.
        assertEquals(null, buttonSource(0, isDown = true, repeatCount = 0))
    }

    @Test
    fun `android axis names map back to evdev codes, layout first`() {
        assertEquals(0x10, evdevAxis("HAT_X", emptyMap()))
        assertEquals(0x0a, evdevAxis("BRAKE", emptyMap()))
        assertEquals(0x0a, evdevAxis("LTRIGGER", mapOf(0x0a to "LTRIGGER")))
        assertEquals(0x02, evdevAxis("Z", mapOf(0x02 to "Z")))
        assertEquals(null, evdevAxis("LTRIGGER", emptyMap()))
    }

    @Test
    fun `an axis reports each push once`() {
        val edges = AxisEdges()
        assertEquals(null, edges.feed(0x01, 0.3f))
        assertEquals(ControlRef.half(0x01, -1), edges.feed(0x01, -0.6f))
        assertEquals(null, edges.feed(0x01, -0.9f))
        // Wobbling around the line without coming back to rest is still the same push.
        assertEquals(null, edges.feed(0x01, -0.4f))
        assertEquals(null, edges.feed(0x01, -0.6f))
        assertEquals(null, edges.feed(0x01, 0.1f))
        assertEquals(ControlRef.half(0x01, -1), edges.feed(0x01, -0.7f))
        // Straight across is a new push the other way.
        assertEquals(ControlRef.half(0x01, 1), edges.feed(0x01, 0.8f))
    }
}
