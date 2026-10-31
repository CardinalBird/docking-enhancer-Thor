package com.odininputmirror.ui

import androidx.compose.foundation.background
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.odininputmirror.domain.model.ControlRef
import com.odininputmirror.domain.model.MappingSlot
import com.odininputmirror.domain.model.SlotIcon
import com.odininputmirror.domain.model.describeSource
import com.odininputmirror.ui.theme.Palette
import kotlinx.coroutines.delay

// The editor's repeated pieces: one row per slot, the glyph that names it, the overlay that waits
// for a press, and the button style both of them use.

/**
 * One slot: what it is, and what it currently reads.
 *
 * Focusable on purpose. The external controller is busy answering the capture, but the handheld's own
 * pad is not — it is the mirror target, and while this screen is open nothing is being forwarded to
 * it, so its buttons reach the app normally. Without a visible focus ring there is no way to tell
 * where the D-pad is.
 */
@Composable
internal fun SlotRow(
    slot: MappingSlot,
    source: ControlRef?,
    onBind: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // The row itself is NOT focusable: it is a plain container holding two focus targets side by side.
    // Making the whole row clickable is what put Clear inside an already-focusable node, where the
    // D-pad could never reach it — focus stopped at the parent and the child was never a stop of its
    // own.
    // One focus target per row; X clears it (the hint bar says so), replacing a Clear button per row.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Palette.focusBg else Palette.screen)
            .border(if (focused) 3.dp else 0.dp, if (focused) Palette.focus else Palette.screen, RoundedCornerShape(8.dp))
            .onPreviewKeyEvent {
                // Both halves, even on an unbound row: Android turns an unhandled X press into a
                // select, which opened a capture on the row instead of doing nothing.
                if (it.key != Key.ButtonX) return@onPreviewKeyEvent false
                if (it.type == KeyEventType.KeyUp && source != null) onClear()
                true
            }
            .clickable(interactionSource = interaction, indication = null) { onBind() }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SlotGlyph(slot)
        Spacer(Modifier.width(14.dp))
        Text(slot.label, color = Palette.textPrimary, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text(
            source?.let(::describeSource) ?: "Not bound",
            color = if (source == null) Palette.textMuted else Palette.accent,
            fontSize = 14.sp,
        )
    }
    Rule(Modifier.padding(horizontal = 8.dp))
}

/** A group in the left pane. Focusing it is selecting it. */
@Composable
internal fun GroupRow(group: String, selected: Boolean, onFocus: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // Immediate, so a quick Down then Right lands in the group passed through; the screen ignores the
    // passing focus the overlay's closing causes.
    LaunchedEffect(focused) {
        if (focused) onFocus()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Palette.focusBg else Palette.screen)
            .border(if (focused) 3.dp else 0.dp, if (focused) Palette.focus else Palette.screen, RoundedCornerShape(8.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onFocus)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The Switch's selection bar: a stripe beside the group whose rows are showing.
        Box(Modifier.width(4.dp).height(26.dp).background(if (selected) Palette.accent else Palette.screen))
        Spacer(Modifier.width(12.dp))
        Text(group, color = if (selected) Palette.textPrimary else Palette.textSecondary, fontSize = 17.sp)
    }
}

/**
 * The button as it is marked on the pad.
 *
 * Drawn rather than shipped as artwork: four coloured letters, four short labels and a handful of
 * arrows is less code than an asset pipeline, and it scales with the text size for free.
 */
@Composable
internal fun SlotGlyph(slot: MappingSlot) {
    val glyph = when (slot.icon) {
        SlotIcon.A -> PromptGlyph.A
        SlotIcon.B -> PromptGlyph.B
        SlotIcon.X -> PromptGlyph.X
        SlotIcon.Y -> PromptGlyph.Y
        SlotIcon.LEFT_BUMPER -> PromptGlyph.LEFT_BUMPER
        SlotIcon.RIGHT_BUMPER -> PromptGlyph.RIGHT_BUMPER
        SlotIcon.LEFT_TRIGGER -> PromptGlyph.LEFT_TRIGGER
        SlotIcon.RIGHT_TRIGGER -> PromptGlyph.RIGHT_TRIGGER
        SlotIcon.DPAD_UP -> PromptGlyph.DPAD_UP
        SlotIcon.DPAD_DOWN -> PromptGlyph.DPAD_DOWN
        SlotIcon.DPAD_LEFT -> PromptGlyph.DPAD_LEFT
        SlotIcon.DPAD_RIGHT -> PromptGlyph.DPAD_RIGHT
        SlotIcon.LEFT_STICK_UP -> PromptGlyph.LEFT_STICK_UP
        SlotIcon.LEFT_STICK_DOWN -> PromptGlyph.LEFT_STICK_DOWN
        SlotIcon.LEFT_STICK_LEFT -> PromptGlyph.LEFT_STICK_LEFT
        SlotIcon.LEFT_STICK_RIGHT -> PromptGlyph.LEFT_STICK_RIGHT
        SlotIcon.LEFT_STICK_CLICK -> PromptGlyph.LEFT_STICK_CLICK
        SlotIcon.RIGHT_STICK_UP -> PromptGlyph.RIGHT_STICK_UP
        SlotIcon.RIGHT_STICK_DOWN -> PromptGlyph.RIGHT_STICK_DOWN
        SlotIcon.RIGHT_STICK_LEFT -> PromptGlyph.RIGHT_STICK_LEFT
        SlotIcon.RIGHT_STICK_RIGHT -> PromptGlyph.RIGHT_STICK_RIGHT
        SlotIcon.RIGHT_STICK_CLICK -> PromptGlyph.RIGHT_STICK_CLICK
        SlotIcon.VIEW -> PromptGlyph.VIEW
        SlotIcon.MENU -> PromptGlyph.MENU
        SlotIcon.HOME -> "⌂"
        SlotIcon.BACK -> "↩"
    }
    // Plain characters, not PromptFont remaps: the font would turn these into unrelated arrows.
    val plainText = slot.icon == SlotIcon.HOME || slot.icon == SlotIcon.BACK
    // The face buttons keep the colours they are printed in — on an Xbox pad "the green one" is as
    // much the button's name as the letter is. Everything else is one ink, because it is on the pad.
    val colour = when (slot.icon) {
        SlotIcon.A -> Color(0xFF6CC24A)
        SlotIcon.B -> Color(0xFFE5484D)
        SlotIcon.X -> Color(0xFF3E7BFA)
        SlotIcon.Y -> Color(0xFFF2C037)
        else -> Palette.textSecondary
    }
    Box(modifier = Modifier.size(30.dp), contentAlignment = Alignment.Center) {
        Text(glyph, color = colour, fontFamily = if (plainText) null else promptFontFamily(), fontSize = 26.sp)
    }
}

/**
 * The capture prompt. Sits over the list so the slot it belongs to stays visible behind it.
 *
 * Cancel is the only action on it, deliberately. Anything else here is a button the user cannot reach:
 * the first control they press to navigate towards it is the control that gets captured, which binds
 * the slot and closes the prompt. Clearing lives on the row, where nothing is listening.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun CaptureOverlay(
    slot: MappingSlot,
    source: ControlRef?,
    notice: String?,
    onCancel: () -> Unit,
) {
    // Focus has to come here, and not just for looks: the handheld's pad is still driving the list
    // behind, so without taking focus a press meant for the overlay would open a different slot.
    val cancelFocus = remember { FocusRequester() }
    val inputModeManager = LocalInputModeManager.current
    LaunchedEffect(slot) {
        repeat(10) {
            inputModeManager.requestInputMode(InputMode.Keyboard)
            if (runCatching { cancelFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(50)
        }
    }

    // A trap on purpose: focus that wandered to a row behind and pressed A re-bound a different
    // slot, and so did a tap through the scrim.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.scrim)
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup()
                .widthIn(max = 460.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Palette.surface)
                .border(1.dp, Palette.border, RoundedCornerShape(16.dp))
                .padding(24.dp),
        ) {
            Text(slot.label, color = Palette.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(
                "Press the control on your controller to bind it here.",
                color = Palette.textSecondary,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                source?.let { "Currently: ${describeSource(it)}" } ?: "Currently: no binding",
                color = Palette.textMuted,
                fontSize = 13.sp,
            )

            if (notice != null) {
                Spacer(Modifier.height(10.dp))
                Text(notice, color = Palette.danger, fontSize = 13.sp)
            }

            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                EditorButton("Cancel", Palette.surface, onCancel, Modifier.focusRequester(cancelFocus))
            }
        }
    }
}

@Composable
internal fun EditorButton(
    text: String,
    background: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier = modifier
            // Still at the accessible minimum for a touch target, just no longer oversized.
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Palette.focusBg else background)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Palette.focus else Palette.border,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Palette.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
