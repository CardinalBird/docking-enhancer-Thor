package com.controlswitch

import android.os.SystemClock
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.random.Random

/**
 * A DSU (cemuhook) client for the app's own motion port, the way an emulator subscribes: whatever
 * serves 127.0.0.1:26760 right now (the Thor's gyro from [ThorMotionServer], or the controller's
 * from the daemon while it mirrors) is what [onSample] receives. Gyro is pitch, yaw, roll in
 * deg/s; accel is x, y, z in g, both in DSU's PlayStation axes.
 */
internal class DsuClient(private val onSample: (gyro: FloatArray, accel: FloatArray) -> Unit) {
    private val id = Random.nextInt()
    @Volatile private var socket: DatagramSocket? = null

    fun start() {
        if (socket != null) return
        // Bound to 127.0.0.1 so the server answers to a v4 loopback address; getLoopbackAddress() is ::1 here.
        val s = runCatching { DatagramSocket(0, LOOPBACK).apply { soTimeout = 1000 } }.getOrNull() ?: return
        socket = s
        Thread({ loop(s) }, "dsu-client").start()
    }

    fun stop() {
        socket?.close()
        socket = null
    }

    private fun loop(s: DatagramSocket) {
        val server = InetSocketAddress(LOOPBACK, Dsu.PORT)
        val request = request()
        val buf = ByteArray(256)
        val p = DatagramPacket(buf, buf.size)
        var nextAsk = 0L
        while (!s.isClosed) {
            val now = SystemClock.elapsedRealtime()
            if (now >= nextAsk) {
                // Servers drop a client that stops asking; both of ours keep one for 5 s.
                runCatching { s.send(DatagramPacket(request, request.size, server)) }
                nextAsk = now + ASK_MS
            }
            try {
                s.receive(p)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: IOException) {
                break
            }
            if (p.length < DATA_LEN || buf[0] != 'D'.code.toByte() || buf[1] != 'S'.code.toByte() || buf[2] != 'U'.code.toByte() || buf[3] != 'S'.code.toByte()) continue
            val b = ByteBuffer.wrap(buf, 0, p.length).order(ByteOrder.LITTLE_ENDIAN)
            if (b.getInt(16) != Dsu.MSG_DATA) continue
            onSample(FloatArray(3) { b.getFloat(GYRO_AT + 4 * it) }, FloatArray(3) { b.getFloat(ACCEL_AT + 4 * it) })
        }
    }

    /** "Send me controller data": every slot, no MAC filter. */
    private fun request(): ByteArray {
        val body = 8
        val b = ByteBuffer.allocate(20 + body).order(ByteOrder.LITTLE_ENDIAN)
        b.put("DSUC".toByteArray()).putShort(Dsu.PROTOCOL.toShort()).putShort((4 + body).toShort()).putInt(0).putInt(id).putInt(Dsu.MSG_DATA)
        b.put(0).put(0).put(ByteArray(6))
        b.putInt(8, CRC32().apply { update(b.array()) }.value.toInt())
        return b.array()
    }

    private companion object {
        val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        const val ASK_MS = 1000L
        // Header 20 + 80-byte data body (see Dsu.data): accel floats at 76, gyro floats at 88.
        const val DATA_LEN = 100
        const val ACCEL_AT = 76
        const val GYRO_AT = 88
    }
}
