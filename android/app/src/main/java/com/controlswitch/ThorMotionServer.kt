package com.controlswitch

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import kotlin.random.Random

/**
 * The handheld half of the motion switch: serves the Thor's own gyro over DSU on 127.0.0.1:26760
 * while the mirror is off. Docked, the daemon serves the controller's gyro on the same address
 * (input_mirror's dsu.c), so an emulator set up once for DSU follows whichever is in your hands.
 *
 * The supervisor calls [serve] every tick, and with false just before it starts the mirror, so the
 * daemon can take the port. Sensors are read only while an emulator is subscribed.
 */
internal class ThorMotionServer(context: Context) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val accelSensor = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroSensor = sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val clients = ConcurrentHashMap<SocketAddress, Long>()
    private val serverId = Random.nextInt()
    private val accel = FloatArray(3)
    // Kept for the service's life, so the offset learned in one session holds in the next.
    private val bias = GyroBias()
    private val sensorHandler by lazy { Handler(HandlerThread("thor-motion-sensors").apply { start() }.looper) }
    private var packet = 0

    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var listening = false

    /** Serve the Thor's gyro while [on]; release the port while not. Cheap to call repeatedly. */
    @Synchronized
    fun serve(on: Boolean) {
        val current = socket
        if (on && current == null && gyroSensor != null && accelSensor != null) {
            // Fails while the daemon still holds the port (just undocked): the next tick retries.
            val s = runCatching {
                DatagramSocket(null).apply {
                    // 127.0.0.1 exactly: emulators connect there, and getLoopbackAddress() is ::1 here.
                    bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), Dsu.PORT))
                    soTimeout = 1000
                }
            }.getOrNull() ?: return
            socket = s
            Thread({ receiveLoop(s) }, "thor-motion").start()
            Log.i(TAG, "Serving the Thor's motion over DSU")
        } else if (!on && current != null) {
            socket = null
            current.close()
            clients.clear()
            stopListening()
        }
    }

    private fun receiveLoop(s: DatagramSocket) {
        val buf = ByteArray(256)
        val p = DatagramPacket(buf, buf.size)
        while (!s.isClosed) {
            try {
                s.receive(p)
                handle(s, buf, p.length, p.socketAddress)
            } catch (_: SocketTimeoutException) {
                // Just a chance to drop clients that stopped asking.
            } catch (_: IOException) {
                break
            }
            val now = SystemClock.elapsedRealtime()
            clients.entries.removeIf { it.value <= now }
            if (clients.isEmpty()) stopListening()
        }
    }

    private fun handle(s: DatagramSocket, b: ByteArray, n: Int, from: SocketAddress) {
        if (n < 20 || b[0] != 'D'.code.toByte() || b[1] != 'S'.code.toByte() || b[2] != 'U'.code.toByte() || b[3] != 'C'.code.toByte()) return
        val req = ByteBuffer.wrap(b, 0, n).order(ByteOrder.LITTLE_ENDIAN)
        when (req.getInt(16)) {
            Dsu.MSG_VERSION -> send(s, Dsu.version(serverId), from)
            Dsu.MSG_INFO -> if (n >= 24) {
                val count = req.getInt(20)
                for (i in 0 until minOf(count, 4, n - 24)) {
                    val slot = b[24 + i].toInt()
                    if (slot in 0..3) send(s, Dsu.info(serverId, slot, connected = slot == 0), from)
                }
            }
            Dsu.MSG_DATA -> {
                if (clients.put(from, SystemClock.elapsedRealtime() + SUBSCRIPTION_MS) == null) {
                    Log.i(TAG, "DSU client subscribed: $from")
                }
                startListening()
            }
        }
    }

    private fun send(s: DatagramSocket, bytes: ByteArray, to: SocketAddress) {
        runCatching { s.send(DatagramPacket(bytes, bytes.size, to)) }
    }

    @Synchronized
    private fun startListening() {
        if (listening) return
        listening = true
        // Delivered off the main thread: the callback sends packets, and Android refuses network
        // I/O on the main thread (the send would fail silently inside runCatching).
        // 200 Hz, about what a Switch pad's IMU delivers, instead of SENSOR_DELAY_GAME's 50 Hz; the
        // sh5001 goes to 879 Hz. Zero report latency so the sensor hub hands each sample over at once.
        sensors?.registerListener(this, accelSensor, SAMPLE_PERIOD_US, 0, sensorHandler)
        sensors?.registerListener(this, gyroSensor, SAMPLE_PERIOD_US, 0, sensorHandler)
    }

    @Synchronized
    private fun stopListening() {
        if (!listening) return
        listening = false
        sensors?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, accel, 0, 3)
            return
        }
        val s = socket ?: return
        val bytes = Dsu.data(serverId, packet++, event.timestamp / 1000, Dsu.thorAccel(accel), Dsu.thorGyro(bias.correct(event.values, accel)))
        clients.keys.forEach { send(s, bytes, it) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val TAG = "ThorMotionServer"
        // Clients re-ask about once a second.
        const val SUBSCRIPTION_MS = 5_000L
        const val SAMPLE_PERIOD_US = 5_000
    }
}

/** DSU (cemuhook) packets, the same ones input_mirror's dsu.c builds. github.com/v1993/cemuhook-protocol */
internal object Dsu {
    const val PORT = 26760
    const val MSG_VERSION = 0x100000
    const val MSG_INFO = 0x100001
    const val MSG_DATA = 0x100002
    private const val PROTOCOL = 1001
    private const val GRAVITY = 9.80665f
    private const val DEG_PER_RAD = 57.29578f

    /**
     * Android's device axes (x right, y toward the top edge, z out of the screen; the Thor lying
     * flat reads +g on z) to DSU's PlayStation axes (x right, y up, z toward the player), in g.
     */
    fun thorAccel(a: FloatArray) = floatArrayOf(a[0] / GRAVITY, a[2] / GRAVITY, -a[1] / GRAVITY)

    /** Same axes for rotation, rad/s to deg/s: pitch, yaw, roll. */
    fun thorGyro(g: FloatArray) = floatArrayOf(g[0] * DEG_PER_RAD, g[2] * DEG_PER_RAD, -g[1] * DEG_PER_RAD)

    fun version(id: Int): ByteArray = packet(id, MSG_VERSION, 2) { putShort(PROTOCOL.toShort()) }

    fun info(id: Int, slot: Int, connected: Boolean): ByteArray = packet(id, MSG_INFO, 12) {
        slotInfo(slot, connected)
        put(0)
    }

    fun data(id: Int, packet: Int, timestampUs: Long, accel: FloatArray, gyro: FloatArray): ByteArray =
        packet(id, MSG_DATA, 80) {
            slotInfo(0, connected = true)
            put(1)                                   // connected
            putInt(packet)
            put(ByteArray(4))                        // buttons, home, touch
            repeat(4) { put(128.toByte()) }          // sticks at rest
            put(ByteArray(24))                       // analog buttons, two touches
            putLong(timestampUs)
            accel.forEach { putFloat(it) }
            gyro.forEach { putFloat(it) }
        }

    private fun ByteBuffer.slotInfo(slot: Int, connected: Boolean) {
        put(slot.toByte())
        val state: Byte = if (connected) 2 else 0
        put(state); put(state); put(state)           // connected, full gyro, Bluetooth
        // The same MAC the daemon reports, so an emulator sees one controller across the swap.
        put(byteArrayOf(0, 0, 0, 0, 0, if (connected) 1 else 0))
        put(if (connected) 0x05 else 0)              // battery: full
    }

    private fun packet(id: Int, type: Int, body: Int, fill: ByteBuffer.() -> Unit): ByteArray {
        val b = ByteBuffer.allocate(20 + body).order(ByteOrder.LITTLE_ENDIAN)
        b.put("DSUS".toByteArray()).putShort(PROTOCOL.toShort()).putShort((4 + body).toShort()).putInt(0).putInt(id).putInt(type)
        b.fill()
        val crc = CRC32().apply { update(b.array()) }.value.toInt()
        b.putInt(8, crc)
        return b.array()
    }
}
