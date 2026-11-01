package com.controlswitch

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class DsuTest {
    @Test
    fun thorLyingFlatHasGravityOnDsuUp() {
        assertArrayEquals(floatArrayOf(0f, 1f, 0f), Dsu.thorAccel(floatArrayOf(0f, 0f, 9.80665f)), 1e-4f)
        // Turning about the screen's normal is yaw.
        assertArrayEquals(floatArrayOf(0f, 57.29578f, 0f), Dsu.thorGyro(floatArrayOf(0f, 0f, 1f)), 1e-3f)
    }

    @Test
    fun dataPacketHasLengthCrcAndMotion() {
        val p = Dsu.data(7, 42, 123_456, floatArrayOf(0f, 1f, 0f), floatArrayOf(1f, 2f, 3f))
        assertEquals(100, p.size)
        val b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(84, b.getShort(6).toInt())
        val crc = b.getInt(8)
        val zeroed = p.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(8, 0) }
        assertEquals(CRC32().apply { update(zeroed) }.value.toInt(), crc)
        assertEquals(42, b.getInt(32))
        assertEquals(1f, b.getFloat(80), 0f)      // accel y
        assertEquals(3f, b.getFloat(96), 0f)      // gyro roll
        assertEquals(22, Dsu.version(7).size)
        assertEquals(32, Dsu.info(7, 0, true).size)
    }
}
