package com.controlswitch.domain.repository

import com.controlswitch.domain.model.ControllerDevice
import com.controlswitch.domain.model.MappingKey
import com.controlswitch.domain.model.TargetTraits

interface InputDeviceRepository {
    fun getConnectedControllers(): List<ControllerDevice>

    /**
     * What the mirror's target — the internal pad — declares it can receive, read from the live
     * device. Null when there is no internal pad to inspect; callers fall back to the measured Odin
     * defaults rather than to an empty editor.
     */
    fun targetTraits(): TargetTraits? = null

    /**
     * The capability bitmasks of the REAL external controller behind this mapping key. Same shape as
     * the target's traits, no labels: what matters here is which codes the pad declares, because the
     * controller database's indices only mean anything counted over exactly that set.
     */
    fun sourceTraits(key: MappingKey): TargetTraits? = null

    /** evdev axis code -> Android axis name, from the key layout Android applies to this device. */
    fun axisLabels(vendorId: Int, productId: Int): Map<Int, String> = emptyMap()

    /**
     * Whether enough physical pads are attached for a mirror to be possible at all: the handheld's
     * own and one more. Answered in-process, so the supervisor can ask every tick without a
     * privileged call. Not a substitute for [getConnectedControllers], only a gate in front of it.
     */
    fun controllerPairPresent(): Boolean = true
}
