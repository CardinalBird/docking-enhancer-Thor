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
        // Until the handheld's pad is identified there is nothing to exclude, so nothing is taken.
        assertFalse(isCaptureSource("8BitDo Ultimate 2", isVirtual = false, isGamepad = true, internalPadName = null))
    }

    private fun press(ref: ControlRef) = PadEdge(ref, pressed = true)
    private fun release(ref: ControlRef) = PadEdge(ref, pressed = false)

    @Test
    fun `a trigger resting at its minimum reports the pull and the let-go, never a push the other way`() {
        val edges = AxisEdges()
        val pull = ControlRef.half(0x02, 1)
        assertEquals(emptyList<PadEdge>(), edges.feed(0x02, -1f))
        assertEquals(listOf(press(pull)), edges.feed(0x02, 1f))
        assertEquals(listOf(release(pull)), edges.feed(0x02, -1f))
        assertEquals(listOf(press(pull)), edges.feed(0x02, 0.9f))
    }

    @Test
    fun `a button reports its press and its release by raw evdev code, never a repeat`() {
        assertEquals(press(ControlRef.button(0x131)), buttonEdge(0x131, isDown = true, repeatCount = 0))
        assertEquals(release(ControlRef.button(0x131)), buttonEdge(0x131, isDown = false, repeatCount = 0))
        assertEquals(null, buttonEdge(0x131, isDown = true, repeatCount = 1))
        // Synthetic key events (the Activity's own D-pad taps) carry no scan code.
        assertEquals(null, buttonEdge(0, isDown = true, repeatCount = 0))
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
    fun `an axis presses and releases each push once`() {
        val edges = AxisEdges()
        val up = ControlRef.half(0x01, -1)
        val down = ControlRef.half(0x01, 1)
        assertEquals(emptyList<PadEdge>(), edges.feed(0x01, 0.3f))
        assertEquals(listOf(press(up)), edges.feed(0x01, -0.6f))
        assertEquals(emptyList<PadEdge>(), edges.feed(0x01, -0.9f))
        // Wobbling around the line without coming back to rest is still the same push.
        assertEquals(emptyList<PadEdge>(), edges.feed(0x01, -0.4f))
        assertEquals(listOf(release(up)), edges.feed(0x01, 0.1f))
        assertEquals(listOf(press(up)), edges.feed(0x01, -0.7f))
        // Straight across lets go of one half and presses the other.
        assertEquals(listOf(release(up), press(down)), edges.feed(0x01, 0.8f))
    }
}
