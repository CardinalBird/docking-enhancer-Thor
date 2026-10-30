package com.odininputmirror.ui

import com.odininputmirror.domain.model.HomeButtonMode
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import com.odininputmirror.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.odininputmirror.MainActivity
import com.odininputmirror.domain.model.lockedPlayer1
import com.odininputmirror.ui.theme.Palette
import kotlinx.coroutines.delay

private fun Context.canPinShortcut(): Boolean =
    getSystemService(ShortcutManager::class.java)?.isRequestPinShortcutSupported == true

// The home app answers with its own confirmation; there is nothing to do with its answer here.
private fun Context.pinToHome() {
    val shortcut = ShortcutInfo.Builder(this, "open")
        .setShortLabel(getString(R.string.app_name))
        .setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
        .setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
        .build()
    getSystemService(ShortcutManager::class.java)?.requestPinShortcut(shortcut, null)
}

internal fun Context.findMainActivity(): MainActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is MainActivity) return current
        current = current.baseContext
    }
    return null
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun MirrorScreen(viewModel: MirrorViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val editorState by viewModel.mappingEditor.state.collectAsStateWithLifecycle()

    // A whole screen rather than an overlay: mapping is the only thing happening while it is open,
    // and the controller is answering it instead of driving whatever would show through behind.
    if (editorState.open) {
        MappingEditorScreen(
            state = editorState,
            onBind = { viewModel.mappingEditor.bind(it) },
            onClear = { viewModel.mappingEditor.clear(it) },
            onCancelBind = { viewModel.mappingEditor.cancelBind() },
            onClearAll = { viewModel.mappingEditor.clearAll() },
            onClose = { viewModel.mappingEditor.close() },
        )
        return
    }
    if (state.pickingPlayer1) {
        Player1Screen(
            externals = state.devices.filter { !it.isInternal },
            player1Name = state.player1Name,
            player1Path = state.player1Path,
            onPick = viewModel::choosePlayer1,
            onUnlock = { viewModel.setPlayer1(null) },
            onClose = viewModel::closePlayer1Picker,
        )
        return
    }
    var pickerOpen by remember { mutableStateOf(false) }
    var screenHasFocus by remember { mutableStateOf(false) }
    // Initial gamepad focus prefers the first control (the Local Controller card) and falls back to
    // the always-present primary button for when the card isn't focusable (mirror on / busy).
    val topFocus = remember { FocusRequester() }
    val primaryFocus = remember { FocusRequester() }
    val scrollState = rememberScrollState()

    // Bridge the Activity's analog-stick handler to this screen's scroll state so the right stick
    // free-scrolls the whole page (header included), independent of which control has focus.
    val context = LocalContext.current
    DisposableEffect(context, scrollState) {
        val activity = context.findMainActivity()
        activity?.scrollConsumer = { delta -> scrollState.dispatchRawDelta(delta) }
        onDispose { activity?.scrollConsumer = null }
    }

    // Anchor gamepad focus on the first control, leaving touch mode first. On a touchscreen handheld
    // the window starts in Touch input mode, in which requestFocus() is a silent no-op and the D-pad
    // and stick have no anchor to navigate from until a face button hands focus out.
    val inputModeManager = LocalInputModeManager.current
    val focusManager = LocalFocusManager.current
    // Reports whether a target actually took the focus. The caller needs to know: a request made
    // before the node is placed fails silently, and there is no other way to tell.
    val anchorFocus: () -> Boolean = remember(inputModeManager, topFocus, primaryFocus) {
        {
            inputModeManager.requestInputMode(InputMode.Keyboard)
            runCatching { topFocus.requestFocus() }.isSuccess ||
                runCatching { primaryFocus.requestFocus() }.isSuccess
        }
    }

    // Re-anchor the instant the window gains focus. The launch-time effect below can lose the race
    // when the window isn't focused yet (e.g. while controllers are scanned), so without this the
    // D-pad/stick have no focus to move from until a button press hands it out.
    DisposableEffect(context, anchorFocus) {
        val activity = context.findMainActivity()
        // The Activity hook wants a plain callback; whether the anchor took is only the retry
        // loop's business.
        activity?.onWindowFocused = { anchorFocus() }
        onDispose { activity?.onWindowFocused = null }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onResume()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Give the gamepad a focus anchor so the D-pad works immediately, retrying across a few frames
    // because the focus node is not necessarily placed on the first pass.
    //
    // Keyed on the editor: coming back from it this whole screen is composed again, and it must take
    // the focus back — the editor's rows are gone and nothing else holds it.
    //
    // The loop stops on OBSERVED focus, not on what requestFocus() reported. That distinction is the
    // whole bug: on the return trip the anchor node exists immediately, so the request "succeeds" on
    // the first frame while the focus goes nowhere, the loop congratulates itself and exits, and the
    // pad is left with nothing to navigate from.
    LaunchedEffect(editorState.open) {
        if (editorState.open) return@LaunchedEffect
        // Let go of the focus before asking for it. Leaving the editor destroys the node that held it,
        // and Compose is then left pointing at something that no longer exists — in that state a fresh
        // requestFocus() is refused outright, which is why the retry loop alone was not enough.
        focusManager.clearFocus(force = true)
        repeat(24) {
            if (screenHasFocus) return@LaunchedEffect
            anchorFocus()
            // Belt and braces: if neither named anchor will take it, ask the focus system for the
            // first thing it can find. Landing somewhere unexpected still beats landing nowhere, which
            // costs the user the whole app until they restart it.
            if (!screenHasFocus) focusManager.moveFocus(FocusDirection.Down)
            delay(50)
        }
    }

    // When a state change disables whatever had focus — toggling the mirror briefly makes the
    // button busy, and starting/stopping it locks the cards and rows — Compose clears focus and
    // the D-pad has nothing to navigate from. Re-anchor to the first available control so gamepad
    // navigation keeps working after every transition.
    LaunchedEffect(state.enabled, state.autoMirrorEnabled, state.busy) {
        anchorFocus()
    }

    val externals = state.devices.filter { !it.isInternal }
    val lockedPad = externals.lockedPlayer1(state.player1Name, state.player1Path)
    val homeModes = HomeButtonMode.entries
    fun cycleHome(step: Int) {
        viewModel.setHomeMode(homeModes[(state.homeMode.ordinal + step + homeModes.size) % homeModes.size])
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.screen)
            // Whether anything at all on this screen holds focus. `hasFocus` covers the whole subtree,
            // so one modifier on the root answers a question no FocusRequester can: requestFocus()
            // reports success as soon as its node exists, which is not the same as the node having
            // taken focus.
            .onFocusChanged { screenHasFocus = it.hasFocus }
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Header(enabled = state.enabled, docked = state.docked, loading = state.loading)
        Rule(Modifier.padding(horizontal = 28.dp))

        Row(modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 28.dp)) {
            // Left: the handheld, then every pad it can see, with player 1's lamp lit.
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().padding(vertical = 8.dp, horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                HandheldTile(
                    device = state.localDevice,
                    hint = when {
                        state.autoMirrorEnabled -> "Built-in · locked while mirror is on"
                        state.hasKnownInternalProfile -> "Built-in · auto-detected"
                        else -> "Built-in · tap to change"
                    },
                    onClick = if (state.busy || state.autoMirrorEnabled) null else ({ pickerOpen = true }),
                )
                Rule(Modifier.padding(vertical = 4.dp))
                if (externals.isEmpty()) {
                    Text("Connect a controller to play docked.", color = Palette.textMuted, fontSize = 14.sp)
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                        externals.take(4).forEach { pad ->
                            PadTile(
                                device = pad,
                                player1 = pad.path == state.externalDevice?.path,
                                locked = pad == lockedPad,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(4 - externals.size.coerceAtMost(4)) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }

            Box(Modifier.fillMaxHeight().width(1.dp).padding(vertical = 16.dp).background(Palette.border))

            // Right: the menu.
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(scrollState)
                    .padding(start = 16.dp, top = 16.dp, bottom = 16.dp),
            ) {
                MenuRow(
                    title = "Player 1 controller",
                    subtitle = "Pick with L + R",
                    onClick = if (state.busy) null else viewModel::openPlayer1Picker,
                    modifier = Modifier.focusRequester(topFocus),
                ) {
                    RowValue(if (state.player1Name != null) "Locked" else "First connected", accent = state.player1Name != null)
                }
                MenuRow(
                    title = "Button mapping",
                    subtitle = state.externalDevice?.name,
                    // Only offered once the controller has an identity to save a mapping against.
                    onClick = if (state.externalDevice?.mappingKey != null) viewModel::openMappingEditor else null,
                ) {
                    RowValue(
                        when {
                            (state.mappedControlCount ?: 0) > 0 -> "${state.mappedControlCount} custom"
                            state.hasSeededMapping -> "Profile"
                            else -> "As reported"
                        },
                        accent = (state.mappedControlCount ?: 0) > 0,
                    )
                }
                MenuRow(
                    title = "Home button",
                    subtitle = if (state.homeMode == HomeButtonMode.PASS_THROUGH) "Sent to games" else "Hold for Recents",
                    onClick = if (state.busy) null else ({ cycleHome(1) }),
                    onLeft = { cycleHome(-1) },
                    onRight = { cycleHome(1) },
                ) {
                    RowValue(
                        "‹  " + when (state.homeMode) {
                            HomeButtonMode.HOME -> "Home"
                            HomeButtonMode.BACK -> "Back"
                            HomeButtonMode.PASS_THROUGH -> "Pass through"
                        } + "  ›",
                        accent = true,
                    )
                }
                MenuRow(
                    title = "Select + Start closes app",
                    subtitle = "Hold both for 3 seconds",
                    onClick = if (state.busy) null else ({ viewModel.toggleComboHoldKillApp(!state.comboHoldKillApp) }),
                ) {
                    RowSwitch(state.comboHoldKillApp, !state.busy) { viewModel.toggleComboHoldKillApp(it) }
                }
                MenuRow(
                    title = "Virtual mouse",
                    subtitle = "Select + R3 toggles · A click · B right-click · R1 shade",
                    onClick = if (state.busy) null else ({ viewModel.toggleVirtualMouse(!state.virtualMouse) }),
                ) {
                    RowSwitch(state.virtualMouse, !state.busy) { viewModel.toggleVirtualMouse(it) }
                }
                // Game frontends (Cocoon Shell on the Thor) often hide the Android app list, so the app
                // can ask to be pinned itself. Shown only when the home app takes pin requests.
                if (context.canPinShortcut()) {
                    MenuRow(title = "Add to home screen", subtitle = "Pin this app to your launcher", onClick = { context.pinToHome() })
                }
                Spacer(Modifier.height(16.dp))
                PrimaryButton(
                    text = when {
                        state.busy -> "Processing..."
                        state.autoMirrorEnabled -> "Turn off automatic mirror"
                        else -> "Turn on automatic mirror"
                    },
                    stop = state.autoMirrorEnabled,
                    focusRequester = primaryFocus,
                    // The ViewModel ignores presses while busy, so the button can stay focusable
                    // (a disabled control would drop D-pad focus mid-toggle).
                    onClick = { viewModel.toggleAutoMirrorEnabled(!state.autoMirrorEnabled) },
                )
            }
        }

        // Status sits in the bottom band, left of the hints, so the controller pane never runs out of room.
        Row(Modifier.fillMaxWidth().background(Palette.band), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.loading) "Scanning controllers..."
                else if (state.restartWaiting) "Waiting for automatic mirror start..."
                else state.message,
                color = Palette.textSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(start = 28.dp).weight(1f),
            )
            Box(Modifier.weight(1f)) {
                HintBar(
                    hints = listOf(
                        HintAction(PromptGlyph.B, "Back", null),
                        HintAction(PromptGlyph.A, "OK", null),
                    ),
                )
            }
        }
    }

    if (pickerOpen) {
        InternalControllerPicker(
            // Drop whatever is currently acting as the external pad: this list answers "which one is
            // built into the handheld?", and the mirror source is never that. Matching on path keeps
            // a placeholder external (resolved from saved identity, absent from the list) from
            // filtering anything out. "Automatic (detect)" stays available to undo a wrong pick.
            devices = state.devices.filter { it.path != state.externalDevice?.path },
            selectedGuid = state.manualInternalGuid,
            onSelect = { guid ->
                pickerOpen = false
                viewModel.selectInternalController(guid)
            },
            onClose = { pickerOpen = false },
        )
    }

    if (state.unsupported) {
        UnsupportedDeviceDialog(serviceUnresponsive = state.serviceUnresponsive)
    }
}
