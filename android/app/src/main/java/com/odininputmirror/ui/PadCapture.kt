package com.odininputmirror.ui

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.odininputmirror.domain.model.ControlRef
import kotlin.math.abs

// Capturing a press in the app, for when the mirror is off and the daemon is not there to listen.
// The pad is then not grabbed, so the Activity sees it like any other input device. Codes are the
// raw evdev ones (KeyEvent.scanCode, and axes mapped back through the device's key layout), which is
// what the daemon reports from the same node, so a binding made either way means the same thing.

/**
 * Whether an input device is one whose presses should be bound: a real game controller that is not
 * the handheld's own pad. The handheld's pad is what drives the editor (B skips, X stops), so taking
 * its presses would make the overlay impossible to leave.
 */
internal fun isCaptureSource(name: String?, isVirtual: Boolean, isGamepad: Boolean, internalPadName: String?): Boolean =
    isGamepad && !isVirtual && name != null && !name.equals(internalPadName, ignoreCase = true)

/** A key press as a binding source, or null for anything that is not the first press of a button. */
internal fun buttonSource(scanCode: Int, isDown: Boolean, repeatCount: Int): ControlRef? =
    if (isDown && repeatCount == 0 && scanCode > 0) ControlRef.button(scanCode) else null

/** Generic.kl's axis lines: what Android calls each standard evdev axis when a pad has no own layout. */
private val GENERIC_AXES = mapOf(
    0x00 to "X", 0x01 to "Y", 0x02 to "Z", 0x03 to "RX", 0x04 to "RY", 0x05 to "RZ",
    0x09 to "GAS", 0x0a to "BRAKE", 0x10 to "HAT_X", 0x11 to "HAT_Y",
)

/** The evdev code behind an Android axis name (`X`, `HAT_Y`, `LTRIGGER`…), per the device's layout. */
internal fun evdevAxis(androidName: String, layout: Map<Int, String>): Int? =
    (layout.entries.firstOrNull { it.value == androidName } ?: GENERIC_AXES.entries.firstOrNull { it.value == androidName })?.key

/**
 * Turns axis values into one report per push, like the daemon's axis capture: an axis past half its
 * travel reports that half once, and has to come back near rest before it can report again. The
 * gap between the two thresholds keeps a stick resting on the line from reporting twice.
 */
internal class AxisEdges {
    private val pushed = mutableMapOf<Int, Int>()

    fun feed(code: Int, value: Float): ControlRef? {
        val was = pushed[code] ?: 0
        val now = when {
            value >= PUSH -> 1
            value <= -PUSH -> -1
            abs(value) < RELEASE -> 0
            else -> was
        }
        pushed[code] = now
        return if (now != 0 && now != was) ControlRef.half(code, now) else null
    }

    private companion object {
        const val PUSH = 0.5f
        const val RELEASE = 0.25f
    }
}

/**
 * While [active], route the external pad's input to [onCaptured] instead of the screen. Events from
 * any other device go on as normal.
 */
@Composable
internal fun PadCaptureEffect(
    active: Boolean,
    internalPadName: String?,
    axisLabels: (vendorId: Int, productId: Int) -> Map<Int, String>,
    onCaptured: (ControlRef) -> Unit,
) {
    val activity = LocalContext.current.findMainActivity()
    val capture by rememberUpdatedState(onCaptured)
    DisposableEffect(activity, active, internalPadName) {
        if (activity == null || !active) return@DisposableEffect onDispose { }
        fun InputDevice?.isSource(event: android.view.InputEvent) = this != null && isCaptureSource(
            name, isVirtual,
            event.isFromSource(InputDevice.SOURCE_GAMEPAD) || event.isFromSource(InputDevice.SOURCE_JOYSTICK),
            internalPadName,
        )
        val edges = AxisEdges()
        // ponytail: layout read on the main thread, once per device per capture session; a few
        // hundred bytes of text, so not worth a background hop.
        val layouts = mutableMapOf<Int, Map<Int, String>>()
        activity.onPadCapture = { event: KeyEvent ->
            event.device.isSource(event).also { mine ->
                if (mine) buttonSource(event.scanCode, event.action == KeyEvent.ACTION_DOWN, event.repeatCount)?.let(capture)
            }
        }
        activity.onPadMotion = { event: MotionEvent ->
            val device = event.device
            device.isSource(event).also { mine ->
                if (!mine || device == null) return@also
                val layout = layouts.getOrPut(device.id) { axisLabels(device.vendorId, device.productId) }
                for (range in device.motionRanges) {
                    if (range.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) continue
                    val code = evdevAxis(MotionEvent.axisToString(range.axis).removePrefix("AXIS_"), layout) ?: continue
                    edges.feed(code, event.getAxisValue(range.axis))?.let(capture)
                }
            }
        }
        onDispose {
            activity.onPadCapture = null
            activity.onPadMotion = null
        }
    }
}
