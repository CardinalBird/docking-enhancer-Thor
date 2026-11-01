package com.controlswitch

/**
 * Removes the Thor's gyro offset: sitting still, its calibrated gyroscope still reports about
 * 0.33 rad/s (19 deg/s) on one axis, which an emulator turns into a constant spin.
 *
 * Samples are taken in windows of [window]. A window in which neither the gyro nor gravity moved
 * (each axis within [stillGyro] rad/s and [stillAccel] m/s²) is the device at rest, so its mean is
 * the offset. The offset is kept between rest windows and subtracted from every sample.
 *
 * ponytail: a perfectly steady turn about gravity for a whole window would pass for rest and be
 * absorbed into the offset until the next real rest; widen the window if that ever shows up.
 */
internal class GyroBias(
    private val window: Int = 50,          // ~1 s at SENSOR_DELAY_GAME
    private val stillGyro: Float = 0.03f,  // ~1.7 deg/s: tighter than a hand, looser than a table
    private val stillAccel: Float = 0.3f,
) {
    val bias = FloatArray(3)
    private val sum = FloatArray(3)
    private val gMin = FloatArray(3)
    private val gMax = FloatArray(3)
    private val aMin = FloatArray(3)
    private val aMax = FloatArray(3)
    private var n = 0

    /** One gyro sample (rad/s) with the latest accelerometer reading; returns it minus the offset. */
    fun correct(gyro: FloatArray, accel: FloatArray): FloatArray {
        for (i in 0..2) {
            if (n == 0) {
                gMin[i] = gyro[i]; gMax[i] = gyro[i]; aMin[i] = accel[i]; aMax[i] = accel[i]; sum[i] = 0f
            }
            sum[i] += gyro[i]
            gMin[i] = minOf(gMin[i], gyro[i]); gMax[i] = maxOf(gMax[i], gyro[i])
            aMin[i] = minOf(aMin[i], accel[i]); aMax[i] = maxOf(aMax[i], accel[i])
        }
        if (++n == window) {
            val still = (0..2).all { gMax[it] - gMin[it] < stillGyro && aMax[it] - aMin[it] < stillAccel }
            if (still) for (i in 0..2) bias[i] = sum[i] / n
            n = 0
        }
        return FloatArray(3) { gyro[it] - bias[it] }
    }
}
