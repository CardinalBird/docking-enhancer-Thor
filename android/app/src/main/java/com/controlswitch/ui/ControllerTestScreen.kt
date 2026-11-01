package com.controlswitch.ui

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.controlswitch.MirrorStateStore
import com.controlswitch.domain.model.PadLayout
import com.controlswitch.domain.model.SlotIcon
import com.controlswitch.ui.theme.Palette
import com.controlswitch.ui.theme.Teal
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Controller Tests: pick a pad, then press everything and watch the drawing light up while the
 * checklist ticks itself off. The mirror is held off for as long as this page is open, so the pad
 * under test reports as itself rather than as the built-in one. Two steps: on the picker the pad
 * drives the page as usual; while testing, every event from the chosen pad is taken by the page
 * (its B included, which is why holding the button printed B is the way out).
 */
@Composable
internal fun ControllerTestScreen(state: MirrorUiState, active: Boolean, onBack: () -> Unit) {
    LifecycleStartEffect(Unit) {
        MirrorStateStore.setTestingPad(true)
        onStopOrDispose { MirrorStateStore.setTestingPad(false) }
    }

    val internalName = state.localDevice?.name
    // Re-read when the supervisor sees pads come or go, and when the mirror stops and the external
    // pad reappears to Android.
    val pads = remember(state.devices, state.enabled) { connectedPads(internalName) }
    var padId by remember { mutableIntStateOf(-1) }
    val pad = pads.firstOrNull { it.id == padId } ?: pads.firstOrNull()
    var testingRaw by remember { mutableStateOf(false) }
    // A pad that drops out mid-test ends the test rather than lighting a ghost.
    val testing = testingRaw && pad != null && pad.id == padId
    var test by remember { mutableStateOf(PadTest(emptyList())) }
    val builtIn = pad != null && pad.name.equals(internalName, ignoreCase = true)
    val layout = if (builtIn) PadLayout.NINTENDO else state.padLayout
    // The mirror is still being stood down: until it is, an external pad's presses arrive as the
    // built-in one, so there is nothing to test yet.
    val ready = !state.enabled && pad != null

    BackHandler(enabled = active) { if (testing) testingRaw = false else onBack() }

    fun start() {
        val chosen = pad ?: return
        if (!ready) return
        val axes = padAxes { chosen.getMotionRange(it) != null }
        test = PadTest(
            items = padItems(hasKey = { chosen.hasKeys(it)[0] }, hasAxis = { chosen.getMotionRange(it) != null }, axes = axes, builtIn = builtIn),
        )
        padId = chosen.id
        testingRaw = true
    }

    val activity = LocalContext.current.findMainActivity()
    DisposableEffect(activity, testing, pad?.id) {
        if (activity == null || !testing || pad == null) return@DisposableEffect onDispose { }
        val axes = padAxes { pad.getMotionRange(it) != null }
        val hasHat = pad.getMotionRange(MotionEvent.AXIS_HAT_X) != null
        // 0 at rest, 1 fully pulled, whatever the axis range: a trigger that reports -1..1 rests at -1.
        fun pull(axis: Int, value: Float): Float {
            val range = pad.getMotionRange(axis) ?: return 0f
            return if (range.range > 0f) ((value - range.min) / range.range).coerceIn(0f, 1f) else 0f
        }
        activity.onPadCapture = { event: KeyEvent ->
            (event.deviceId == pad.id).also { mine ->
                if (mine && event.repeatCount == 0) {
                    PAD_KEYS[event.keyCode]?.let { test = test.button(it, event.action == KeyEvent.ACTION_DOWN) }
                }
            }
        }
        activity.onPadMotion = { event: MotionEvent ->
            (event.deviceId == pad.id && event.isFromSource(InputDevice.SOURCE_JOYSTICK)).also { mine ->
                if (mine) {
                    var next = test
                        .stick(right = false, event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y))
                        .stick(right = true, event.getAxisValue(axes.rightX), event.getAxisValue(axes.rightY))
                    axes.leftTrigger?.let { next = next.trigger(right = false, pull(it, event.getAxisValue(it))) }
                    axes.rightTrigger?.let { next = next.trigger(right = true, pull(it, event.getAxisValue(it))) }
                    if (hasHat) next = next.hat(event.getAxisValue(MotionEvent.AXIS_HAT_X), event.getAxisValue(MotionEvent.AXIS_HAT_Y))
                    test = next
                }
            }
        }
        onDispose {
            activity.onPadCapture = null
            activity.onPadMotion = null
        }
    }

    // Holding the button printed B fills the ring and then ends the test; letting go earlier was a
    // test press like any other. The animation is the timer.
    val exit = exitIcon(layout, builtIn)
    val hold = remember { Animatable(0f) }
    val holding = testing && exit in test.down
    LaunchedEffect(holding) {
        hold.snapTo(0f)
        if (!holding) return@LaunchedEffect
        hold.animateTo(1f, tween(HOLD_MS, easing = LinearEasing))
        testingRaw = false
    }

    // Same anchoring as Additional Settings, redone when the test ends and the row is back in charge.
    var hasFocus by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val inputModeManager = LocalInputModeManager.current
    LaunchedEffect(active, testing) {
        if (!active || testing) return@LaunchedEffect
        repeat(24) {
            if (hasFocus) return@LaunchedEffect
            inputModeManager.requestInputMode(InputMode.Keyboard)
            runCatching { first.requestFocus() }
            delay(50)
        }
    }

    val cycle = { by: Int ->
        if (pads.isNotEmpty() && pad != null) padId = pads[(pads.indexOf(pad) + by + pads.size) % pads.size].id
    }
    CompositionLocalProvider(LocalPadLayout provides layout) {
        Column(
            Modifier.fillMaxSize().onFocusChanged { hasFocus = it.hasFocus }.windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Header(title = "Controller Tests")
            Rule(Modifier.padding(horizontal = 28.dp))
            Row(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 40.dp, vertical = 2.dp)) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    MenuRow(
                        title = "Controller",
                        minHeight = 52.dp,
                        onClick = { start() },
                        onLeft = if (testing) null else ({ cycle(-1) }),
                        onRight = if (testing) null else ({ cycle(1) }),
                        modifier = Modifier.focusRequester(first),
                    ) {
                        RowValue(
                            when {
                                pad == null -> "No controller connected"
                                !ready -> "Pausing mirroring…"
                                testing -> padLabel(pad, pads, internalName)
                                else -> "‹  ${padLabel(pad, pads, internalName)}  ›"
                            },
                            accent = ready && !testing,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    PadDiagram(test, layout, builtIn, dim = !testing)
                }
                Spacer(Modifier.width(32.dp))
                Checklist(test, layout, builtIn, Modifier.width(300.dp).padding(top = 8.dp))
            }
            if (testing) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Press every button, pull both triggers and turn each stick in a full circle.",
                        color = Palette.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 28.dp).weight(1f),
                    )
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { HoldHint(hold.value) }
                }
            } else {
                Footer(
                    status = if (ready) "Choose a controller, then press A." else "Waiting for the mirror to pause.",
                    hints = listOf(HintAction(SlotIcon.B, "Back", onBack), HintAction(SlotIcon.A, "Start", null)),
                )
            }
        }
    }
}

private const val HOLD_MS = 1500

/** The real game controllers Android lists right now, the handheld's own first. */
private fun connectedPads(internalName: String?): List<InputDevice> =
    InputDevice.getDeviceIds().toList().mapNotNull(InputDevice::getDevice)
        .filter { !it.isVirtual && (it.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD || it.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK) }
        .sortedBy { !it.name.equals(internalName, ignoreCase = true) }

private fun padLabel(pad: InputDevice, pads: List<InputDevice>, internalName: String?): String = when {
    pad.name.equals(internalName, ignoreCase = true) -> "This handheld"
    // Two pads of one model share a name; the player number tells them apart.
    pads.count { it.name == pad.name } > 1 -> "${pad.name} (P${pad.controllerNumber})"
    else -> pad.name
}

/** The B hint with a ring that fills while the button is held. */
@Composable
private fun HoldHint(progress: Float) {
    Row(Modifier.padding(horizontal = 28.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp)) {
            PadIcon(SlotIcon.B, 30.dp)
            Canvas(Modifier.fillMaxSize()) {
                drawArc(Teal, startAngle = -90f, sweepAngle = 360f * progress, useCenter = false, style = Stroke(3.dp.toPx()))
            }
        }
        Spacer(Modifier.width(8.dp))
        Text("Hold to stop", color = Palette.textPrimary, fontSize = 15.sp)
    }
}

/** "N/N passed" and one line per control, two columns. */
@Composable
private fun Checklist(test: PadTest, layout: PadLayout, builtIn: Boolean, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            if (test.items.isEmpty()) "Not started" else "${test.passedCount}/${test.items.size} passed",
            color = Palette.textPrimary, fontSize = 17.sp, modifier = Modifier.padding(bottom = 6.dp),
        )
        Rule()
        for (pair in test.items.chunked(2)) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                for (icon in pair) {
                    val passed = test.passed(icon)
                    Row(Modifier.weight(1f).height(26.dp), verticalAlignment = Alignment.CenterVertically) {
                        PadIcon(faceLabel(icon, layout, builtIn), 20.dp, tint = if (test.lit(icon)) Teal else Palette.textPrimary)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            itemLabel(icon, layout, builtIn), color = Palette.textSecondary, fontSize = 13.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        val stick = when (icon) {
                            SlotIcon.LEFT_STICK_UP -> test.left
                            SlotIcon.RIGHT_STICK_UP -> test.right
                            else -> null
                        }
                        Text(
                            if (passed) "✓" else if (stick != null) "${stick.reached}/8" else "·",
                            color = if (passed) Palette.custom else Palette.textMuted, fontSize = 13.sp,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

// The drawing: a Switch Pro-shaped pad in a 360 x 200 dp box. Shapes are drawn on a Canvas in that
// coordinate space (one unit = one dp), and the button prompts sit on top as PadIcons.
private const val W = 360f
private const val H = 200f

private data class Spot(val x: Float, val y: Float)

@Composable
private fun PadDiagram(test: PadTest, layout: PadLayout, builtIn: Boolean, dim: Boolean) {
    val nintendo = layout == PadLayout.NINTENDO
    // Faces by printed letter: Nintendo puts A east and X north, Xbox A south and X west.
    fun at(label: SlotIcon) = when (label) {
        SlotIcon.A -> if (nintendo) EAST else SOUTH
        SlotIcon.B -> if (nintendo) SOUTH else EAST
        SlotIcon.X -> if (nintendo) NORTH else WEST
        else -> if (nintendo) WEST else NORTH
    }
    val faces = listOf(SlotIcon.A, SlotIcon.B, SlotIcon.X, SlotIcon.Y).map { it to at(faceLabel(it, layout, builtIn)) }
    val idle = Palette.borderStrong
    val body = Palette.surfaceRaised
    val ink = Palette.textPrimary
    val good = Palette.custom
    val tint = if (dim) Palette.textMuted else ink
    val showHome = SlotIcon.HOME in test.items || (test.items.isEmpty() && !builtIn)

    Box(Modifier.size(W.dp, H.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val u = size.width / W
            fun fill(icon: SlotIcon) = if (test.lit(icon)) Teal else idle
            fun DrawScope.passRing(icon: SlotIcon, center: Spot, radius: Float) {
                if (test.passed(icon)) drawCircle(good, radius * u, Offset(center.x * u, center.y * u), style = Stroke(2f * u))
            }
            // Body and grips.
            drawRoundRect(body, Offset(40f * u, 42f * u), Size(280f * u, 88f * u), CornerRadius(40f * u))
            drawOval(body, Offset(74f * u, 100f * u), Size(52f * u, 70f * u))
            drawOval(body, Offset(234f * u, 100f * u), Size(52f * u, 70f * u))
            // Triggers: the bar fills with the pull. Bumpers: lit while held.
            for ((icon, x, value) in listOf(
                Triple(SlotIcon.LEFT_TRIGGER, 52f, test.leftTrigger), Triple(SlotIcon.RIGHT_TRIGGER, 228f, test.rightTrigger),
            )) {
                drawRoundRect(idle, Offset(x * u, 2f * u), Size(80f * u, 18f * u), CornerRadius(9f * u))
                if (value > 0f) drawRoundRect(Teal, Offset(x * u, 2f * u), Size(80f * value * u, 18f * u), CornerRadius(9f * u))
                if (test.passed(icon)) drawRoundRect(good, Offset(x * u, 2f * u), Size(80f * u, 18f * u), CornerRadius(9f * u), style = Stroke(2f * u))
            }
            for ((icon, x) in listOf(SlotIcon.LEFT_BUMPER to 48f, SlotIcon.RIGHT_BUMPER to 220f)) {
                drawRoundRect(fill(icon), Offset(x * u, 23f * u), Size(92f * u, 16f * u), CornerRadius(8f * u))
                if (test.passed(icon)) drawRoundRect(good, Offset(x * u, 23f * u), Size(92f * u, 16f * u), CornerRadius(8f * u), style = Stroke(2f * u))
            }
            // Sticks: eight arc segments go green as the stick reaches them, the dot follows the stick.
            for ((stick, center, click) in listOf(
                Triple(test.left, LEFT_STICK, SlotIcon.LEFT_STICK_CLICK), Triple(test.right, RIGHT_STICK, SlotIcon.RIGHT_STICK_CLICK),
            )) {
                val c = Offset(center.x * u, center.y * u)
                for (i in 0 until 8) {
                    val hit = stick.octants and (1 shl i) != 0
                    drawArc(
                        if (hit) good else idle, startAngle = i * 45f - 20f, sweepAngle = 40f, useCenter = false,
                        topLeft = c - Offset(28f * u, 28f * u), size = Size(56f * u, 56f * u), style = Stroke(3f * u),
                    )
                }
                drawCircle(if (test.lit(click)) Teal else idle, 22f * u, c, style = Stroke(2f * u))
                drawCircle(if (test.lit(click)) Teal else tint, 9f * u, c + Offset(stick.x * 16f * u, stick.y * 16f * u))
                passRing(click, center, 12f)
            }
            // D-pad.
            for ((icon, dx, dy) in listOf(
                Triple(SlotIcon.DPAD_UP, 0f, -17f), Triple(SlotIcon.DPAD_DOWN, 0f, 17f),
                Triple(SlotIcon.DPAD_LEFT, -17f, 0f), Triple(SlotIcon.DPAD_RIGHT, 17f, 0f),
            )) {
                val topLeft = Offset((DPAD.x + dx - 7.5f) * u, (DPAD.y + dy - 7.5f) * u)
                drawRoundRect(fill(icon), topLeft, Size(15f * u, 15f * u), CornerRadius(3f * u))
                if (test.passed(icon)) drawRoundRect(good, topLeft, Size(15f * u, 15f * u), CornerRadius(3f * u), style = Stroke(2f * u))
            }
            // Face halos, −/+, Home: the prompts sit on top.
            for ((icon, spot) in faces) {
                drawCircle(fill(icon), 13f * u, Offset(spot.x * u, spot.y * u))
                passRing(icon, spot, 15f)
            }
            for ((icon, spot) in listOf(SlotIcon.VIEW to MINUS, SlotIcon.MENU to PLUS)) {
                drawCircle(fill(icon), 10f * u, Offset(spot.x * u, spot.y * u))
                passRing(icon, spot, 12f)
            }
            if (showHome) {
                drawCircle(fill(SlotIcon.HOME), 10f * u, Offset(HOME.x * u, HOME.y * u))
                passRing(SlotIcon.HOME, HOME, 12f)
            }
        }
        for ((icon, spot) in faces) Prompt(faceLabel(icon, layout, builtIn), spot, 22.dp, tint)
        Prompt(SlotIcon.LEFT_TRIGGER, Spot(92f, 11f), 18.dp, tint)
        Prompt(SlotIcon.RIGHT_TRIGGER, Spot(268f, 11f), 18.dp, tint)
        Prompt(SlotIcon.LEFT_BUMPER, Spot(94f, 31f), 16.dp, tint)
        Prompt(SlotIcon.RIGHT_BUMPER, Spot(266f, 31f), 16.dp, tint)
        Prompt(SlotIcon.VIEW, MINUS, 16.dp, tint)
        Prompt(SlotIcon.MENU, PLUS, 16.dp, tint)
        if (showHome) Prompt(SlotIcon.HOME, HOME, 16.dp, tint)
        StickReadout(test.left, Spot(LEFT_STICK.x, READOUT_Y))
        StickReadout(test.right, Spot(RIGHT_STICK.x, READOUT_Y))
    }
}

private val SOUTH = Spot(255f, 100f)
private val EAST = Spot(277f, 78f)
private val NORTH = Spot(255f, 56f)
private val WEST = Spot(233f, 78f)
private val LEFT_STICK = Spot(105f, 78f)
private val RIGHT_STICK = Spot(220f, 132f)
private val DPAD = Spot(140f, 132f)
private val MINUS = Spot(152f, 56f)
private val PLUS = Spot(208f, 56f)
private val HOME = Spot(180f, 80f)
// Both stick readouts on one line under the grips, clear of the D-pad.
private const val READOUT_Y = 186f

/** A prompt icon centred on [spot] of the drawing. */
@Composable
private fun Prompt(icon: SlotIcon, spot: Spot, size: Dp, tint: Color) {
    Box(Modifier.offset(spot.x.dp - size / 2, spot.y.dp - size / 2)) { PadIcon(icon, size, tint) }
}

@Composable
private fun StickReadout(stick: StickState, spot: Spot) {
    Box(Modifier.offset(spot.x.dp - 40.dp, spot.y.dp - 6.dp).width(80.dp), contentAlignment = Alignment.Center) {
        Text(String.format(Locale.ROOT, "%+.2f  %+.2f", stick.x, stick.y), color = Palette.textMuted, fontSize = 10.sp, maxLines = 1)
    }
}
