package com.controlswitch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class InputMirrorBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // An update kills the supervisor but not the daemon it started, which then runs unwatched
        // until the app is next opened; restarting here puts it back under supervision.
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED && intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        // No PServerBinder probe here: onReceive runs on the main thread, the probe blocks on a
        // binder transact, and a receiver that overruns its window is killed. The supervisor checks
        // on its own worker and stops itself when the service is missing.
        runCatching {
            val serviceIntent = Intent(context, InputMirrorSupervisorService::class.java)
            context.startForegroundService(serviceIntent)
        }.onFailure { Log.w("InputMirrorBootReceiver", "Could not start supervisor on boot", it) }
    }
}
