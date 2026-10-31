package com.odininputmirror.ui

import com.odininputmirror.domain.model.HomeButtonMode
import com.odininputmirror.domain.model.Binding
import com.odininputmirror.domain.model.CaptureKind
import com.odininputmirror.domain.model.CaptureRead
import com.odininputmirror.domain.model.CaptureResult
import com.odininputmirror.domain.model.ControlRef
import com.odininputmirror.domain.model.ControllerDevice
import com.odininputmirror.domain.model.ControllerMapping
import com.odininputmirror.domain.model.MappingKey
import com.odininputmirror.domain.model.MirrorSettings
import com.odininputmirror.domain.model.MirrorStartRequest
import com.odininputmirror.domain.model.PadLayout
import com.odininputmirror.domain.model.mappingSlots
import com.odininputmirror.domain.model.odinFallbackTraits
import com.odininputmirror.domain.repository.InputDeviceRepository
import com.odininputmirror.domain.repository.MappingRepository
import com.odininputmirror.domain.repository.MirrorProcessRepository
import com.odininputmirror.domain.repository.MirrorSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MappingEditorControllerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun installMainDispatcher() {
        // The controller hops to Main to touch UI state. Unconfined rather than a scheduled test
        // dispatcher because the code under test runs on real threads here: what is being verified
        // is the ordering between coroutines, so nothing may be virtualised away.
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    /**
     * The capture used to be opened by one coroutine and polled by another, with nothing ordering
     * them. The poller could therefore read the log — and move its offset past the end of it —
     * before `clearCaptures` had emptied it, so the new slot could be filled by a press left over
     * from the previous one.
     */
    @Test
    fun `the capture is cleared and opened before anything is read`() {
        val process = FakeProcessRepository()
        val controller = controllerWith(process = process)

        controller.openAndAwait()
        controller.bind(slots.first())

        awaitUntil("no read was ever attempted") { "readCaptures" in process.calls }

        val firstRead = process.calls.indexOf("readCaptures")
        assertTrue(
            "read at $firstRead before the log was cleared (${process.calls})",
            process.calls.indexOf("clearCaptures").let { it in 0 until firstRead },
        )
        assertTrue(
            "read at $firstRead before the daemon was listening (${process.calls})",
            process.calls.indexOf("beginCapture").let { it in 0 until firstRead },
        )
    }

    /**
     * A control already spoken for is refused rather than stolen, and the daemon has to be reopened
     * because it latches on its first result. That reopen must happen on the polling coroutine: as a
     * separate launch it reset the offset underneath a read that was still in flight.
     */
    @Test
    fun `a refused press reopens the capture without a second reader`() {
        val process = FakeProcessRepository()
        val taken = ControlRef.button(BTN_SOUTH)
        val controller = controllerWith(
            process = process,
            mappings = FakeMappingRepository(stored = ControllerMapping(listOf(Binding(taken, slots[0].target)))),
        )

        controller.openAndAwait()
        // Bind a DIFFERENT slot with the control slot 0 already owns.
        controller.bind(slots[1])
        process.deliver(CaptureResult.Button(BTN_SOUTH))

        awaitUntil("the capture was never reopened") { process.calls.count { it == "beginCapture" } >= 2 }

        assertEquals(
            "the refusal was not surfaced",
            true,
            controller.state.value.notice?.contains(slots[0].label) == true,
        )
        // Still exactly one poll loop: a second reader would show up as interleaved reads.
        assertEquals("the slot was filled despite the refusal", null, controller.state.value.bindings[slots[1].target])
    }

    /**
     * `close()` runs on the screen's own scope, and closing the screen is what cancels it. A cancel
     * landing between the save and the generation bump stored the mapping and told nobody: the badge
     * showed the new mapping while the daemon went on running the old one.
     */
    @Test
    fun `a cancel during the save still advances the generation`() {
        val savingStarted = CountDownLatch(1)
        val releaseSave = CountDownLatch(1)
        val settings = FakeSettingsRepository()
        val mappings = FakeMappingRepository(
            stored = ControllerMapping(
                listOf(
                    Binding(ControlRef.button(BTN_SOUTH), slots[0].target),
                    Binding(ControlRef.button(BTN_EAST), slots[1].target),
                ),
            ),
            onSave = {
                savingStarted.countDown()
                releaseSave.await(5, TimeUnit.SECONDS)
            },
        )
        val controller = controllerWith(mappings = mappings, settings = settings)

        controller.openAndAwait()
        // Leaves one binding, so this is an edit and takes the save path rather than the clear path.
        controller.clear(slots[0])
        controller.close()

        assertTrue("the save never started", savingStarted.await(5, TimeUnit.SECONDS))
        // The screen goes away mid-write — the exact case that used to drop the bump.
        scope.cancel()
        releaseSave.countDown()

        awaitUntil("the mapping was saved but the daemon was never told") { settings.bumps > 0 }
        assertEquals("the mapping itself was lost", 1, mappings.saved?.bindings?.size)
    }

    @Test
    fun `a visit that changes nothing writes nothing`() {
        val settings = FakeSettingsRepository()
        val mappings = FakeMappingRepository(
            stored = ControllerMapping(listOf(Binding(ControlRef.button(BTN_SOUTH), slots[0].target))),
        )
        val controller = controllerWith(mappings = mappings, settings = settings)

        controller.openAndAwait()
        controller.close()

        Thread.sleep(300L)
        assertEquals("a look around bumped the generation", 0, settings.bumps)
        assertEquals("a look around rewrote the mapping", null, mappings.saved)
    }

    @Test
    fun `with the mirror off the app listens instead`() {
        val process = FakeProcessRepository(daemonRunning = false)
        val controller = controllerWith(process = process)

        controller.openAndAwait()
        controller.bind(slots[0])
        awaitUntil("the app was never asked to listen") { controller.state.value.listenInApp }

        controller.captured(ControlRef.button(BTN_EAST))
        assertEquals(ControlRef.button(BTN_EAST), controller.state.value.bindings[slots[0].target])
        assertEquals(null, controller.state.value.capturing)
    }

    @Test
    fun `map all walks every slot, B skips and X stops`() {
        val controller = controllerWith(process = FakeProcessRepository(daemonRunning = false))
        controller.openAndAwait()

        controller.mapAll()
        assertEquals(slots[0], controller.state.value.capturing)
        controller.captured(ControlRef.button(BTN_SOUTH))
        assertEquals("a bind did not move the walk on", slots[1], controller.state.value.capturing)
        assertEquals(1, controller.state.value.walk)

        // Already bound to slot 0: refused, the walk waits on the same slot.
        controller.captured(ControlRef.button(BTN_SOUTH))
        assertEquals(slots[1], controller.state.value.capturing)

        controller.cancelBind()
        assertEquals("B did not skip", slots[2], controller.state.value.capturing)
        assertEquals("a skipped slot was bound", null, controller.state.value.bindings[slots[1].target])

        controller.stopWalk()
        assertEquals(null, controller.state.value.capturing)
        assertEquals(null, controller.state.value.walk)
        assertEquals(1, controller.state.value.bindings.size)
    }

    @Test
    fun `the last slot ends the walk`() {
        val controller = controllerWith(process = FakeProcessRepository(daemonRunning = false))
        controller.openAndAwait()

        controller.mapAll()
        repeat(slots.size) { controller.cancelBind() }
        assertEquals(null, controller.state.value.capturing)
        assertEquals(null, controller.state.value.walk)
    }

    @Test
    fun `a preset replaces the whole mapping and is saved as the user's`() {
        val mappings = FakeMappingRepository(
            stored = ControllerMapping(listOf(Binding(ControlRef.button(400), slots[0].target))),
        )
        val controller = controllerWith(mappings = mappings)
        controller.openAndAwait()

        controller.autoMap(PadLayout.NINTENDO)
        assertEquals(ControlRef.button(BTN_EAST), controller.state.value.bindings[slots[0].target])
        assertEquals(false, controller.state.value.seeded)
        controller.close()

        awaitUntil("the preset was never saved") { mappings.saved != null }
        assertEquals(slots.size - 2, mappings.saved?.bindings?.size)
    }

    // ---- harness ----------------------------------------------------------------------------

    private val slots = mappingSlots(odinFallbackTraits())

    private fun controllerWith(
        mappings: MappingRepository = FakeMappingRepository(),
        process: MirrorProcessRepository = FakeProcessRepository(),
        settings: MirrorSettingsRepository = FakeSettingsRepository(),
    ) = MappingEditorController(
        mappings = mappings,
        devices = FakeDeviceRepository(),
        process = process,
        settings = settings,
        seedDefaultMapping = {},
        scope = scope,
    )

    private fun MappingEditorController.openAndAwait() {
        open("Test pad", MappingKey("045e:028e"))
        awaitUntil("the editor never opened") { state.value.open }
    }

    private fun awaitUntil(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10L)
        }
        throw AssertionError(message)
    }

    private class FakeMappingRepository(
        private val stored: ControllerMapping = ControllerMapping(),
        private val onSave: () -> Unit = {},
    ) : MappingRepository {
        @Volatile
        var saved: ControllerMapping? = null

        @Volatile
        var cleared = false

        override fun get(key: MappingKey): ControllerMapping = saved ?: stored

        override fun save(key: MappingKey, mapping: ControllerMapping) {
            onSave()
            saved = mapping
        }

        override fun clear(key: MappingKey) {
            cleared = true
        }
    }

    private class FakeSettingsRepository : MirrorSettingsRepository {
        @Volatile
        var bumps = 0

        override fun getSettings() = MirrorSettings()
        override fun saveStarted(request: MirrorStartRequest, startedAt: Long) = Unit
        override fun setExpectedRunning(expectedRunning: Boolean) = Unit
        override fun setPlayer1(name: String?, path: String?) = Unit
        override fun setHomeMode(mode: HomeButtonMode) = Unit
        override fun setComboHoldKillAppEnabled(enabled: Boolean) = Unit
        override fun setVirtualMouseEnabled(enabled: Boolean) = Unit
        override fun setAutoMirrorEnabled(enabled: Boolean) = Unit
        override fun setMirrorWhenHandheld(enabled: Boolean) = Unit
        override fun setComboTapHome(enabled: Boolean) = Unit
        override fun setManualInternalController(guid: String?) = Unit
        override fun bumpConfigGeneration() {
            bumps++
        }
    }

    private class FakeDeviceRepository : InputDeviceRepository {
        override fun getConnectedControllers(): List<ControllerDevice> = emptyList()
    }

    /** Records the order of every call, and hands out a press only when a test says so. */
    private class FakeProcessRepository(private val daemonRunning: Boolean = true) : MirrorProcessRepository {
        val calls: List<String> = Collections.synchronizedList(mutableListOf())

        @Volatile
        private var pending: CaptureResult? = null

        fun deliver(result: CaptureResult) {
            pending = result
        }

        private fun record(name: String) {
            (calls as MutableList<String>).add(name)
        }

        override fun readCaptures(offset: Long): CaptureRead {
            record("readCaptures")
            val next = pending ?: return CaptureRead(offset = offset)
            pending = null
            return CaptureRead(results = listOf(next), offset = offset + 1)
        }

        override fun clearCaptures() = record("clearCaptures")

        override fun beginCapture(kind: CaptureKind): Boolean {
            record("beginCapture")
            return daemonRunning
        }

        override fun endCapture(): Boolean {
            record("endCapture")
            return true
        }

        override fun start(request: MirrorStartRequest) = Unit
        override fun stop() = Unit
        override fun isRunning() = true
        override fun clearProcessFiles() = Unit
        override fun healOrphanedHideNodes() = Unit
        override fun applyLiveSettings(settings: MirrorSettings, mapping: ControllerMapping) = true
        override fun appliedConfigGeneration(): Long? = 0L
    }

    private companion object {
        const val BTN_SOUTH = 304
        const val BTN_EAST = 305
    }
}
