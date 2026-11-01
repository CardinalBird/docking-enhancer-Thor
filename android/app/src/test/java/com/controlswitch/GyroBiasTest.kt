package com.controlswitch

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class GyroBiasTest {
    private val flat = floatArrayOf(0f, 0f, 9.8f)

    @Test
    fun learnsTheOffsetAtRestAndKeepsItWhileMoving() {
        val b = GyroBias(window = 10)
        // At rest, the Thor's 0.33 rad/s offset on y, with sensor noise.
        repeat(10) { b.correct(floatArrayOf(0.001f * (it % 2), -0.33f, 0.01f), flat) }
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), b.correct(floatArrayOf(0.0005f, -0.33f, 0.01f), flat), 0.002f)
        // A real turn while moving: offset removed, turn kept, offset not relearned.
        repeat(9) { b.correct(floatArrayOf(0.5f * it, -0.33f, 0.01f), floatArrayOf(it.toFloat(), 0f, 9.8f)) }
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), b.correct(floatArrayOf(1f, -0.33f, 0.01f), flat), 0.002f)
    }

    @Test
    fun noRestNoCorrection() {
        val b = GyroBias(window = 10)
        repeat(10) { b.correct(floatArrayOf(it * 0.1f, 0f, 0f), flat) }
        assertArrayEquals(floatArrayOf(2f, 0f, 0f), b.correct(floatArrayOf(2f, 0f, 0f), flat), 0f)
    }
}
