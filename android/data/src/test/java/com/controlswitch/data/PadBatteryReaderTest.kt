package com.controlswitch.data

import com.controlswitch.domain.model.PadBattery
import org.junit.Assert.assertEquals
import org.junit.Test

class PadBatteryReaderTest {
    @Test
    fun readsLevelAndChargingFromSwitchReports() {
        val out = """
            Nintendo Switch Pro Controller| 30 8e 91
            Joy-Con (L)| 21 05 41
            Some Keyboard| 01 00 00
            Silent Pad|
        """.trimIndent()
        assertEquals(
            mapOf(
                "Nintendo Switch Pro Controller" to PadBattery(level = 4, charging = true),
                "Joy-Con (L)" to PadBattery(level = 2, charging = false),
            ),
            parsePadBatteries(out),
        )
    }
}
