package com.odininputmirror.data

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import com.odininputmirror.domain.repository.DockStateRepository

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
                !display.isBuiltIn()
        }
    }
}

// The Thor's second screen is a real, powered-on display that is part of the handheld: the system
// reports it as TYPE_INTERNAL, where a dock's HDMI output is TYPE_EXTERNAL. getType() is hidden API
// (allowed for apps via reflection on Android 13); if it ever isn't reachable, fall back to treating
// every secondary display as a dock, which is what this did before.
private const val TYPE_INTERNAL = 1

private fun Display.isBuiltIn(): Boolean =
    runCatching { Display::class.java.getMethod("getType").invoke(this) == TYPE_INTERNAL }.getOrDefault(false)
