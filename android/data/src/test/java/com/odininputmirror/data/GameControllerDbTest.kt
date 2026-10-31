package com.odininputmirror.data

import com.odininputmirror.domain.model.ControlRef
import com.odininputmirror.domain.model.odinFallbackTraits
import com.odininputmirror.domain.model.mappingSlots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameControllerDbTest {
    // A real line from the bundled database: the 8BitDo Ultimate, vendor 2dc8 product 3106, spelled
    // little-endian inside the GUID.
    private val eightBitDo =
        "03000000c82d00000631000010010000,8BitDo Ultimate,a:b0,b:b1,back:b6,dpdown:h0.4,dpleft:h0.8," +
            "dpright:h0.2,dpup:h0.1,leftshoulder:b4,leftstick:b7,lefttrigger:a4,leftx:a0,lefty:a1," +
            "rightshoulder:b5,rightstick:b8,righttrigger:a5,rightx:a2,righty:a3,start:b11,x:b3,y:b2," +
            "platform:Linux"

    @Test
    fun looksUpByVendorProductInsideTheGuid() {
        val db = GameControllerDb(sequenceOf("# comment", eightBitDo))

        assertNull(db.entryFor(0x2dc8, 0x9999))
        val entry = db.entryFor(0x2dc8, 0x3106)!!
        assertEquals("b0", entry["a"])
        assertEquals("h0.1", entry["dpup"])
    }

    @Test
    fun sdlButtonIndicesWalkDeclaredCodesGamepadRangeFirst() {
        // The walk starts at BTN_JOYSTICK and wraps to low codes last; gaps shift every later index,
        // which is exactly why the indices cannot be read as codes.
        val keys = setOf(0x130, 0x131, 0x133, 0x134, 0x13a, 0x9e)

        assertEquals(listOf(0x130, 0x131, 0x133, 0x134, 0x13a, 0x9e), SdlJoystickIndex.buttonCodes(keys))
    }

    @Test
    fun sdlAxisIndicesSkipTheHatRange() {
        // Hats become hN values, so ABS_HAT0X..ABS_HAT3Y never consume an axis index: on this pad
        // a4/a5 are the pedal axes even though the hat sits between them and the sticks.
        val axes = setOf(0x00, 0x01, 0x02, 0x05, 0x09, 0x0a, 0x10, 0x11)

        assertEquals(listOf(0x00, 0x01, 0x02, 0x05, 0x09, 0x0a), SdlJoystickIndex.axisCodes(axes))
    }

    @Test
    fun anEntryBecomesBindingsAgainstTheTargetSlots() {
        val slots = mappingSlots(odinFallbackTraits())
        val db = GameControllerDb(sequenceOf(eightBitDo))
        // A pad whose declared codes are deliberately NOT contiguous from BTN_SOUTH, so every index
        // lands on a different code than it would name literally — identity here would mean the
        // translation is not happening at all.
        val sourceKeys = setOf(0x131, 0x133, 0x134, 0x136, 0x137, 0x138, 0x139, 0x13a, 0x13b, 0x13c, 0x13d, 0x13e)
        val sourceAxes = setOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x10, 0x11)

        val mapping = GameControllerDb.toMapping(db.entryFor(0x2dc8, 0x3106)!!, sourceKeys, sourceAxes, slots)
        val byTarget = mapping.bindings.associate { it.target to it.source }

        // a:b0 -> the pad's FIRST declared button (0x131), onto the target's A (0x130).
        assertEquals(ControlRef.button(0x131), byTarget[ControlRef.button(0x130)])
        // dpup:h0.1 -> the source hat's negative Y half onto the target's own hat-up.
        assertEquals(ControlRef.half(0x11, -1), byTarget[ControlRef.half(0x11, -1)])
        // lefttrigger:a4 -> axis index 4 is code 0x05 on this pad (hat skipped, gap at 0). A trigger
        // rests at its minimum, so an axis filling a button-like role is its positive half.
        assertEquals(ControlRef.half(0x05, 1), byTarget[ControlRef.half(0x0a, 1)])
        // leftx:a0 -> the source axis's two halves onto the two direction slots (code 0x01 here).
        // Halves rather than one whole-axis binding because the editor's rows ARE the halves: a
        // whole-axis target matches no slot, displays as unbound, and gets purged as stale.
        assertEquals(ControlRef.half(0x01, 1), byTarget[ControlRef.half(0x00, 1)])
        assertEquals(ControlRef.half(0x01, -1), byTarget[ControlRef.half(0x00, -1)])
        // And the seed only ever names targets the editor lists, so nothing it writes can be purged.
        val slotTargets = slots.map { it.target }.toSet()
        assertTrue(mapping.bindings.all { it.target in slotTargets })
    }

    @Test
    fun whatCannotBeHonouredIsSkippedNotGuessed() {
        val slots = mappingSlots(odinFallbackTraits())
        val entry = mapOf(
            "a" to "b40",       // index past the pad's declared buttons
            "guide" to "b2",    // a role the target has no slot for
            "b" to "b0",
        )

        val mapping = GameControllerDb.toMapping(entry, setOf(0x130), setOf(0x00), slots)

        assertEquals(1, mapping.bindings.size)
        assertEquals(ControlRef.button(0x130), mapping.bindings.single().source)
    }

    @Test
    fun everySlotIconTheDatabaseCanNameExistsOnTheOdinList() {
        // If a role ever stops resolving to a slot, its bindings silently vanish from the default —
        // this pins the join between the two vocabularies.
        val icons = mappingSlots(odinFallbackTraits()).map { it.icon }.toSet()
        val roles = listOf(
            "a", "b", "x", "y", "back", "start", "leftshoulder", "rightshoulder",
            "lefttrigger", "righttrigger", "leftstick", "rightstick",
            "dpup", "dpdown", "dpleft", "dpright",
        )
        val entry = roles.associateWith { "b0" }

        val mapping = GameControllerDb.toMapping(entry, setOf(0x130), setOf(0x00), mappingSlots(odinFallbackTraits()))

        assertEquals(roles.size, mapping.bindings.size)
        assertTrue(icons.isNotEmpty())
    }

    // A Switch Pro Controller on an AYN Thor: the buttons its driver declares (no 306, D-pad as
    // BTN_DPAD_*, no hat) and the database's entries for it, in file order. The first entry was
    // written for a different driver; taking it put Home on D-pad up.
    @Test
    fun picksTheEntryThatFitsThePadNotTheFirstOne() {
        val proKeys = setOf(304, 305) + (307..318) + (544..547)
        val proAxes = setOf(0, 1, 3, 4)
        val db = GameControllerDb(
            sequenceOf(
                "030000007e0500000920000000026803,Nintendo Switch Pro Controller,a:b1,b:b0,back:b4,dpdown:b12,dpleft:b13,dpright:b14,dpup:b11,guide:b5,leftshoulder:b9,leftstick:b7,lefttrigger:a4,leftx:a0,lefty:a1,rightshoulder:b10,rightstick:b8,righttrigger:a5,rightx:a2,righty:a3,start:b6,x:b3,y:b2,platform:Linux,",
                "050000007e0500000920000001000000,Nintendo Switch Pro Controller,a:b0,b:b1,back:b8,dpdown:h0.4,dpleft:h0.8,dpright:h0.2,dpup:h0.1,guide:b12,leftshoulder:b4,leftstick:b10,lefttrigger:b6,leftx:a0,lefty:a1,misc1:b13,rightshoulder:b5,rightstick:b11,righttrigger:b7,rightx:a2,righty:a3,start:b9,x:b2,y:b3,platform:Linux,",
                "050000007e0500000920000001800000,Nintendo Switch Pro Controller,a:b0,b:b1,back:b9,dpdown:h0.4,dpleft:h0.8,dpright:h0.2,dpup:h0.1,guide:b11,leftshoulder:b5,leftstick:b12,lefttrigger:b7,leftx:a0,lefty:a1,misc1:b4,rightshoulder:b6,rightstick:b13,righttrigger:b8,rightx:a2,righty:a3,start:b10,x:b3,y:b2,platform:Linux,",
                "050000007e05000009200000ffff0f00,Nintendo Switch Pro Controller,a:b0,b:b1,back:b15,dpdown:b12,dpleft:b13,dpright:b14,dpup:b11,guide:b5,leftshoulder:b3,leftstick:b4,lefttrigger:b9,leftx:a0,lefty:a1,rightshoulder:b18,rightstick:b6,righttrigger:b10,rightx:a2,righty:a3,start:b16,x:b17,y:b2,platform:Android,",
            ),
        )

        val entry = db.entryFor(0x057e, 0x2009, proKeys)!!
        assertEquals("b11", entry["guide"])
        assertEquals("Linux", entry["platform"])

        val mapping = GameControllerDb.toMapping(entry, proKeys, proAxes, mappingSlots(odinFallbackTraits()))
        val sources = mapping.bindings.map { it.source }
        // Home and the stick clicks stay off the D-pad, and the absent hat binds nothing, so the
        // D-pad buttons are forwarded as the pad reports them.
        assertTrue(mapping.bindings.none { it.source == ControlRef.button(316) })
        assertTrue(sources.none { it.kind == com.odininputmirror.domain.model.ControlKind.HALF_AXIS && it.code in 0x10..0x11 })
        assertEquals(ControlRef.button(0x136), mapping.bindings.first { it.target == ControlRef.button(0x136) }.source)
    }
}
