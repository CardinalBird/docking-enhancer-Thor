package com.odininputmirror.ui

import com.odininputmirror.domain.model.Binding
import com.odininputmirror.domain.model.CaptureKind
import com.odininputmirror.domain.model.CaptureResult
import com.odininputmirror.domain.model.ControlRef
import com.odininputmirror.domain.model.ControllerMapping
import com.odininputmirror.domain.model.MappingKey
import com.odininputmirror.domain.model.MappingSlot
import com.odininputmirror.domain.model.PadLayout
import com.odininputmirror.domain.model.SlotIcon
import com.odininputmirror.domain.model.TargetTraits
import com.odininputmirror.domain.model.mappingSlots
import com.odininputmirror.domain.model.odinFallbackTraits
import com.odininputmirror.domain.model.presetBindings
import com.odininputmirror.domain.repository.InputDeviceRepository
import com.odininputmirror.domain.repository.MappingRepository
import com.odininputmirror.domain.repository.MirrorProcessRepository
import com.odininputmirror.domain.repository.MirrorSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MappingEditorUiState(
    val open: Boolean = false,
    val controllerName: String = "",
    val slots: List<MappingSlot> = emptyList(),
    /** What each slot currently reads, keyed by the slot's target. Absent means "forwarded as is". */
    val bindings: Map<ControlRef, ControlRef> = emptyMap(),
    /** The slot waiting for a press, when the capture overlay is up. */
    val capturing: MappingSlot? = null,
    /** Why the last press was refused, shown in the overlay. */
    val notice: String? = null,
    /** True when what is on screen is an untouched database default rather than the user's work. */
    val seeded: Boolean = false,
    /** Index of [capturing] in [slots] while "Map all" walks the list; null outside a walk. */
    val walk: Int? = null,
    /**
     * True when the daemon is not running, so the app itself listens for the press: with the mirror
     * off the pad is not grabbed and reaches the Activity like any other.
     */
    val listenInApp: Boolean = false,
    /** The handheld's own pad, whose presses drive the editor and must never be captured. */
    val internalPadName: String? = null,
    /** evdev axis code -> Android axis name for the pad being mapped, to name its bound axes. */
    val sourceAxisLabels: Map<Int, String> = emptyMap(),
    /** Which console's button icons to draw: the preset last applied to this pad, Xbox by default. */
    val layout: PadLayout = PadLayout.XBOX,
) {
    val boundCount: Int get() = bindings.size
}

/**
 * Drives the mapping editor.
 *
 * While the mirror runs the controller is grabbed and its node unlinked, so the app never sees its
 * input directly: binding a slot opens a capture on the daemon and polls the append-only log for a
 * result. With the mirror off nobody is listening there, so the screen captures the press in the app
 * instead and hands it to [captured].
 *
 * A list first, with the walk ("Map all") as an option rather than the way in. Most slots on most
 * pads are already right, and forcing the user through all of them to fix two made the common case
 * the expensive one.
 */
class MappingEditorController(
    private val mappings: MappingRepository,
    private val devices: InputDeviceRepository,
    private val process: MirrorProcessRepository,
    private val settings: MirrorSettingsRepository,
    /** Hands a pad the user has never mapped its bundled-database default. Safe to repeat. */
    private val seedDefaultMapping: (MappingKey) -> Unit,
    private val scope: CoroutineScope,
    /** Called once the mapping has been written, so whatever displays it can catch up. */
    private val onMappingChanged: () -> Unit = {},
) {
    private val _state = MutableStateFlow(MappingEditorUiState())
    val state: StateFlow<MappingEditorUiState> = _state.asStateFlow()

    private var poller: Job? = null

    // One lane for everything that writes the control FIFO. cancelBind/clear used to fire
    // "capture off" on a free IO thread, unordered with the next bind's "capture button": B then A
    // quickly could land the off AFTER the new capture opened, and the overlay waited forever.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val fifo = Dispatchers.IO.limitedParallelism(1)
    private var mappingKey: MappingKey? = null

    /** What was saved when the editor opened, to tell a real edit from a look around. */
    private var openedWith: Map<ControlRef, ControlRef> = emptyMap()

    /** Whether opening threw away bindings aimed at slots that no longer exist. */
    private var purgedOnOpen = false

    /** Whether the mapping on screen came from the database untouched. */
    private var seededOnOpen = false

    /** Whether the mirror was off when the editor opened, so captures listen in the app. */
    private var listenInAppOnOpen = false

    private var layoutOnOpen = PadLayout.XBOX

    /** What the real external pad declares, for the auto-map presets. Null when unreadable. */
    private var sourceTraits: TargetTraits? = null

    fun open(controllerName: String, key: MappingKey, internalPadName: String? = null) {
        if (_state.value.open) return
        mappingKey = key
        scope.launch {
            // Opened on what is already saved, not on a blank slate: the list has to show the user
            // what their pad does today before it can be somewhere they change it.
            // Slots come from what the target actually declares, read from the live device; the
            // measured Odin defaults only stand in when there is nothing to inspect. This is what
            // keeps the editor from ever offering a target the kernel would silently drop.
            val (saved, slots) = withContext(Dispatchers.IO) {
                // The supervisor normally seeds before the mirror ever starts; repeating it here
                // covers an editor opened before any start. A no-op for a pad already mapped.
                seedDefaultMapping(key)
                val mapping = mappings.get(key)
                seededOnOpen = runCatching { mappings.isSeeded(key) }.getOrDefault(false)
                layoutOnOpen = runCatching { mappings.layout(key) }.getOrDefault(PadLayout.XBOX)
                // Known now rather than after the first capture asks the daemon: the overlay is up
                // before that answer comes back, and a press in the gap would reach the screen.
                listenInAppOnOpen = runCatching { !process.isRunning() }.getOrDefault(false)
                val traits = runCatching { devices.targetTraits() }.getOrNull()
                sourceTraits = runCatching { devices.sourceTraits(key) }.getOrNull()
                mapping to mappingSlots(traits ?: odinFallbackTraits())
            }
            val sourceAxisLabels = runCatching {
                val (vendor, product) = key.value.split(":")
                axisLabels(vendor.toInt(16), product.toInt(16))
            }.getOrDefault(emptyMap())

            // Drop anything aimed at a target no slot claims any more. A stored binding outlives the
            // slot list, so a corrected target leaves the old one behind: invisible in the list, still
            // in the file, and still pushed to a daemon that can do nothing with it.
            val targets = slots.map { it.target }.toSet()
            openedWith = saved.bindings
                .filter { it.target in targets }
                .associate { it.target to it.source }
            // Worth writing out even if the user changes nothing else: a stale binding still MATCHES
            // its source, so it swallows that event on the way past and hands it to a target the
            // kernel discards. Leaving it in the file would keep costing the user the control it
            // names long after the slot it aimed at stopped existing.
            purgedOnOpen = openedWith.size != saved.bindings.size

            _state.value = MappingEditorUiState(
                open = true,
                controllerName = controllerName,
                slots = slots,
                bindings = openedWith,
                seeded = seededOnOpen,
                internalPadName = internalPadName,
                listenInApp = listenInAppOnOpen,
                sourceAxisLabels = sourceAxisLabels,
                layout = layoutOnOpen,
            )
        }
    }

    /** Start listening for the control that should drive this slot. */
    fun bind(slot: MappingSlot) {
        if (!_state.value.open) return
        _state.update {
            val blind = it.listenInApp && it.internalPadName == null
            it.copy(capturing = slot, notice = "Pick the built-in controller first".takeIf { blind })
        }
        startCapture()
    }

    /** Walk every slot in order, opening the capture for each. */
    fun mapAll() {
        val first = _state.value.slots.firstOrNull() ?: return
        if (!_state.value.open) return
        _state.update { it.copy(walk = 0) }
        bind(first)
    }

    /** End a walk, leaving what it has bound so far. */
    fun stopWalk() {
        _state.update { it.copy(walk = null) }
        cancelBind()
    }

    /** Replace the whole mapping with a layout preset. Held until [close], like any other edit. */
    fun autoMap(layout: PadLayout) {
        if (!_state.value.open) return
        stopPolling()
        _state.update {
            // No preset binds Home or Back, so whatever the user put there stays.
            val kept = it.slots.filter { s -> s.icon == SlotIcon.HOME || s.icon == SlotIcon.BACK }.map { s -> s.target }
            it.copy(
                bindings = it.bindings.filterKeys { k -> k in kept } + presetBindings(layout, it.slots, sourceTraits),
                capturing = null, walk = null, notice = null, seeded = false, layout = layout,
            )
        }
        // Icons only, so saved at once rather than with the mapping.
        mappingKey?.let { key -> scope.launch(Dispatchers.IO) { mappings.setLayout(key, layout) } }
        scope.launch(fifo) { process.endCapture() }
    }

    /** A press the app heard itself (mirror off). Main thread only. */
    fun captured(source: ControlRef) {
        if (accept(source) == CaptureVerdict.Next) startCapture()
    }

    /** The key layout of a pad the app hears directly, to name its axes by evdev code. */
    fun axisLabels(vendorId: Int, productId: Int): Map<Int, String> =
        runCatching { devices.axisLabels(vendorId, productId) }.getOrDefault(emptyMap())

    /** Close the overlay without changing anything. During a walk, skip to the next slot instead. */
    fun cancelBind() {
        if (advanceWalk()) {
            startCapture()
            return
        }
        stopPolling()
        scope.launch(fifo) { process.endCapture() }
        _state.update { it.copy(capturing = null, notice = null) }
    }

    /** Back to forwarding whatever the pad reports for this slot. */
    fun clear(slot: MappingSlot) {
        if (!_state.value.open) return
        _state.update {
            it.copy(bindings = it.bindings - slot.target, capturing = null, walk = null, notice = null, seeded = false)
        }
        stopPolling()
        scope.launch(fifo) { process.endCapture() }
    }

    /** Throw the whole mapping away. Stays open, so the user can see it emptied and start over. */
    fun clearAll() {
        if (!_state.value.open) return
        stopPolling()
        // No longer "the profile as it came": what is on screen is the user's doing from here on,
        // even though clearing it all is what will hand the profile back on the way out.
        _state.update { it.copy(bindings = emptyMap(), capturing = null, walk = null, notice = null, seeded = false) }
        scope.launch(fifo) { process.endCapture() }
    }

    /**
     * Leave, writing the mapping on the way out.
     *
     * Held until here rather than pushed on every binding: a walk through the list is one edit as far
     * as the user is concerned, and pushing each row separately meant a config write and a daemon
     * reload per press — twenty of them to remap a pad, each one a chance to be caught half-applied.
     */
    fun close() {
        // The page lingers for its exit animation, so a second B can land here: the state is then
        // already empty and a second write would wipe what the first one saved.
        if (!_state.value.open) return
        stopPolling()
        val key = mappingKey
        val bindings = _state.value.bindings
        // A visit that changed nothing writes nothing: bumping the generation would make the daemon
        // re-read a document identical to the one it is already running.
        val edited = bindings != openedWith || purgedOnOpen

        scope.launch(Dispatchers.IO) {
            process.endCapture()
            if (key != null && edited) {
                // Written as ONE indivisible unit. This scope belongs to the screen, and the screen
                // closing is precisely what runs this — so a cancel landing between the save and the
                // bump stored the mapping and told nobody: the badge showed the new mapping while the
                // pad went on using the old one until something unrelated restarted the daemon.
                withContext(NonCancellable) {
                    val mapping = ControllerMapping(bindings.map { Binding(it.value, it.key) })
                    if (mapping.isEmpty) {
                        mappings.clear(key)
                        // Clearing everything means "back to how this should be", and for a pad the
                        // database knows, that is its profile. Restored HERE rather than at the next
                        // mirror start: otherwise the card sits badge-less claiming the pad has no
                        // mapping while it is about to be handed one, and the pad stays unmapped until
                        // something unrelated happens to restart the daemon. A pad the database does not
                        // know seeds nothing and stays genuinely cleared.
                        seedDefaultMapping(key)
                    } else {
                        mappings.save(key, mapping)
                        // Whatever this was before, it is the user's now.
                        mappings.setSeeded(key, false)
                    }
                    // Advancing the generation is what makes the supervisor push the new config on its
                    // next tick — the same path a toggle takes, so the daemon adopts it without a restart.
                    settings.bumpConfigGeneration()
                }
            }
            // After the write, not before: the badge is read straight back out of storage.
            withContext(Dispatchers.Main) { onMappingChanged() }
        }
        _state.value = MappingEditorUiState()
    }

    /**
     * Open a capture on the daemon and poll it until something fills the slot.
     *
     * Opening and polling are ONE coroutine deliberately. As two they were unordered, so the poller
     * could read the log — and advance its offset past the end of it — before `clearCaptures` had
     * emptied it, handing the new slot a press left over from the previous one. Sequencing them also
     * makes the offset a local, which is the strongest form of "not shared".
     *
     * Polling rather than a callback because the daemon speaks through a file. 80ms is well under
     * what reads as instant for a button press, and nothing is being forwarded meanwhile anyway.
     */
    private fun startCapture() {
        poller?.cancel()
        poller = scope.launch(fifo) {
            process.clearCaptures()
            var offset = 0L
            // Always the button mode, even for a stick direction: what a slot wants is one control
            // pushed one way, and that is exactly what this mode reports — a code for a button, a
            // code and a direction for an axis.
            // The daemon listens when it runs. With the mirror off it cannot, but then the pad is not
            // grabbed either and reaches the app, so the screen listens instead.
            val daemon = process.beginCapture(CaptureKind.BUTTON)
            withContext(Dispatchers.Main) { _state.update { it.copy(listenInApp = !daemon) } }
            if (!daemon) return@launch
            var lastBegin = System.currentTimeMillis()

            while (isActive && _state.value.capturing != null) {
                // The daemon closes an idle capture after 30s so a dead wizard can't mute the pad
                // for good. Renew it while the overlay is genuinely still waiting.
                if (System.currentTimeMillis() - lastBegin > RENEW_CAPTURE_MS) {
                    process.beginCapture(CaptureKind.BUTTON)
                    lastBegin = System.currentTimeMillis()
                }
                val read = process.readCaptures(offset)
                offset = read.offset
                val source = when (val result = read.results.firstOrNull()) {
                    is CaptureResult.Button -> ControlRef.button(result.code)
                    // The pad reports this control as an axis — a trigger, usually, or a stick being
                    // pushed at a slot. Half of that axis's travel is the source, so it fills any slot.
                    is CaptureResult.AxisButton -> ControlRef.half(result.code, result.sign)
                    is CaptureResult.Stick, null -> null
                }
                if (source != null) {
                    when (withContext(Dispatchers.Main) { accept(source) }) {
                        CaptureVerdict.Accepted -> {
                            process.endCapture()
                            return@launch
                        }
                        // Reopen: the daemon latches as soon as it has a result, so without this the
                        // overlay would sit there refusing to see the next press (or, in a walk,
                        // the next slot's). Done HERE rather than from accept() so it cannot race
                        // the loop that is still reading.
                        CaptureVerdict.Refused, CaptureVerdict.Next -> {
                            process.clearCaptures()
                            offset = 0L
                            process.beginCapture(CaptureKind.BUTTON)
                        }
                        CaptureVerdict.Ignored -> Unit
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopPolling() {
        poller?.cancel()
        poller = null
    }

    /** What the capture loop should do next with the press it just handed over. */
    private enum class CaptureVerdict {
        /** It filled the slot; the capture is finished. */
        Accepted,

        /** The control belongs to another slot; reopen and wait for a different press. */
        Refused,

        /** It filled the slot and the walk moved on; reopen for the next one. */
        Next,

        /** Nothing this mode can use; keep waiting. */
        Ignored,
    }

    /**
     * Fold one press into the slot being bound, and say what the loop should do next.
     *
     * Returns a verdict rather than acting: it runs ON the capture coroutine (via the main
     * dispatcher), so cancelling the poller or reopening the capture from in here would be the loop
     * reaching around itself — in the reopen case, resetting the offset of a read still in flight.
     */
    private fun accept(source: ControlRef): CaptureVerdict {
        val slot = _state.value.capturing ?: return CaptureVerdict.Ignored

        // Refusing beats stealing. Letting the newer binding win silently is how a user ends up with
        // a control torn off a slot they set earlier with nothing on screen to connect the two — and
        // the daemon would have both bindings fighting over the same event anyway.
        val taken = _state.value.bindings.entries.firstOrNull {
            it.value == source && it.key != slot.target
        }
        // Except mid-walk, from a slot still ahead: the walk is going to ask for that one anyway, and
        // refusing would make swapping two buttons impossible without clearing first.
        val walk = _state.value.walk
        val ownerIndex = taken?.let { t -> _state.value.slots.indexOfFirst { it.target == t.key } } ?: -1
        if (taken != null && walk != null && ownerIndex > walk) {
            _state.update { it.copy(bindings = it.bindings - taken.key) }
        } else if (taken != null) {
            val owner = _state.value.slots.firstOrNull { it.target == taken.key }?.label ?: "another slot"
            _state.update { it.copy(notice = "That control is already bound to $owner") }
            return CaptureVerdict.Refused
        }

        _state.update {
            it.copy(bindings = it.bindings + (slot.target to source), capturing = null, notice = null, seeded = false)
        }
        return if (advanceWalk()) CaptureVerdict.Next else CaptureVerdict.Accepted
    }

    /** Move a walk on to the next slot, or end it after the last. True while there is a next slot. */
    private fun advanceWalk(): Boolean {
        val walk = _state.value.walk ?: return false
        val next = walk + 1
        val slot = _state.value.slots.getOrNull(next)
        _state.update { it.copy(walk = slot?.let { next }, capturing = slot, notice = null) }
        return slot != null
    }

    private companion object {
        const val POLL_INTERVAL_MS = 80L
        const val RENEW_CAPTURE_MS = 20_000L
    }
}
