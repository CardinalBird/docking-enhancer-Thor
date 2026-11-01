package com.controlswitch.domain.repository

import com.controlswitch.domain.model.PadLayout
import com.controlswitch.domain.model.ControllerMapping
import com.controlswitch.domain.model.MappingKey

/**
 * Where a captured mapping lives between sessions.
 *
 * Keyed by the real controller rather than by the mirror source: this firmware republishes every
 * external pad under one shared vendor:product, so keying on what the mirror opens would hand the
 * second controller the first one's mapping.
 */
interface MappingRepository {
    /** The mapping saved for this controller, or an empty one when it has never been captured. */
    fun get(key: MappingKey): ControllerMapping

    fun save(key: MappingKey, mapping: ControllerMapping)

    /** Forget this controller's mapping, returning it to the daemon's defaults. */
    fun clear(key: MappingKey)

    /**
     * Whether the stored mapping is an untouched database default, as opposed to something the user
     * shaped. The distinction is presentational: a seeded pad just works and needs no callout, while
     * the "custom mapping" badge means the user made it and should only ever mean that.
     */
    fun isSeeded(key: MappingKey): Boolean = false

    fun setSeeded(key: MappingKey, seeded: Boolean) {}

    /** The layout preset last applied to this controller; picks which console's button icons to show. */
    fun layout(key: MappingKey): PadLayout = PadLayout.XBOX

    fun setLayout(key: MappingKey, layout: PadLayout) {}
}
