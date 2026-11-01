package com.controlswitch.data

import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.Parcel

/**
 * Root helper the daemon starts with `app_process` (see input_mirror.c `start_rumble_bridge`).
 *
 * Games rumble the Thor's own motor: the built-in "Odin Controller" has no force feedback, so a
 * game that asks the controller to vibrate falls back to the system vibrator. Nothing outside
 * system_server can see those calls, but system_server tells listeners when a vibrator turns on or
 * off. This registers one on every vibrator and prints `1`/`0` per change on stdout, which the
 * daemon replays as rumble on the external pad.
 *
 * Needs ACCESS_VIBRATOR_STATE (signature|privileged); it works because the daemon runs as root.
 * Exits when stdin closes, so it dies with the daemon even after a SIGKILL. Only on/off is
 * reported: Android gives listeners no amplitude.
 */
object RumbleBridge {
    private const val LISTENER = "android.os.IVibratorStateListener"

    @JvmStatic
    fun main(args: Array<String>) {
        Looper.prepareMainLooper()
        val service = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "vibrator_manager") as IBinder
        val manager = Class.forName("android.os.IVibratorManagerService\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, service)
        val listenerType = Class.forName(LISTENER)
        val ids = manager.javaClass.getMethod("getVibratorIds").invoke(manager) as IntArray
        val register = manager.javaClass.getMethod(
            "registerVibratorStateListener", Int::class.javaPrimitiveType, listenerType,
        )
        val on = BooleanArray(ids.size)
        ids.forEachIndexed { i, id ->
            val binder = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
                    data.enforceInterface(LISTENER)
                    report(on, i, data.readInt() != 0)
                    return true
                }
            }.apply { attachInterface(null, LISTENER) }
            val listener = Class.forName("$LISTENER\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
            register.invoke(manager, id, listener)
        }
        System.out.println("ready")
        System.out.flush()
        Thread {
            while (System.`in`.read() >= 0) Unit
            System.exit(0)
        }.start()
        Looper.loop()
    }

    @Synchronized
    private fun report(on: BooleanArray, index: Int, vibrating: Boolean) {
        val before = on.any { it }
        on[index] = vibrating
        val after = on.any { it }
        if (before != after) {
            System.out.println(if (after) "1" else "0")
            System.out.flush()
        }
    }
}
