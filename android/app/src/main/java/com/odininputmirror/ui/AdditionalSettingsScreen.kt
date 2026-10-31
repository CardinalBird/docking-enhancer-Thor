package com.odininputmirror.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The options that used to make the main menu scroll. Rows are one line each so the page fits
 * without scrolling; the focused row's explanation shows in the footer instead, Switch-style.
 */
@Composable
internal fun AdditionalSettingsScreen(state: MirrorUiState, viewModel: MirrorViewModel, active: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = active, onBack = onBack)
    val context = LocalContext.current
    // A binder call; asked once, not on every recomposition.
    val canPin = remember(context) { context.canPinShortcut() }
    var help by remember { mutableStateOf("") }
    var hasFocus by remember { mutableStateOf(false) }

    // Same anchoring as the main page, minus its window hook: leave touch mode, then retry until the
    // first row has really taken the focus (requestFocus() reports success before it does).
    val first = remember { FocusRequester() }
    val inputModeManager = LocalInputModeManager.current
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        repeat(24) {
            if (hasFocus) return@LaunchedEffect
            inputModeManager.requestInputMode(InputMode.Keyboard)
            runCatching { first.requestFocus() }
            delay(50)
        }
    }

    // Each row reports itself as the footer's text when it holds the focus.
    fun explains(text: String) = Modifier.onFocusChanged { if (it.hasFocus) help = text }

    Column(
        Modifier.fillMaxSize().onFocusChanged { hasFocus = it.hasFocus }.windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Header(title = "Additional Settings")
        Rule(Modifier.padding(horizontal = 28.dp))
        Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 120.dp, vertical = 8.dp)) {
            MenuRow(
                title = "Start + L3 sends Home",
                minHeight = 52.dp,
                onClick = { viewModel.toggleComboTapHome(!state.comboTapHome) },
                modifier = Modifier.focusRequester(first).then(explains("Tap Start and L3 together to go to the home screen.")),
            ) { RowSwitch(state.comboTapHome, !state.busy) }
            MenuRow(
                title = "Select + Start closes app",
                minHeight = 52.dp,
                onClick = { viewModel.toggleComboHoldKillApp(!state.comboHoldKillApp) },
                modifier = explains("Hold both for 3 seconds to close the game you're in."),
            ) { RowSwitch(state.comboHoldKillApp, !state.busy) }
            MenuRow(
                title = "Virtual mouse",
                minHeight = 52.dp,
                onClick = { viewModel.toggleVirtualMouse(!state.virtualMouse) },
                modifier = explains("Select + R3 toggles · A click · B right-click · R1 shade"),
            ) { RowSwitch(state.virtualMouse, !state.busy) }
            val always = state.mirrorWhenHandheld
            val flip = { viewModel.setMirrorWhenHandheld(!always) }
            MenuRow(
                title = "Allow mirroring",
                minHeight = 52.dp,
                onClick = flip,
                onLeft = flip,
                onRight = flip,
                modifier = explains(
                    if (always) "Always, when a controller is connected: handheld or docked."
                    else "Only when docked.",
                ),
            ) { RowValue("‹  " + (if (always) "Always" else "Only when docked") + "  ›", accent = true) }
            // Game frontends (Cocoon Shell on the Thor) often hide the Android app list, so the app
            // can ask to be pinned itself. Shown only when the home app takes pin requests.
            if (canPin) {
                MenuRow(
                    title = "Add to home screen",
                    minHeight = 52.dp,
                    onClick = { context.pinToHome() },
                    modifier = explains("Pin this app to your launcher."),
                )
            }
        }
        Footer(status = help, hints = listOf(HintAction("B", "Back", onBack), HintAction("A", "OK", null)))
    }
}
