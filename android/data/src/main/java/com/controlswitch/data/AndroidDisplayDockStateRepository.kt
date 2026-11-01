package com.controlswitch.data

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import com.controlswitch.domain.repository.DockStateRepository

internal class AndroidDisplayDockStateRepository(
    context: Context,
    private val forceDockMode: Boolean = false,
) : DockStateRepository {
    private val displayManager = context.applicationContext.getSystemService(DisplayManager::class.java)

    override fun isDockActive(): Boolean {
        if (forceDockMode) {
            return true
        }

        return displayManager.displays.any { display ->
            display.displayId != Display.DEFAULT_DISPLAY &&
                display.state != Display.STATE_OFF &&
                display.isDockOutput()
        }
    }
}

// The Thor's second screen is a real, powered-on display that is part of the handheld: the system
// reports it as TYPE_INTERNAL, where a dock's HDMI output is TYPE_EXTERNAL and a cast target is
// TYPE_WIFI. Only those two count as a dock: a screen recorder, a cast mirror or the developer
// "simulated display" (TYPE_VIRTUAL/TYPE_OVERLAY) used to start the mirror and grab the external pad
// in the middle of handheld play. getType() is hidden API (allowed via reflection on Android 13); if
// it ever isn't reachable, fall back to treating every secondary display as a dock, which is what
// this did before, and say so once so the symptom is traceable.
private const val TYPE_EXTERNAL = 2
private const val TYPE_WIFI = 3

private val getDisplayType: java.lang.reflect.Method? =
    runCatching { Display::class.java.getMethod("getType") }
        .onFailure { Log.w("DockState", "Display.getType unreachable; every secondary display counts as a dock", it) }
        .getOrNull()

private fun Display.isDockOutput(): Boolean {
    val method = getDisplayType ?: return true
    val type = runCatching { method.invoke(this) as? Int }.getOrNull() ?: return true
    return type == TYPE_EXTERNAL || type == TYPE_WIFI
}
