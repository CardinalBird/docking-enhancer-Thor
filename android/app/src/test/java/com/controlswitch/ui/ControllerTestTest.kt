package com.controlswitch.ui

import android.view.KeyEvent
import android.view.MotionEvent
import com.controlswitch.domain.model.PadLayout
import com.controlswitch.domain.model.SlotIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerTestTest {
    private val all = listOf(
        SlotIcon.A, SlotIcon.B, SlotIcon.X, SlotIcon.Y, SlotIcon.LEFT_BUMPER, SlotIcon.RIGHT_BUMPER,
        SlotIcon.LEFT_TRIGGER, SlotIcon.RIGHT_TRIGGER,
        SlotIcon.DPAD_UP, SlotIcon.DPAD_DOWN, SlotIcon.DPAD_LEFT, SlotIcon.DPAD_RIGHT,
        SlotIcon.LEFT_STICK_UP, SlotIcon.LEFT_STICK_CLICK, SlotIcon.RIGHT_STICK_UP, SlotIcon.RIGHT_STICK_CLICK,
        SlotIcon.VIEW, SlotIcon.MENU, SlotIcon.HOME,
    )
    private fun fresh(analog: Boolean = true) = PadTest(all, analogTriggers = analog)
    private fun PadTest.tap(icon: SlotIcon) = button(icon, true).button(icon, false)

    @Test
    fun `a button passes on press then release, not on press alone`() {
        val pressed = fresh().button(SlotIcon.A, true)
        assertTrue(pressed.lit(SlotIcon.A))
        assertFalse(pressed.passed(SlotIcon.A))
        val released = pressed.button(SlotIcon.A, false)
        assertFalse(released.lit(SlotIcon.A))
        assertTrue(released.passed(SlotIcon.A))
    }

    @Test
    fun `a release with no press before it does nothing`() {
        assertEquals(fresh(), fresh().button(SlotIcon.A, false))
    }

    @Test
    fun `octant maps the eight directions and ignores a small push`() {
        assertEquals(0, octant(1f, 0f))
        assertEquals(2, octant(0f, 1f))
        assertEquals(4, octant(-1f, 0f))
        assertEquals(6, octant(0f, -1f))
        assertEquals(1, octant(0.7f, 0.7f))
        assertEquals(7, octant(0.7f, -0.7f))
        assertNull(octant(0.5f, 0f))
        assertNull(octant(0.79f, 0f))
        assertEquals(0, octant(0.8f, 0f))
        // A square-gated stick reaches past the unit circle on the diagonals.
        assertEquals(7, octant(1f, -1f))
    }

    private fun circle(test: PadTest, right: Boolean, skip: Int = -1): PadTest {
        var t = test
        for (i in 0 until 8) {
            if (i == skip) continue
            val angle = i * Math.PI / 4
            t = t.stick(right, Math.cos(angle).toFloat(), Math.sin(angle).toFloat()).stick(right, 0f, 0f)
        }
        return t
    }

    @Test
    fun `a stick passes only after all eight octants at full tilt`() {
        val seven = circle(fresh(), right = false, skip = 3)
        assertFalse(seven.passed(SlotIcon.LEFT_STICK_UP))
        assertEquals(7, seven.left.reached)
        // Reaching the missing one under full tilt adds nothing.
        val still = seven.stick(false, -0.5f, 0.5f)
        assertEquals(7, still.left.reached)
        val full = still.stick(false, -0.6f, 0.6f)
        assertTrue(full.passed(SlotIcon.LEFT_STICK_UP))
        assertEquals(0, full.right.reached)
        // Per-axis events in between count only where the stick really pointed.
        val partial = fresh().stick(false, 1f, 0f).stick(false, 0f, 0f).stick(false, 0f, -1f)
        assertEquals(0b01000001, partial.left.octants)
    }

    @Test
    fun `the stick click is its own row and the dot follows the stick`() {
        val t = fresh().stick(true, 0.3f, -0.2f).tap(SlotIcon.RIGHT_STICK_CLICK)
        assertTrue(t.passed(SlotIcon.RIGHT_STICK_CLICK))
        assertFalse(t.passed(SlotIcon.RIGHT_STICK_UP))
        assertEquals(0.3f, t.right.x)
        assertEquals(-0.2f, t.right.y)
    }

    @Test
    fun `an analog trigger passes on a full pull, never on its digital click`() {
        assertFalse(fresh().tap(SlotIcon.LEFT_TRIGGER).passed(SlotIcon.LEFT_TRIGGER))
        val part = fresh().trigger(false, 0.89f)
        assertFalse(part.passed(SlotIcon.LEFT_TRIGGER))
        assertTrue(part.lit(SlotIcon.LEFT_TRIGGER))
        val full = part.trigger(false, 0.9f).trigger(false, 0f)
        assertTrue(full.passed(SlotIcon.LEFT_TRIGGER))
        assertFalse(full.lit(SlotIcon.LEFT_TRIGGER))
        assertFalse(full.passed(SlotIcon.RIGHT_TRIGGER))
    }

    @Test
    fun `a digital-only trigger passes by its button`() {
        assertTrue(fresh(analog = false).tap(SlotIcon.RIGHT_TRIGGER).passed(SlotIcon.RIGHT_TRIGGER))
    }

    @Test
    fun `the hat presses and releases the D-pad, and only on a change`() {
        val up = fresh().hat(0f, -1f)
        assertTrue(up.lit(SlotIcon.DPAD_UP))
        assertFalse(up.passed(SlotIcon.DPAD_UP))
        val back = up.hat(0f, 0f)
        assertTrue(back.passed(SlotIcon.DPAD_UP))
        val diagonal = back.hat(1f, 1f)
        assertTrue(diagonal.lit(SlotIcon.DPAD_RIGHT) && diagonal.lit(SlotIcon.DPAD_DOWN))
        // A hat resting at centre, reported with every stick move, must not release a key-driven press.
        val keyed = fresh().button(SlotIcon.DPAD_LEFT, true).hat(0f, 0f)
        assertTrue(keyed.lit(SlotIcon.DPAD_LEFT))
        // Rocking straight from one side to the other passes both.
        val rocked = fresh().hat(0f, -1f).hat(0f, 1f).hat(0f, 0f)
        assertTrue(rocked.passed(SlotIcon.DPAD_UP) && rocked.passed(SlotIcon.DPAD_DOWN))
    }

    @Test
    fun `passedCount counts checklist rows only`() {
        val t = PadTest(listOf(SlotIcon.A, SlotIcon.LEFT_STICK_UP), analogTriggers = false)
            .tap(SlotIcon.A).tap(SlotIcon.B).tap(SlotIcon.X)
        assertEquals(1, t.passedCount)
        assertEquals(2, circle(t, right = false).passedCount)
    }

    private val thorAxes = setOf(
        MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
        MotionEvent.AXIS_GAS, MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y,
    )

    @Test
    fun `padAxes reads the right stick and triggers like the mapping editor`() {
        assertEquals(PadAxes(MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS), padAxes { it in thorAxes })
        val named = thorAxes - MotionEvent.AXIS_GAS - MotionEvent.AXIS_BRAKE + MotionEvent.AXIS_LTRIGGER + MotionEvent.AXIS_RTRIGGER
        assertEquals(PadAxes(MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER), padAxes { it in named })
        val xpad = setOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RX, MotionEvent.AXIS_RY, MotionEvent.AXIS_RZ)
        assertEquals(PadAxes(MotionEvent.AXIS_RX, MotionEvent.AXIS_RY, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ), padAxes { it in xpad })
        // A Pro Controller on its own node: sticks on X/Y and RX/RY, digital ZL/ZR.
        val pro = setOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_RX, MotionEvent.AXIS_RY)
        assertEquals(PadAxes(MotionEvent.AXIS_RX, MotionEvent.AXIS_RY, null, null), padAxes { it in pro })
    }

    private val thorKeys = setOf(
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
        KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_MODE,
        KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR, KeyEvent.KEYCODE_BUTTON_Z,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
    )

    @Test
    fun `padItems lists only what the pad declares`() {
        val axes = padAxes { it in thorAxes }
        val thor = padItems({ it in thorKeys }, { it in thorAxes }, axes, builtIn = true)
        assertEquals(all - SlotIcon.HOME, thor)
        val external = padItems({ it in thorKeys }, { it in thorAxes }, axes, builtIn = false)
        assertEquals(all, external)
        // No right click, no Guide, D-pad as keys only, digital triggers.
        val keys = thorKeys - KeyEvent.KEYCODE_BUTTON_THUMBR - KeyEvent.KEYCODE_BUTTON_MODE
        val proAxes = setOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_RX, MotionEvent.AXIS_RY)
        val pro = padItems({ it in keys }, { it in proAxes }, padAxes { it in proAxes }, builtIn = false)
        assertEquals(all - SlotIcon.HOME - SlotIcon.RIGHT_STICK_CLICK, pro)
        // A hat alone still gives the four D-pad rows; nothing declared gives nothing.
        val hatOnly = padItems({ false }, { it == MotionEvent.AXIS_HAT_X }, padAxes { false }, builtIn = false)
        assertEquals(listOf(SlotIcon.DPAD_UP, SlotIcon.DPAD_DOWN, SlotIcon.DPAD_LEFT, SlotIcon.DPAD_RIGHT), hatOnly)
        assertEquals(emptyList<SlotIcon>(), padItems({ false }, { false }, padAxes { false }, builtIn = false))
    }

    @Test
    fun `faces are relabelled for an external Nintendo pad only, and B is the way out`() {
        assertEquals(SlotIcon.B, faceLabel(SlotIcon.A, PadLayout.NINTENDO, builtIn = false))
        assertEquals(SlotIcon.A, faceLabel(SlotIcon.A, PadLayout.NINTENDO, builtIn = true))
        assertEquals(SlotIcon.A, faceLabel(SlotIcon.A, PadLayout.XBOX, builtIn = false))
        assertEquals(SlotIcon.A, exitIcon(PadLayout.NINTENDO, builtIn = false))
        assertEquals(SlotIcon.B, exitIcon(PadLayout.NINTENDO, builtIn = true))
        assertEquals(SlotIcon.B, exitIcon(PadLayout.XBOX, builtIn = false))
        assertEquals("B", itemLabel(SlotIcon.A, PadLayout.NINTENDO, builtIn = false))
        assertEquals("ZL", itemLabel(SlotIcon.LEFT_TRIGGER, PadLayout.NINTENDO, builtIn = false))
        assertEquals("LT", itemLabel(SlotIcon.LEFT_TRIGGER, PadLayout.XBOX, builtIn = false))
    }

    @Test
    fun `unknown keys are not in the map`() {
        assertNull(PAD_KEYS[KeyEvent.KEYCODE_BUTTON_Z])
        assertNull(PAD_KEYS[KeyEvent.KEYCODE_BACK])
        assertEquals(SlotIcon.HOME, PAD_KEYS[KeyEvent.KEYCODE_BUTTON_MODE])
    }
}
