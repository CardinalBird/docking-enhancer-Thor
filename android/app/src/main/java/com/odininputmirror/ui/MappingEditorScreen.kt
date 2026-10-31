package com.odininputmirror.ui

import com.odininputmirror.domain.model.SlotIcon
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.odininputmirror.domain.model.MappingSlot
import com.odininputmirror.domain.model.PadLayout
import com.odininputmirror.ui.theme.Palette
import kotlinx.coroutines.delay

/**
 * The mapping editor: one flat list of every slot and what it currently reads, with the auto-map
 * presets above it and "Map all" (Y) to walk the whole list one press at a time.
 *
 * A screen rather than a dialog because it is the only thing the user is doing, and the controller is
 * busy answering it rather than driving the app behind. Back leaves.
 *
 * [controller] carries what the original callbacks do not: the walk, the presets, and listening for
 * a press in the app while the mirror is off. Without it those parts are simply not offered.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun MappingEditorScreen(
    state: MappingEditorUiState,
    onBind: (MappingSlot) -> Unit,
    onClear: (MappingSlot) -> Unit,
    onCancelBind: () -> Unit,
    onClearAll: () -> Unit,
    onClose: () -> Unit,
    controller: MappingEditorController? = null,
) {
    // Back means "close the overlay" while one is up, and "leave" otherwise — anything else would
    // make the overlay a trap, since the pad that would normally dismiss it is the one being captured.
    BackHandler(enabled = state.open) { if (state.capturing != null) onCancelBind() else onClose() }

    // One anchor per row, and a memory of which row opened the overlay.
    //
    // Both halves are load-bearing. The handheld opens windows in Touch input mode, where
    // requestFocus() is a silent no-op, so the mode has to be left first and the request retried
    // across a few frames — the focus node is not necessarily placed on the first pass. And when the
    // overlay closes, the node that held focus is destroyed with it: Compose then has nowhere to put
    // focus and the D-pad restarts from the top of the list, which after binding row twenty is a long
    // way from where the user was. Sending it back to the row they came from is the whole point.
    val rowFocus = remember(state.slots.size) { List(state.slots.size) { FocusRequester() } }
    var focusedRow by remember { mutableIntStateOf(0) }
    val inputModeManager = LocalInputModeManager.current
    val listScroll = rememberScrollState()
    var scrollWhenOpened by remember { mutableIntStateOf(0) }

    // Which row wants the focus back, if any. Asked for explicitly rather than inferred from the
    // state, because only some changes should move the focus: leaving the overlay and clearing a row
    // both destroy the node that held it, but Clear All is pressed from the footer and has to leave
    // the focus right there — throwing the user to the top of a list they were done with is the
    // opposite of helpful.
    var restoreTo by remember { mutableStateOf<Int?>(null) }
    // Where focus really is, as the rows report it: requestFocus() says nothing about whether it took.
    var focusedSlot by remember { mutableStateOf(-1) }

    // Closing the overlay hands the row back. Also fires on the very first composition, with the
    // default of row zero, which is the initial anchor the D-pad needs.
    LaunchedEffect(state.capturing) {
        if (state.capturing == null) restoreTo = focusedRow
    }

    // With the mirror off the daemon cannot listen, so the app does.
    if (controller != null) {
        PadCaptureEffect(
            active = state.capturing != null && state.listenInApp,
            internalPadName = state.internalPadName,
            axisLabels = controller::axisLabels,
            onCaptured = controller::captured,
        )
    }

    LaunchedEffect(restoreTo, state.slots.size) {
        val target = restoreTo ?: return@LaunchedEffect
        repeat(12) {
            inputModeManager.requestInputMode(InputMode.Keyboard)
            rowFocus.getOrNull(target)?.let { runCatching { it.requestFocus() } }
            delay(40)
            // Judged by where focus went, not by requestFocus(), which reports success even when the
            // request was refused (touch mode, a node not yet placed) and ended the loop on try one.
            if (focusedSlot == target) {
                // Put the list back exactly where it was. Taking focus drags the row into view, and
                // "into view" means the nearest edge — so a row that sat comfortably mid-screen comes
                // back pinned to the top or the bottom, and the list appears to jump on its own.
                listScroll.scrollTo(scrollWhenOpened)
                restoreTo = null
                return@LaunchedEffect
            }
        }
        restoreTo = null
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Palette.screen).onPreviewKeyEvent {
            // Both halves: Android turns an unhandled Y press into Back.
            if (it.key != Key.ButtonY || state.capturing != null || controller == null) return@onPreviewKeyEvent false
            if (it.type == KeyEventType.KeyUp) controller.mapAll()
            true
        },
    ) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column(Modifier.padding(horizontal = 28.dp, vertical = 16.dp)) {
                Text("Button mapping", color = Palette.textPrimary, fontSize = 26.sp)
                Text(
                    if (state.seeded) {
                        // A known pad arrives already mapped from its profile; the editor's job then is
                        // adjustment, and saying so stops "why is everything filled in?" cold.
                        "${state.controllerName} · set from its profile. Pick a button to change it, or map them all in turn."
                    } else {
                        "${state.controllerName} · buttons left unbound work as the controller reports them."
                    },
                    color = Palette.textSecondary,
                    fontSize = 14.sp,
                )
            }
            Rule(Modifier.padding(horizontal = 28.dp))

            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(listScroll)
                    .padding(horizontal = 28.dp, vertical = 12.dp),
            ) {
                if (controller != null) {
                    ActionRow("Auto-map: Xbox layout", "A B X Y as printed on an Xbox pad") { controller.autoMap(PadLayout.XBOX) }
                    ActionRow("Auto-map: Nintendo layout", "A B X Y as printed on a Switch pad") { controller.autoMap(PadLayout.NINTENDO) }
                }
                ActionRow("Reset to profile", "Back to how this controller came", onClearAll)
                state.slots.forEachIndexed { index, slot ->
                    SlotRow(
                        slot = slot,
                        source = state.bindings[slot.target],
                        axisLabels = state.sourceAxisLabels,
                        onBind = {
                            focusedRow = index
                            // Recorded HERE, which is the whole trick: by the time the overlay closes
                            // the list has already been scrolled by the focus move, so the position
                            // worth returning to is the one from the instant before it opened.
                            scrollWhenOpened = listScroll.value
                            onBind(slot)
                        },
                        onClear = {
                            focusedRow = index
                            scrollWhenOpened = listScroll.value
                            onClear(slot)
                            restoreTo = index
                        },
                        modifier = Modifier
                            .focusRequester(rowFocus[index])
                            .onFocusChanged { if (it.isFocused) focusedSlot = index },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().background(Palette.band),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    state.notice
                        ?: if (state.boundCount == 0) "Nothing bound yet." else "${state.boundCount} bound — applied when you leave.",
                    color = Palette.textMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 28.dp).weight(1f),
                )
                Box(Modifier.weight(2f)) {
                    HintBar(
                        hints = listOfNotNull(
                            HintAction(SlotIcon.A, "Rebind", null),
                            HintAction(SlotIcon.X, "Clear", null),
                            controller?.let { HintAction(SlotIcon.Y, "Map all", it::mapAll) },
                            HintAction(SlotIcon.B, "Done", onClose),
                        ),
                    )
                }
            }
        }

        if (state.capturing != null) {
            CaptureOverlay(
                slot = state.capturing,
                source = state.bindings[state.capturing.target],
                axisLabels = state.sourceAxisLabels,
                notice = state.notice,
                onCancel = onCancelBind,
                // "3 of 26": the walk's place, counted from one.
                progress = state.walk?.let { "${it + 1} of ${state.slots.size}" },
                onStop = controller?.let { it::stopWalk }.takeIf { state.walk != null },
            )
        }
    }
}
