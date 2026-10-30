package com.odininputmirror.ui

import android.view.InputDevice
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.odininputmirror.domain.model.ControllerDevice
import com.odininputmirror.domain.model.lockedPlayer1
import com.odininputmirror.ui.theme.Palette

/**
 * "Press L + R on the controller you want to use." The pad that does becomes player 1: the one the
 * mirror replays as the built-in controller whenever it is connected.
 *
 * The supervisor holds the mirror off while this is open, so every external pad reports as itself.
 * B leaves, X unlocks (back to "first connected").
 */
@Composable
fun Player1Screen(
    externals: List<ControllerDevice>,
    player1Name: String?,
    player1Path: String?,
    onPick: (InputDevice) -> Unit,
    onUnlock: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)

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

    // Locked pad first so it sits in the P1 tile, then the rest; four tiles like the Switch screen.
    val locked = externals.lockedPlayer1(player1Name, player1Path)
    val ordered = externals.sortedByDescending { it == locked }
    val glyphs = promptFontFamily()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.screen)
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyUp && it.key == Key.ButtonX) {
                    onUnlock()
                    true
                } else {
                    false
                }
            },
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().background(Palette.band)
                .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 28.dp, vertical = 18.dp),
        ) {
            Text("Player 1 controller", color = Palette.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 40.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Press ", color = Palette.textPrimary, fontSize = 24.sp)
                ButtonChip(PromptGlyph.LEFT_BUMPER, glyphs)
                Text("  +  ", color = Palette.textPrimary, fontSize = 24.sp)
                ButtonChip(PromptGlyph.RIGHT_BUMPER, glyphs)
                Text(" on the controller you want to use", color = Palette.textPrimary, fontSize = 24.sp)
            }
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                repeat(4) { slot ->
                    PlayerTile(
                        slot = slot,
                        device = ordered.getOrNull(slot),
                        locked = locked != null && ordered.getOrNull(slot) == locked,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                if (player1Name == null) {
                    "No lock yet: the first connected controller plays as player 1."
                } else {
                    "Locked: $player1Name plays as player 1 whenever it's connected."
                },
                color = Palette.textSecondary,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
            )
        }

        HintBar(
            hints = listOf(
                HintAction(PromptGlyph.X, "Unlock", onUnlock),
                HintAction(PromptGlyph.B, "Done", onClose),
            ),
        )
    }
}

@Composable
private fun PlayerTile(slot: Int, device: ControllerDevice?, locked: Boolean, modifier: Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(if (device != null) Palette.surfaceRaised else Palette.screen)
            .border(if (locked) 3.dp else 1.dp, if (locked) Palette.accent else Palette.border, shape)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // The four player lamps, this tile's one lit.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(4) { lamp ->
                Box(
                    Modifier.size(10.dp).background(
                        if (lamp == slot && device != null) Palette.accent else Palette.borderStrong,
                    ),
                )
            }
        }
        if (device == null) {
            Text("${slot + 1}", color = Palette.textMuted, fontSize = 40.sp)
        } else {
            Text("🎮", fontSize = 44.sp)
        }
        Text(
            device?.name ?: "",
            color = if (locked) Palette.accent else Palette.textSecondary,
            fontSize = 13.sp,
            fontWeight = if (locked) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ButtonChip(glyph: String, glyphs: androidx.compose.ui.text.font.FontFamily) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Palette.accent).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = Palette.band, fontFamily = glyphs, fontSize = 30.sp)
    }
}

internal data class HintAction(val glyph: String, val label: String, val onClick: (() -> Unit)?)

/** The Switch-style strip along the bottom: which pad button does what. Tappable too. */
@Composable
internal fun HintBar(hints: List<HintAction>) {
    val glyphs = promptFontFamily()
    Row(
        modifier = Modifier.fillMaxWidth().background(Palette.band).padding(horizontal = 28.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        hints.forEach { hint ->
            Row(
                modifier = Modifier.let { m -> hint.onClick?.let { m.clickable(onClick = it) } ?: m }.padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(hint.glyph, color = Palette.textPrimary, fontFamily = glyphs, fontSize = 24.sp)
                Spacer(Modifier.size(8.dp))
                Text(hint.label, color = Palette.textPrimary, fontSize = 16.sp)
            }
        }
    }
}
