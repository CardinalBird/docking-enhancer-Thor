package com.odininputmirror.data

import android.view.InputDevice
import com.odininputmirror.domain.model.ControllerDevice
import com.odininputmirror.domain.model.MappingKey
import com.odininputmirror.domain.model.TargetTraits
import com.odininputmirror.domain.repository.InputDeviceRepository
import java.io.File

// Compiled once: these run on every supervisor tick, several times per /proc block and dumpsys line.
private val PROC_BLOCK_SEPARATOR = Regex("\\n\\s*\\n")
private val PROC_IDENTITY = Regex("I: Bus=([0-9A-Fa-f]+) Vendor=([0-9A-Fa-f]+) Product=([0-9A-Fa-f]+)")
private val PROC_NAME = Regex("N: Name=\"([^\"]+)\"")
private val PROC_HANDLERS = Regex("H: Handlers=(.+)")
private val PROC_EVENT_NAME = Regex("\\bevent\\d+\\b")
private val PROC_KEY_BITS = Regex("B: KEY=(.+)")
private val PROC_ABS_BITS = Regex("B: ABS=(.+)")
private val DUMPSYS_HUB_ID = Regex("""^\s+(-?\d+): """)
private val DUMPSYS_HUB_PATH = Regex("""^\s+Path: (/dev/input/\S+)""")
private val DUMPSYS_READER_DEVICE = Regex("""^\s+Device (-?\d+): """)
private val DUMPSYS_READER_HUBS = Regex("""EventHub Devices: \[\s*(-?\d+)""")

internal class AndroidInputDeviceRepository(
    private val shell: MirrorShell = UnavailableShell,
    private val pathExists: (String) -> Boolean = { File(it).exists() },
    private val manualInternalGuidProvider: () -> String? = { null },
    // Path of the mirror source we may have hidden (its /dev node unlinked). Lets us re-materialise
    // it from /proc so the UI and auto-mirror logic still see the controller being mirrored.
    private val hiddenSourcePathProvider: () -> String? = { null },
    // True only while the mirror is actually running. Gates re-materialisation so a STALE persisted
    // source (from a prior session, e.g. a real node that never has a /dev entry) is never revived
    // when nothing is running — which would otherwise block restarting on the live node.
    private val mirrorRunningProvider: () -> Boolean = { false },
    private val keyLayouts: KeyLayoutFiles = KeyLayoutFiles(),
) : InputDeviceRepository {
    /**
     * What the internal pad can actually receive, straight from the running system: its capability
     * bitmasks out of /proc, and its Android key layout for what the codes mean. Built fresh per
     * call — the editor opens rarely, and a cached answer would survive a controller swap.
     */
    override fun targetTraits(): TargetTraits? {
        val procEntries = readProcEntries()
        val internal = getConnectedControllers().firstOrNull { it.isInternal } ?: return null
        val entry = procEntries.firstOrNull { it.path == internal.path } ?: return null
        if (entry.keyBits.isEmpty() && entry.absBits.isEmpty()) {
            return null
        }
        val layout = keyLayouts.resolve(entry.vendorId, entry.productId)
        return TargetTraits(
            keys = entry.keyBits,
            axes = entry.absBits,
            keyLabels = layout.keyLabels,
            axisLabels = layout.axisLabels,
        )
    }

    // The real pad keeps its /proc entry — bitmasks included — even while its /dev node is hidden,
    // which is exactly when seeding runs.
    override fun sourceTraits(key: MappingKey): TargetTraits? {
        val entry = readProcEntries().firstOrNull {
            it.vendorId !in MIRRORING_QUIRK_VENDOR_IDS &&
                it.vendorId != 0 &&
                MappingKey.of(it.vendorId, it.productId) == key
        } ?: return null
        if (entry.keyBits.isEmpty() && entry.absBits.isEmpty()) {
            return null
        }
        return TargetTraits(keys = entry.keyBits, axes = entry.absBits)
    }

    private fun readProcEntries(): List<ProcInputEntry> =
        shell.read("cat /proc/bus/input/devices")
            .split(PROC_BLOCK_SEPARATOR)
            .mapNotNull { parseProcInputBlock(it) }

    override fun getConnectedControllers(): List<ControllerDevice> {
        val procEntries = readProcEntries()

        val mirroredNames = computeMirroredNames(procEntries)

        val candidates = InputDevice.getDeviceIds()
            .asSequence()
            .mapNotNull { id -> InputDevice.getDevice(id) }
            .filter { device -> isPhysicalGameController(device) }
            .map { it.toCandidate() }
            .toList()

        // Matching by name sends two identically named pads to the same node, and the distinctBy
        // below then drops one. Only then is the input service asked which node each device really
        // reads, so the common case costs no extra privileged call.
        val sharedName = candidates.groupBy { it.name.lowercase() }.any { it.value.size > 1 }
        val knownPaths = if (sharedName) parseDumpsysInputPaths(shell.read("dumpsys input")) else emptyMap()

        val framework = candidates
            .mapNotNull { candidate ->
                resolveControllerDevice(candidate, procEntries, mirroredNames, knownPaths[candidate.id])
            }
            .distinctBy { it.path }
            .toList()
            .let(::dropForeignHideNodes)

        // The hidden source is the external pad being mirrored, so it never takes part in choosing
        // the internal one. It used to: rebuilt with controller number 0 it sorted first, and on a
        // Thor it shares the built-in pad's 2020:0111 guid, so a manual pick of the built-in pad
        // matched it instead and the mirror swapped source and target every few seconds.
        val internalResolved = resolveInternalController(framework.sortedBy { it.controllerNumber }, manualInternalGuidProvider())
        return (internalResolved + hiddenSourceDevice(framework, procEntries)).sortedBy { it.controllerNumber }
    }

    // When the hide-external feature has unlinked the mirror source's /dev node, the framework drops
    // it from enumeration but its /proc entry persists. Rebuild a ControllerDevice from that entry so
    // the external is still shown and still treated as present (it hides itself, so hideNodePath is
    // its own path). Returns empty when the source isn't hidden (present in /dev, already resolved)
    // or its /proc entry is gone (controller actually disconnected).
    internal fun hiddenSourceDevice(
        resolved: List<ControllerDevice>,
        procEntries: List<ProcInputEntry>,
    ): List<ControllerDevice> {
        if (!mirrorRunningProvider()) {
            return emptyList()
        }
        val hiddenSource = hiddenSourcePathProvider() ?: return emptyList()
        if (resolved.any { it.path == hiddenSource } || pathExists(hiddenSource)) {
            return emptyList()
        }
        val entry = procEntries.firstOrNull { it.path == hiddenSource } ?: return emptyList()
        return listOf(
            ControllerDevice(
                name = entry.name,
                path = entry.path,
                guid = guidOf(entry.vendorId, entry.productId),
                controllerNumber = 0,
                handlers = entry.handlers,
                isInternal = false,
                isKnownInternal = false,
                hideNodePath = entry.path,
                // The mirror is running, so this is the path the wizard sees the pad through.
                mappingKey = resolveMappingKey(entry.name, procEntries),
            ),
        )
    }

    // Decides which controller is the internal one:
    //  1. The user's manual pick wins, if its controller is connected — even over a recognised
    //     signature, so the auto-detection can always be overridden just in case.
    //  2. Otherwise a recognised handheld signature (Odin).
    //  3. Otherwise the first detected controller, assumed to be the always-attached built-in.
    internal fun resolveInternalController(
        devices: List<ControllerDevice>,
        manualGuid: String?,
    ): List<ControllerDevice> {
        if (devices.isEmpty()) {
            return devices
        }

        // Several devices can share a guid (it is only vendor:product, and the Thor's firmware gives
        // an external pad the built-in pad's), so a recognised built-in pad wins among them.
        val manualMatch = manualGuid?.let { guid ->
            devices.filter { it.guid == guid }.maxByOrNull { it.isKnownInternal }
        }
        if (manualMatch != null) {
            return devices.map { device -> device.copy(isInternal = device.path == manualMatch.path) }
        }

        if (devices.any { it.isKnownInternal }) {
            return devices
        }

        val first = devices.first()
        return devices.map { device ->
            if (device.path == first.path) device.copy(isInternal = true) else device
        }
    }

    // Some handhelds (e.g. the Odin) re-expose any active controller as a virtual HID node
    // carrying the handheld's own vendor id, for game-compatibility reasons. Such a mirrored
    // node always has a "twin" proc entry with the same name on a real, non-quirk vendor id
    // (e.g. the actual Bluetooth identity). The genuine internal controller has no such twin,
    // so this distinguishes it from a mirrored external controller. Only relevant when a
    // mirroring-quirk device is actually present; otherwise there is nothing to disambiguate.
    internal fun computeMirroredNames(procEntries: List<ProcInputEntry>): Set<String> {
        val hasQuirkDevice = procEntries.any { it.vendorId in MIRRORING_QUIRK_VENDOR_IDS }
        if (!hasQuirkDevice) {
            return emptySet()
        }
        return procEntries
            .filter { it.vendorId !in MIRRORING_QUIRK_VENDOR_IDS }
            .map { it.name.lowercase() }
            .toSet()
    }

    /**
     * The identity a saved mapping is keyed on.
     *
     * The device we resolve for an external controller is the firmware's quirk twin, and every twin
     * carries the same vendor:product — so keying a mapping on it would hand the second pad the
     * first pad's mapping. The real controller keeps its own /proc entry under the same name (its
     * /dev node is gone, which is why it is filtered out everywhere else), and that entry has its
     * true vendor:product. Returns null when no such entry exists, which is the normal case for the
     * internal controller.
     */
    internal fun resolveMappingKey(name: String, entries: List<ProcInputEntry>): MappingKey? {
        val real = entries.firstOrNull { entry ->
            entry.vendorId !in MIRRORING_QUIRK_VENDOR_IDS &&
                entry.vendorId != 0 &&
                entry.name.lowercase() == name.lowercase()
        } ?: return null
        return MappingKey.of(real.vendorId, real.productId)
    }

    internal fun resolveControllerDevice(
        candidate: ControllerCandidate,
        entries: List<ProcInputEntry>,
        mirroredNames: Set<String>,
        // The node the input service says this device reads, when we asked. Beats any name match.
        knownPath: String? = null,
    ): ControllerDevice? {
        val normalizedName = candidate.name.lowercase()
        val candidates = entries.filter { entry ->
            if (!pathExists(entry.path)) {
                return@filter false
            }

            val sameVendorProduct =
                candidate.vendorId != 0 &&
                    candidate.productId != 0 &&
                    candidate.vendorId !in MIRRORING_QUIRK_VENDOR_IDS &&
                    entry.vendorId !in MIRRORING_QUIRK_VENDOR_IDS &&
                    entry.vendorId == candidate.vendorId &&
                    entry.productId == candidate.productId
            val sameName = entry.name.lowercase() == normalizedName
            sameVendorProduct || sameName
        }

        // The node the input service named wins outright, but only among nodes that pass the checks
        // above: narrowing to it first dropped the pad entirely whenever that node was a sibling
        // (a motion sensor, say) that failed them.
        return (candidates.filter { it.path == knownPath }.ifEmpty { candidates })
            .sortedWith(
                compareByDescending<ProcInputEntry> {
                    candidate.vendorId !in MIRRORING_QUIRK_VENDOR_IDS &&
                        it.vendorId == candidate.vendorId &&
                        it.productId == candidate.productId
                }
                    .thenByDescending { it.bus == BUS_BLUETOOTH }
                    .thenByDescending { it.bus == BUS_USB && it.vendorId !in MIRRORING_QUIRK_VENDOR_IDS }
                    .thenBy { it.isInternalControllerSignature }
                    .thenBy { it.eventNumber }
            )
            .firstOrNull()
            ?.let { entry ->
                val isInternal = entry.isInternalControllerSignature && entry.name.lowercase() !in mirroredNames
                ControllerDevice(
                    name = entry.name,
                    path = entry.path,
                    guid = candidate.guid,
                    controllerNumber = candidate.controllerNumber,
                    handlers = entry.handlers,
                    isInternal = isInternal,
                    isKnownInternal = isInternal,
                    hideNodePath = resolveHideNodePath(isInternal, normalizedName, entries),
                    // Only an external controller gets one: the internal pad IS the quirk vendor, so
                    // it has no separate real entry to point at, and it is never the one remapped.
                    mappingKey = if (isInternal) null else resolveMappingKey(entry.name, entries),
                )
            }
    }

    // The framework surfaces an external controller as the Odin quirk (vendor 0x2020) node bearing
    // its name — which may ALSO be the node the mirror reads as source when the firmware exposes
    // only the re-exposed node in /dev. Either way that is the node the hide-external feature
    // unlinks. Returns it (present in /dev) for an external controller; null for the internal one
    // (never hide the mirror target) and when no such node exists.
    private fun resolveHideNodePath(
        isInternal: Boolean,
        normalizedName: String,
        entries: List<ProcInputEntry>,
    ): String? {
        if (isInternal) {
            return null
        }
        return entries.firstOrNull { entry ->
            entry.vendorId in MIRRORING_QUIRK_VENDOR_IDS &&
                entry.name.lowercase() == normalizedName &&
                pathExists(entry.path)
        }?.path
    }

    internal fun parseProcInputBlock(block: String): ProcInputEntry? {
        val identityMatch = PROC_IDENTITY.find(block)
            ?: return null
        val name = PROC_NAME.find(block)?.groupValues?.getOrNull(1) ?: return null
        val handlersLine = PROC_HANDLERS.find(block)?.groupValues?.getOrNull(1) ?: return null
        val eventName = PROC_EVENT_NAME.find(handlersLine)?.value ?: return null

        return ProcInputEntry(
            name = name,
            handlers = handlersLine.split(WHITESPACE).filter { it.isNotBlank() },
            path = "/dev/input/$eventName",
            eventName = eventName,
            bus = identityMatch.groupValues[1].toInt(16),
            vendorId = identityMatch.groupValues[2].toInt(16),
            productId = identityMatch.groupValues[3].toInt(16),
            keyBits = capabilityBits(block, PROC_KEY_BITS),
            absBits = capabilityBits(block, PROC_ABS_BITS),
        )
    }

    private fun capabilityBits(block: String, pattern: Regex): Set<Int> =
        pattern.find(block)?.groupValues?.getOrNull(1)
            ?.let(::parseCapabilityBits)
            ?: emptySet()

    private fun isPhysicalGameController(device: InputDevice?): Boolean {
        device ?: return false
        if (device.isVirtual) return false

        val sources = device.sources
        val hasControllerSource =
            sources.hasSource(InputDevice.SOURCE_GAMEPAD) ||
                sources.hasSource(InputDevice.SOURCE_JOYSTICK)
        if (!hasControllerSource) return false

        val hasControllerButtons = device.hasKeys(*CONTROLLER_BUTTONS).any { it }
        val hasControllerAxes = device.motionRanges.any { range ->
            CONTROLLER_AXES.contains(range.axis)
        }
        return hasControllerButtons || hasControllerAxes
    }

    internal data class ProcInputEntry(
        val name: String,
        val handlers: List<String>,
        val path: String,
        val eventName: String,
        val bus: Int,
        val vendorId: Int,
        val productId: Int,
        val keyBits: Set<Int> = emptySet(),
        val absBits: Set<Int> = emptySet(),
    ) {
        val eventNumber: Int
            get() = eventName.removePrefix("event").toIntOrNull() ?: Int.MAX_VALUE

        val isInternalControllerSignature: Boolean
            get() = matchedInternalSignature(vendorId, productId) != null
    }
}

internal data class ControllerCandidate(
    val name: String,
    // InputDevice.getId(), the key into parseDumpsysInputPaths.
    val id: Int = -1,
    val vendorId: Int,
    val productId: Int,
    val controllerNumber: Int,
    val guid: String,
)

private fun InputDevice.toCandidate(): ControllerCandidate = ControllerCandidate(
    name = name,
    id = id,
    vendorId = vendorId,
    productId = productId,
    controllerNumber = controllerNumber,
    guid = getGuid(),
)

private fun InputDevice.getGuid(): String = guidOf(vendorId, productId)

// Controller GUID from vendor + product only — matches InputDevice.getGuid() so a device
// re-materialised from /proc keeps the same identity the framework had assigned it.
internal fun guidOf(vendorId: Int, productId: Int): String = String.format("%016x%016x", productId, vendorId)

private fun Int.hasSource(source: Int): Boolean = this and source == source

/**
 * Framework input device id -> the /dev node it reads, from `dumpsys input`.
 *
 * Two sections are joined. "Event Hub State" lists every node by EventHub id with its `Path:`;
 * "Input Reader State" lists every framework device (`Device N:`) and, from Android 11, the EventHub
 * ids behind it (`EventHub Devices: [ 3 ]`). Before Android 11 the two ids are the same number, so a
 * device without that line is looked up under its own id. Anything unparseable just leaves the device
 * out, and the caller falls back to matching by name.
 */
internal fun parseDumpsysInputPaths(dump: String): Map<Int, String> {
    val hubPaths = mutableMapOf<Int, String>()
    val readerHub = mutableMapOf<Int, Int>()
    var section = ""
    var hubId: Int? = null
    var readerId: Int? = null
    for (line in dump.lineSequence()) {
        if (line.isNotEmpty() && !line[0].isWhitespace()) {
            section = line.trim()
            continue
        }
        if (section.startsWith("Event Hub State")) {
            DUMPSYS_HUB_ID.find(line)?.let { hubId = it.groupValues[1].toInt() }
            DUMPSYS_HUB_PATH.find(line)?.let { match ->
                hubId?.let { hubPaths[it] = match.groupValues[1] }
            }
        } else if (section.startsWith("Input Reader State")) {
            DUMPSYS_READER_DEVICE.find(line)?.let {
                val id = it.groupValues[1].toInt()
                readerId = id
                readerHub[id] = id
            }
            DUMPSYS_READER_HUBS.find(line)?.let { match ->
                readerId?.let { readerHub[it] = match.groupValues[1].toInt() }
            }
        }
    }
    return readerHub.mapNotNull { (reader, hub) -> hubPaths[hub]?.let { reader to it } }.toMap()
}

/**
 * The hide node is found by name, so with two identically named pads one can be handed the other's
 * node, and mirroring it would hide the wrong controller. A node that is another device's own path
 * belongs to that device: drop it. The pad left without one stays visible while mirrored (a phantom
 * player 2), which beats making a real pad vanish.
 */
internal fun dropForeignHideNodes(devices: List<ControllerDevice>): List<ControllerDevice> {
    val ownPaths = devices.map { it.path }.toSet()
    return devices.map { device ->
        val hide = device.hideNodePath
        if (hide != null && hide != device.path && hide in ownPaths) device.copy(hideNodePath = null) else device
    }
}
