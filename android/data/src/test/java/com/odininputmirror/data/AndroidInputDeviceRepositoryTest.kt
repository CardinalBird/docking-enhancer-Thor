package com.odininputmirror.data

import com.odininputmirror.domain.model.ControllerDevice
import com.odininputmirror.domain.model.MappingKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ODIN_VENDOR = 0x2020

class AndroidInputDeviceRepositoryTest {
    private val repository = AndroidInputDeviceRepository(pathExists = { true })

    // -- parseProcInputBlock -------------------------------------------------

    @Test
    fun parseProcInputBlockExtractsIdentityNameAndEventPath() {
        val block = """
            I: Bus=0003 Vendor=2020 Product=0112 Version=0000
            N: Name="Xbox Wireless Controller"
            P: Phys=
            S: Sysfs=/devices/virtual/input/input55
            U: Uniq=
            H: Handlers=event7 cpufreq
        """.trimIndent()

        val entry = repository.parseProcInputBlock(block)

        assertEquals("Xbox Wireless Controller", entry?.name)
        assertEquals("/dev/input/event7", entry?.path)
        assertEquals(0x0003, entry?.bus)
        assertEquals(0x2020, entry?.vendorId)
        assertEquals(0x0112, entry?.productId)
    }

    @Test
    fun parseProcInputBlockReturnsNullWhenIdentityLineMissing() {
        val block = """
            N: Name="Orphan device"
            H: Handlers=event3
        """.trimIndent()

        assertNull(repository.parseProcInputBlock(block))
    }

    @Test
    fun parseProcInputBlockReturnsNullWhenNoEventHandler() {
        val block = """
            I: Bus=0003 Vendor=2020 Product=0112 Version=0000
            N: Name="No event handler"
            H: Handlers=cpufreq kgsl
        """.trimIndent()

        assertNull(repository.parseProcInputBlock(block))
    }

    // -- computeMirroredNames -------------------------------------------------

    @Test
    fun computeMirroredNamesIncludesOnlyNonQuirkVendorEntries() {
        val entries = listOf(
            procEntry(name = "8BitDo Ultimate 2C Wireless", bus = BUS_BLUETOOTH, vendorId = 0x2dc8, productId = 0x301b),
            procEntry(name = "8BitDo Ultimate 2C Wireless", bus = BUS_USB, vendorId = ODIN_VENDOR, productId = 0x0111),
            procEntry(name = "Odin Controller", bus = BUS_USB, vendorId = ODIN_VENDOR, productId = 0x0111),
        )

        val mirroredNames = repository.computeMirroredNames(entries)

        assertEquals(setOf("8bitdo ultimate 2c wireless"), mirroredNames)
    }

    @Test
    fun computeMirroredNamesIsEmptyWhenNoMirroringQuirkDevicePresent() {
        // A generic handheld (no Odin-style mirroring quirk): nothing to disambiguate.
        val entries = listOf(
            procEntry(name = "Retro Pad", bus = BUS_USB, vendorId = 0x1234, productId = 0x5678),
            procEntry(name = "8BitDo Ultimate 2C Wireless", bus = BUS_BLUETOOTH, vendorId = 0x2dc8, productId = 0x301b),
        )

        assertTrue(repository.computeMirroredNames(entries).isEmpty())
    }

    // -- resolveControllerDevice: recognised Odin signature ------------------

    @Test
    fun internalControllerInXboxModeIsClassifiedAsInternal() {
        // The internal Odin controller reports a vendor/product that matches a known signature,
        // even though in Xbox mode its proc name is "Xbox Wireless Controller".
        val candidate = ControllerCandidate(
            name = "Xbox Wireless Controller",
            vendorId = ODIN_VENDOR,
            productId = 0x0112,
            controllerNumber = 1,
            guid = "guid-xbox-mode",
        )
        val entries = listOf(
            procEntry(name = "Xbox Wireless Controller", bus = BUS_USB, vendorId = ODIN_VENDOR, productId = 0x0112),
        )

        val result = repository.resolveControllerDevice(candidate, entries, mirroredNames = emptySet())

        assertTrue(result?.isInternal == true)
    }

    @Test
    fun internalControllerInOdinModeIsClassifiedAsInternal() {
        val candidate = ControllerCandidate(
            name = "Odin Controller",
            vendorId = ODIN_VENDOR,
            productId = 0x0111,
            controllerNumber = 1,
            guid = "guid-odin-mode",
        )
        val entries = listOf(
            procEntry(name = "Odin Controller", bus = BUS_USB, vendorId = ODIN_VENDOR, productId = 0x0111),
            // The mouse HID Odin also exposes shares the same vendor/product id but a different name.
            procEntry(name = "ODIN Station Virtual Mouse", bus = BUS_USB, vendorId = ODIN_VENDOR, productId = 0x0111),
        )

        val result = repository.resolveControllerDevice(candidate, entries, mirroredNames = emptySet())

        assertTrue(result?.isInternal == true)
        assertEquals("Odin Controller", result?.name)
    }

    @Test
    fun externalControllerMirroredUnderQuirkVendorIdIsNotClassifiedAsInternal() {
        // Odin re-exposes a connected external controller as a virtual node carrying its own
        // vendor/product id. The mirroredNames guard keeps it from being seen as the internal pad.
        val candidate = ControllerCandidate(
            name = "8BitDo Ultimate 2C Wireless",
            vendorId = ODIN_VENDOR,
            productId = 0x0111,
            controllerNumber = 3,
            guid = "guid-8bitdo",
        )
        val entries = listOf(
            procEntry(name = "8BitDo Ultimate 2C Wireless", bus = BUS_USB, vendorId = ODIN_VENDOR, productId = 0x0111),
        )
        val mirroredNames = setOf("8bitdo ultimate 2c wireless")

        val result = repository.resolveControllerDevice(candidate, entries, mirroredNames)

        assertFalse(result?.isInternal == true)
    }

    @Test
    fun externalControllerWithDistinctVendorIsNotClassifiedAsInternal() {
        val candidate = ControllerCandidate(
            name = "8BitDo Ultimate 2C Wireless",
            vendorId = 0x2dc8,
            productId = 0x301b,
            controllerNumber = 3,
            guid = "guid-8bitdo-bt",
        )
        val entries = listOf(
            procEntry(name = "8BitDo Ultimate 2C Wireless", bus = BUS_BLUETOOTH, vendorId = 0x2dc8, productId = 0x301b),
            procEntry(name = "8BitDo Ultimate 2C Wireless", bus = BUS_USB, vendorId = ODIN_VENDOR, productId = 0x0111),
        )
        val mirroredNames = repository.computeMirroredNames(entries)

        val result = repository.resolveControllerDevice(candidate, entries, mirroredNames)

        assertFalse(result?.isInternal == true)
        assertEquals(BUS_BLUETOOTH, entries.first { it.path == result?.path }.bus)
    }

    @Test
    fun externalControllerResolvesItsOdinQuirkTwinPath() {
        // Real BT node + the Odin re-exposed twin. The resolved device is the real node; its twin
        // (quirk vendor 0x2020, same name) is what the hide-external feature unlinks.
        val candidate = ControllerCandidate(
            name = "8BitDo Ultimate 2C Wireless",
            vendorId = 0x2dc8,
            productId = 0x301b,
            controllerNumber = 3,
            guid = "guid-8bitdo-bt",
        )
        val entries = listOf(
            procEntry(name = "8BitDo Ultimate 2C Wireless", bus = BUS_BLUETOOTH, vendorId = 0x2dc8, productId = 0x301b, eventName = "event9"),
            procEntry(name = "8BitDo Ultimate 2C Wireless", bus = BUS_USB, vendorId = ODIN_VENDOR, productId = 0x0111, eventName = "event10"),
        )
        val mirroredNames = repository.computeMirroredNames(entries)

        val result = repository.resolveControllerDevice(candidate, entries, mirroredNames)

        assertEquals("/dev/input/event9", result?.path)
        assertEquals("/dev/input/event10", result?.hideNodePath)
    }

    @Test
    fun externalWithoutRealDevNodeHidesTheReExposedNodeItself() {
        // The firmware exposes only the Odin re-exposed node in /dev (the real BT node has no /dev
        // entry). The mirror source IS the quirk node, and that same node is the hide target.
        val repoNoRealDev = AndroidInputDeviceRepository(pathExists = { it != "/dev/input/event9" })
        val candidate = ControllerCandidate(
            name = "8BitDo Ultimate 2C Wireless",
            vendorId = ODIN_VENDOR,
            productId = 0x0111,
            controllerNumber = 3,
            guid = "guid-8bitdo",
        )
        val entries = listOf(
            procEntry(name = "8BitDo Ultimate 2C Wireless", bus = BUS_BLUETOOTH, vendorId = 0x2dc8, productId = 0x301b, eventName = "event9"),
            procEntry(name = "8BitDo Ultimate 2C Wireless", bus = BUS_USB, vendorId = ODIN_VENDOR, productId = 0x0111, eventName = "event10"),
        )
        val mirroredNames = repoNoRealDev.computeMirroredNames(entries)

        val result = repoNoRealDev.resolveControllerDevice(candidate, entries, mirroredNames)

        assertEquals("/dev/input/event10", result?.path)
        assertEquals("/dev/input/event10", result?.hideNodePath)
        assertFalse(result?.isInternal == true)
    }

    // -- hiddenSourceDevice (re-materialise a hidden mirror source from /proc) -----------

    @Test
    fun hiddenSourceIsRematerialisedWhenRunningAndItsDevNodeIsGone() {
        val repo = AndroidInputDeviceRepository(
            pathExists = { false }, // the source's /dev node was unlinked (hidden)
            hiddenSourcePathProvider = { "/dev/input/event10" },
            mirrorRunningProvider = { true },
        )
        val entries = listOf(
            procEntry(name = "8BitDo Ultimate 2C Wireless", bus = BUS_USB, vendorId = ODIN_VENDOR, productId = 0x0111, eventName = "event10"),
        )

        val result = repo.hiddenSourceDevice(resolved = emptyList(), procEntries = entries)

        assertEquals(1, result.size)
        assertEquals("/dev/input/event10", result[0].path)
        assertEquals("/dev/input/event10", result[0].hideNodePath)
        assertEquals(guidOf(ODIN_VENDOR, 0x0111), result[0].guid)
        assertFalse(result[0].isInternal)
    }

    @Test
    fun hiddenSourceNotRematerialisedWhenMirrorNotRunning() {
        // A stale persisted source (its /dev node absent) must NOT be revived while nothing runs —
        // otherwise it blocks restarting on the live node after a reconnect.
        val repo = AndroidInputDeviceRepository(
            pathExists = { false },
            hiddenSourcePathProvider = { "/dev/input/event10" },
            mirrorRunningProvider = { false },
        )
        val entries = listOf(
            procEntry(name = "8BitDo Ultimate 2C Wireless", bus = BUS_USB, vendorId = ODIN_VENDOR, productId = 0x0111, eventName = "event10"),
        )

        assertTrue(repo.hiddenSourceDevice(resolved = emptyList(), procEntries = entries).isEmpty())
    }

    @Test
    fun hiddenSourceNotRematerialisedWhenDevNodeStillPresent() {
        val repo = AndroidInputDeviceRepository(
            pathExists = { true }, // node present → not hidden, framework already has it
            hiddenSourcePathProvider = { "/dev/input/event10" },
            mirrorRunningProvider = { true },
        )
        val entries = listOf(
            procEntry(name = "Pad", bus = BUS_USB, vendorId = ODIN_VENDOR, productId = 0x0111, eventName = "event10"),
        )

        assertTrue(repo.hiddenSourceDevice(resolved = emptyList(), procEntries = entries).isEmpty())
    }

    @Test
    fun hiddenSourceNotRematerialisedWhenAlreadyResolved() {
        val repo = AndroidInputDeviceRepository(
            pathExists = { false },
            hiddenSourcePathProvider = { "/dev/input/event10" },
            mirrorRunningProvider = { true },
        )
        val entries = listOf(
            procEntry(name = "Pad", bus = BUS_USB, vendorId = ODIN_VENDOR, productId = 0x0111, eventName = "event10"),
        )
        val already = listOf(ControllerDevice(name = "Pad", path = "/dev/input/event10", guid = "g", controllerNumber = 1))

        assertTrue(repo.hiddenSourceDevice(resolved = already, procEntries = entries).isEmpty())
    }

    @Test
    fun internalControllerHasNoQuirkTwinPath() {
        // The chosen node is itself the quirk-vendor one → there is no separate twin to hide.
        val candidate = ControllerCandidate(
            name = "Xbox Wireless Controller",
            vendorId = ODIN_VENDOR,
            productId = 0x0112,
            controllerNumber = 1,
            guid = "guid-xbox",
        )
        val entries = listOf(
            procEntry(name = "Xbox Wireless Controller", bus = BUS_USB, vendorId = ODIN_VENDOR, productId = 0x0112, eventName = "event7"),
        )

        val result = repository.resolveControllerDevice(candidate, entries, mirroredNames = emptySet())

        assertNull(result?.hideNodePath)
    }

    @Test
    fun unknownHandheldInternalPadIsNotClassifiedAsInternalBySignature() {
        // A non-Odin handheld: its built-in pad uses an unrecognised vendor, so no signature
        // matches and it is NOT auto-flagged internal. Such devices rely on manual selection.
        val candidate = ControllerCandidate(
            name = "Anbernic Gamepad",
            vendorId = 0x1234,
            productId = 0x0001,
            controllerNumber = 1,
            guid = "guid-anbernic",
        )
        val entries = listOf(
            procEntry(name = "Anbernic Gamepad", bus = BUS_USB, vendorId = 0x1234, productId = 0x0001),
        )

        val result = repository.resolveControllerDevice(candidate, entries, mirroredNames = emptySet())

        assertEquals("Anbernic Gamepad", result?.name)
        assertFalse(result?.isInternal == true)
    }

    @Test
    fun resolveControllerDeviceReturnsNullWhenNoCandidateMatches() {
        val candidate = ControllerCandidate(
            name = "Unknown Pad",
            vendorId = 0x9999,
            productId = 0x5678,
            controllerNumber = 2,
            guid = "guid-unknown",
        )
        val entries = listOf(
            procEntry(name = "Odin Controller", bus = BUS_USB, vendorId = ODIN_VENDOR, productId = 0x0111),
        )

        val result = repository.resolveControllerDevice(candidate, entries, mirroredNames = emptySet())

        assertNull(result)
    }

    // -- resolveInternalController -------------------------------------------

    @Test
    fun internalDefaultsToFirstDetectedControllerOnUnknownDeviceWithNoManualPick() {
        // Non-Odin handheld: the first detected controller is assumed to be the built-in one.
        val devices = listOf(
            device(name = "Anbernic Gamepad", guid = "guid-builtin"),
            device(name = "8BitDo", guid = "guid-external"),
        )

        val result = repository.resolveInternalController(devices, manualGuid = null)

        assertTrue(result.first { it.guid == "guid-builtin" }.isInternal)
        assertFalse(result.first { it.guid == "guid-external" }.isInternal)
    }

    @Test
    fun manualPickOverridesTheDefaultFirstControllerOnUnknownDevice() {
        val devices = listOf(
            device(name = "Anbernic Gamepad", guid = "guid-builtin"),
            device(name = "8BitDo", guid = "guid-external"),
        )

        val result = repository.resolveInternalController(devices, manualGuid = "guid-external")

        assertTrue(result.first { it.guid == "guid-external" }.isInternal)
        assertFalse(result.first { it.guid == "guid-builtin" }.isInternal)
    }

    @Test
    fun manualPickFallsBackToFirstWhenChosenControllerIsDisconnected() {
        val devices = listOf(device(name = "Anbernic Gamepad", guid = "guid-builtin"))

        val result = repository.resolveInternalController(devices, manualGuid = "guid-gone")

        assertTrue(result.single().isInternal)
    }

    @Test
    fun signatureInternalIsUsedWhenNoManualPick() {
        // On Odin the signature internal is the default when the user has not overridden it.
        val devices = listOf(
            device(name = "Odin Controller", guid = "guid-odin", isInternal = true, isKnownInternal = true),
            device(name = "8BitDo", guid = "guid-external"),
        )

        val result = repository.resolveInternalController(devices, manualGuid = null)

        assertEquals(devices, result)
    }

    @Test
    fun manualPickOverridesEvenARecognisedSignatureInternal() {
        // The user can override auto-detection on Odin too, just in case it picked wrong.
        val devices = listOf(
            device(name = "Odin Controller", guid = "guid-odin", isInternal = true, isKnownInternal = true),
            device(name = "8BitDo", guid = "guid-external"),
        )

        val result = repository.resolveInternalController(devices, manualGuid = "guid-external")

        assertTrue(result.first { it.guid == "guid-external" }.isInternal)
        assertFalse(result.first { it.guid == "guid-odin" }.isInternal)
    }

    private fun device(
        name: String,
        guid: String,
        isInternal: Boolean = false,
        isKnownInternal: Boolean = false,
    ) = ControllerDevice(
        name = name,
        path = "/dev/input/$guid",
        guid = guid,
        controllerNumber = 0,
        handlers = emptyList(),
        isInternal = isInternal,
        isKnownInternal = isKnownInternal,
    )

    @Test
    fun mappingKeyComesFromTheRealDeviceNotTheFirmwaresTwin() {
        // The firmware republishes the pad as a 0x2020 twin and deletes the original node. Both
        // entries carry the same name; only the real one carries an identity worth keying on.
        val entries = listOf(
            procEntry("8BitDo Ultimate 2C Wireless", BUS_BLUETOOTH, 0x2dc8, 0x301b),
            procEntry("8BitDo Ultimate 2C Wireless", BUS_USB, 0x2020, 0x0111),
        )

        assertEquals(
            MappingKey("2dc8:301b"),
            repository.resolveMappingKey(entries[1], entries),
        )
    }

    @Test
    fun anUntwinnedPadKeepsItsOwnKeyEvenBesideASameNamedPad() {
        // A DS4 and a third-party clone both enumerate as "Wireless Controller". The clone is not
        // twinned, so it resolves to its own entry; a name lookup used to hand it the DS4's key.
        val entries = listOf(
            procEntry("Wireless Controller", BUS_BLUETOOTH, 0x054c, 0x09cc),
            procEntry("Wireless Controller", BUS_BLUETOOTH, 0x1234, 0x5678),
        )

        assertEquals(MappingKey("1234:5678"), repository.resolveMappingKey(entries[1], entries))
    }

    @Test
    fun twoDifferentPadsGetDifferentMappingKeysDespiteSharingAGuid() {
        // The regression this exists to prevent: both twins are 2020:0111, so keying on the twin
        // would hand the second pad the first pad's mapping.
        val entries = listOf(
            procEntry("8BitDo Ultimate 2C Wireless", BUS_BLUETOOTH, 0x2dc8, 0x301b),
            procEntry("8BitDo Ultimate 2C Wireless", BUS_USB, 0x2020, 0x0111),
            procEntry("Xbox Wireless Controller", BUS_BLUETOOTH, 0x045e, 0x0b13),
            procEntry("Xbox Wireless Controller", BUS_USB, 0x2020, 0x0111),
        )

        val first = repository.resolveMappingKey(entries[1], entries)
        val second = repository.resolveMappingKey(entries[3], entries)

        assertEquals(MappingKey("2dc8:301b"), first)
        assertEquals(MappingKey("045e:0b13"), second)
    }

    @Test
    fun mappingKeyIsNullWhenOnlyTheQuirkEntryExists() {
        // The internal controller IS the quirk vendor: there is no real device behind it to key on,
        // and it is never the controller being remapped.
        val entries = listOf(procEntry("Xbox Wireless Controller", BUS_USB, 0x2020, 0x0112))

        assertNull(repository.resolveMappingKey(entries[0], entries))
    }

    private fun procEntry(
        name: String,
        bus: Int,
        vendorId: Int,
        productId: Int,
        eventName: String = "event${entrySequence++}",
    ) = AndroidInputDeviceRepository.ProcInputEntry(
        name = name,
        handlers = listOf(eventName),
        path = "/dev/input/$eventName",
        eventName = eventName,
        bus = bus,
        vendorId = vendorId,
        productId = productId,
    )

    private var entrySequence = 0

    // -- identical pads ------------------------------------------------------

    @Test
    fun parseDumpsysInputPathsJoinsReaderDevicesToTheirNodes() {
        val dump = """
Event Hub State:
  BuiltInKeyboardId: -2
  Devices:
    -1: Virtual
      Classes: KEYBOARD
      Path: <virtual>
    3: 8BitDo Ultimate 2C
      Classes: KEYBOARD | GAMEPAD | JOYSTICK
      Path: /dev/input/event9
    4: 8BitDo Ultimate 2C
      Classes: KEYBOARD | GAMEPAD | JOYSTICK
      Path: /dev/input/event11
Input Reader State (Nums of device: 2):
  Device 7: 8BitDo Ultimate 2C
    EventHub Devices: [ 3 ] 
    Generation: 12
  Device 8: 8BitDo Ultimate 2C
    EventHub Devices: [ 4 ] 
Input Dispatcher State:
  DispatchEnabled: true
""".trimStart()

        assertEquals(mapOf(7 to "/dev/input/event9", 8 to "/dev/input/event11"), parseDumpsysInputPaths(dump))
    }

    // Captured from an AYN Thor (Android 13): framework id 10 reads EventHub device 9.
    @Test
    fun parseDumpsysInputPathsReadsARealThorDump() {
        val dump = javaClass.classLoader!!.getResource("dumpsys-input-thor.txt")!!.readText()

        val paths = parseDumpsysInputPaths(dump)

        assertEquals("/dev/input/event9", paths[10])
        assertTrue(paths.values.all { it.startsWith("/dev/input/event") })
    }

    @Test
    fun sourceTraitsPreferThePadNodeOverItsSensorNode() {
        val proc = """
I: Bus=0005 Vendor=054c Product=0ce6 Version=0001
N: Name="DualSense Wireless Controller Motion Sensors"
H: Handlers=event12
B: KEY=
B: ABS=3f

I: Bus=0005 Vendor=054c Product=0ce6 Version=0001
N: Name="DualSense Wireless Controller"
H: Handlers=event11 js0
B: KEY=7fdb000000000000 0 0 0 0
B: ABS=3003f
""".trimStart()
        val shell = object : MirrorShell by UnavailableShell {
            override fun read(command: String): String = proc
        }
        val repository = AndroidInputDeviceRepository(shell = shell, pathExists = { true })

        val traits = repository.sourceTraits(MappingKey.of(0x054c, 0x0ce6))!!

        assertTrue(traits.keys.isNotEmpty())
    }

    @Test
    fun parseDumpsysInputPathsUsesTheSameIdBeforeAndroid11() {
        val dump = """
Event Hub State:
  Devices:
    5: Pad
      Path: /dev/input/event5
Input Reader State:
  Device 5: Pad
    Generation: 3
""".trimStart()

        assertEquals(mapOf(5 to "/dev/input/event5"), parseDumpsysInputPaths(dump))
    }

    @Test
    fun knownPathKeepsIdenticallyNamedPadsOnTheirOwnNodes() {
        fun entry(event: String) = AndroidInputDeviceRepository.ProcInputEntry(
            name = "8BitDo Ultimate 2C",
            handlers = listOf(event),
            path = "/dev/input/$event",
            eventName = event,
            bus = 0x0005,
            vendorId = ODIN_VENDOR,
            productId = 0x0112,
        )
        val entries = listOf(entry("event9"), entry("event11"))
        fun candidate(id: Int) = ControllerCandidate(
            name = "8BitDo Ultimate 2C",
            id = id,
            vendorId = ODIN_VENDOR,
            productId = 0x0112,
            controllerNumber = id,
            guid = "guid",
        )

        val first = repository.resolveControllerDevice(candidate(7), entries, emptySet(), "/dev/input/event9")
        val second = repository.resolveControllerDevice(candidate(8), entries, emptySet(), "/dev/input/event11")
        val unknown = repository.resolveControllerDevice(candidate(8), entries, emptySet(), null)

        assertEquals("/dev/input/event9", first?.path)
        assertEquals("/dev/input/event11", second?.path)
        // Without the dump it falls back to today's pick: the lowest node.
        assertEquals("/dev/input/event9", unknown?.path)
    }

    // Seen on a Thor: the firmware twins only one pad, so with two "Sim Pad"s the untwinned one was
    // given the twinned one's node and mirroring it hid the other controller.
    @Test
    fun dropForeignHideNodesNeverHidesAnotherPad() {
        val twinned = ControllerDevice("Sim Pad", "/dev/input/event12", "g", 1, hideNodePath = "/dev/input/event12")
        val untwinned = ControllerDevice("Sim Pad", "/dev/input/event14", "g", 2, hideNodePath = "/dev/input/event12")
        val solo = ControllerDevice("Other", "/dev/input/event15", "g", 3, hideNodePath = "/dev/input/event16")

        val result = dropForeignHideNodes(listOf(twinned, untwinned, solo))

        assertEquals("/dev/input/event12", result[0].hideNodePath)
        assertNull(result[1].hideNodePath)
        assertEquals("/dev/input/event16", result[2].hideNodePath)
    }

    // On a Thor the built-in pad and the external pad's twin are both 2020:0111, so they share a
    // guid. A manual pick of that guid must land on the built-in one.
    @Test
    fun manualPickOfASharedGuidPrefersTheRecognisedBuiltInPad() {
        val twin = ControllerDevice("Pro Controller", "/dev/input/event12", "shared", 0)
        val builtIn = ControllerDevice("Odin Controller", "/dev/input/event9", "shared", 1, isKnownInternal = true)

        val result = repository.resolveInternalController(listOf(twin, builtIn), manualGuid = "shared")

        assertTrue(result.single { it.path == "/dev/input/event9" }.isInternal)
        assertFalse(result.single { it.path == "/dev/input/event12" }.isInternal)
    }
}
