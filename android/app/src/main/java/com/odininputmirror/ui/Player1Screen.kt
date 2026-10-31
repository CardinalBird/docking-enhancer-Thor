package com.odininputmirror.ui

import com.odininputmirror.domain.model.SlotIcon
import android.view.InputDevice
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.odininputmirror.R
import com.odininputmirror.domain.model.ControllerDevice
import com.odininputmirror.domain.model.lockedPlayer1
import com.odininputmirror.ui.theme.Palette
import kotlinx.coroutines.delay

/**
 * Change Input Order: "Press L + R on the controller you'd like to use." Slot 1 stays empty until a
 * pad does; that pad is staged ([pendingName]), and A on any pad saves it as player 1 — the one the
 * mirror replays as the built-in controller whenever it is connected. B leaves without saving.
 *
 * The supervisor holds the mirror off while this is open, so every pad reports as itself and its A
 * reaches this screen through normal key dispatch.
 */
@Composable
fun Player1Screen(
    externals: List<ControllerDevice>,
    player1Name: String?,
    pendingName: String?,
    pendingPath: String?,
    /** False while this page slides out: Back then belongs to the page arriving. */
    active: Boolean,
    onPick: (InputDevice) -> Unit,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(enabled = active, onBack = onClose)

    val activity = LocalContext.current.findMainActivity()
    DisposableEffect(activity) {
        activity?.onLPlusR = onPick
        onDispose { activity?.onLPlusR = null }
    }
    // Leaving the app mid-pick must not leave the mirror held off until someone comes back.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) onClose() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val pending = remember(externals, pendingName, pendingPath) { externals.lockedPlayer1(pendingName, pendingPath) }
    val pendingBattery = remember(pending) { padBattery(pending) }

    // Nothing on this screen is focusable, and Compose routes keys to the focused node's ancestors
    // only, so A never arrived. The screen itself is the focus target. Both halves of A are taken
    // and the confirm waits for the release: the A that opened this page lifts after it appears.
    val anchor = remember { FocusRequester() }
    val inputModeManager = LocalInputModeManager.current
    // Retried: the node is not necessarily placed on the first frame, and without focus here A
    // (confirm) never arrives.
    LaunchedEffect(Unit) {
        repeat(10) {
            inputModeManager.requestInputMode(InputMode.Keyboard)
            if (runCatching { anchor.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(50)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .focusRequester(anchor)
            .onPreviewKeyEvent {
                if (it.key != Key.ButtonA) return@onPreviewKeyEvent false
                if (it.type == KeyEventType.KeyUp && pendingName != null) onConfirm()
                true
            }
            .focusable(),
    ) {
        Header(title = "Change Input Order")
        Rule(Modifier.padding(horizontal = 28.dp))

        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 56.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Press  ", color = Palette.textPrimary, fontSize = 19.sp)
                PadIcon(SlotIcon.LEFT_BUMPER, 34.dp)
                Text("  +  ", color = Palette.textPrimary, fontSize = 19.sp)
                PadIcon(SlotIcon.RIGHT_BUMPER, 34.dp)
                Text("  on the controller you'd like to use.", color = Palette.textPrimary, fontSize = 19.sp)
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                PlayerOneSlot(pendingName, pendingBattery, Modifier.weight(1f))
                for (slot in 2..4) {
                    Slot(filled = false, Modifier.weight(1f)) {
                        Text("$slot", color = Palette.textMuted, fontSize = 34.sp)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            if (pendingName != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Press  ", color = Palette.textPrimary, fontSize = 17.sp)
                    PadIcon(SlotIcon.A, 32.dp)
                    Text("  when you're ready.", color = Palette.textPrimary, fontSize = 17.sp)
                }
            } else {
                Text(
                    player1Name?.let { "Player 1 is $it." } ?: "No controller is player 1 yet.",
                    color = Palette.textMuted,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }

        HintBar(
            hints = listOfNotNull(
                HintAction(SlotIcon.B, "Back", onClose),
                if (pendingName != null) HintAction(SlotIcon.A, "OK", onConfirm) else null,
            ),
        )
    }
}

/** Slot 1: empty until a pad presses L + R, then that pad scales and fades in, lamp lit. */
@Composable
private fun PlayerOneSlot(name: String?, battery: Int?, modifier: Modifier) {
    Slot(filled = name != null, modifier) {
        AnimatedContent(
            targetState = name,
            transitionSpec = {
                (scaleIn(tween(300), initialScale = 0.6f) + fadeIn(tween(300))) togetherWith fadeOut(tween(150))
            },
            label = "player1",
        ) { shown ->
            if (shown == null) {
                Text("1", color = Palette.textMuted, fontSize = 34.sp)
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PlayerLamps(lit = true)
                    Image(painterResource(R.drawable.ic_controller), contentDescription = null, modifier = Modifier.size(width = 60.dp, height = 46.dp))
                    battery?.let { Battery(it) }
                    Text(shown, color = Palette.textPrimary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun Slot(filled: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .height(160.dp)
            .clip(shape)
            .background(if (filled) Palette.surfaceRaised else Palette.screen)
            .let { if (filled) it.focusOutline(true, shape) else it.border(1.dp, Palette.borderStrong, shape) }
            .padding(10.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

internal data class HintAction(val icon: SlotIcon, val label: String, val onClick: (() -> Unit)?)

/** The Switch-style hints along the bottom, on the page: which pad button does what. Tappable too. */
@Composable
internal fun HintBar(hints: List<HintAction>) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        hints.forEach { hint ->
            Row(
                // Touch only: a controller has the real buttons, and a focusable hint pulled the D-pad off the list.
                modifier = Modifier.focusProperties { canFocus = false }
                    .let { m -> hint.onClick?.let { m.clickable(onClick = it) } ?: m }.padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PadIcon(hint.icon, 30.dp)
                Spacer(Modifier.size(8.dp))
                Text(hint.label, color = Palette.textPrimary, fontSize = 15.sp)
            }
        }
    }
}
