package com.odininputmirror.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.odininputmirror.domain.model.ControllerDevice
import com.odininputmirror.ui.theme.Palette

// The pieces MirrorScreen lays out, Switch "Controllers" style: a title band, the handheld and its
// pads on the left, a menu of rows on the right, and the start/stop button.

@Composable
internal fun Header(enabled: Boolean, docked: Boolean, loading: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Docking Enhancer", color = Palette.textPrimary, fontSize = 26.sp)
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Palette.accent, strokeWidth = 2.dp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(if (enabled) Palette.custom else Palette.textMuted))
            Spacer(Modifier.width(8.dp))
            Text(
                (if (enabled) "Mirroring" else "Not mirroring") + " · " + (if (docked) "Docked" else "Handheld"),
                color = Palette.textSecondary,
                fontSize = 15.sp,
            )
        }
    }
}

@Composable
internal fun Rule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Palette.border))
}

/** The handheld, drawn as its screen. Tappable only when [onClick] is given (mirror off). */
@Composable
internal fun HandheldTile(device: ControllerDevice?, hint: String, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .border(if (focused) 3.dp else 0.dp, if (focused) Palette.focus else Palette.screen, shape)
            .let { if (onClick != null) it.clickable(interactionSource = interaction, indication = null, onClick = onClick) else it }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(width = 136.dp, height = 76.dp).clip(RoundedCornerShape(10.dp))
                .background(Palette.band).border(4.dp, Palette.borderStrong, RoundedCornerShape(10.dp)),
        )
        Spacer(Modifier.height(6.dp))
        Text(device?.name ?: "No built-in controller", color = Palette.textPrimary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(hint, color = Palette.textMuted, fontSize = 12.sp)
    }
}

/** One connected pad: player lamps, the pad, its name. Lamp 1 lights on the pad being mirrored. */
@Composable
internal fun PadTile(device: ControllerDevice, player1: Boolean, locked: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(4) { lamp ->
                Box(Modifier.size(9.dp).background(if (player1 && lamp == 0) Palette.custom else Palette.borderStrong))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("🎮", fontSize = 30.sp)
        Spacer(Modifier.height(2.dp))
        Text(
            device.name,
            color = if (player1) Palette.textPrimary else Palette.textSecondary,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (locked) {
            Text("LOCKED", color = Palette.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * One menu row: label left, its state right, a rule below. Focus is the accent outline. [onLeft] and
 * [onRight] let a row take the D-pad sideways (the ‹ value › rows).
 */
@Composable
internal fun MenuRow(
    title: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clip(shape)
                .background(if (focused) Palette.focusBg else Palette.screen)
                .border(if (focused) 3.dp else 0.dp, if (focused) Palette.focus else Palette.screen, shape)
                .onPreviewKeyEvent {
                    if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    // One step per press: a held D-pad auto-repeats, and spun through every mode.
                    if (it.nativeKeyEvent.repeatCount > 0 && (onLeft != null || onRight != null)) {
                        return@onPreviewKeyEvent it.key == Key.DirectionLeft || it.key == Key.DirectionRight
                    }
                    when {
                        it.key == Key.DirectionLeft && onLeft != null -> { onLeft(); true }
                        it.key == Key.DirectionRight && onRight != null -> { onRight(); true }
                        else -> false
                    }
                }
                .clickable(interactionSource = interaction, indication = null, enabled = onClick != null) { onClick?.invoke() }
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = if (onClick != null) Palette.textPrimary else Palette.textMuted, fontSize = 18.sp)
                if (subtitle != null) {
                    Text(subtitle, color = Palette.textMuted, fontSize = 12.sp)
                }
            }
            trailing()
        }
        Rule(Modifier.padding(horizontal = 12.dp))
    }
}

@Composable
internal fun RowValue(text: String, accent: Boolean = false) {
    Text(text, color = if (accent) Palette.accent else Palette.textSecondary, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun RowSwitch(checked: Boolean, enabled: Boolean) {
    // Display only: a Switch with its own handler is a second D-pad focus stop inside the row,
    // with a focus ring that is nearly invisible. The row's click toggles the setting.
    Switch(
        checked = checked,
        onCheckedChange = null,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Palette.textPrimary,
            checkedTrackColor = Palette.accent,
            uncheckedThumbColor = Palette.textMuted,
            uncheckedTrackColor = Palette.surfaceRaised,
        ),
    )
}

@Composable
internal fun PrimaryButton(
    text: String,
    stop: Boolean,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .focusRequester(focusRequester)
            .clip(shape)
            .background(if (stop) Palette.dangerSoft else Palette.accentSoft)
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = when {
                    focused -> Palette.focus
                    stop -> Palette.danger
                    else -> Palette.borderStrong
                },
                shape = shape,
            )
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}
