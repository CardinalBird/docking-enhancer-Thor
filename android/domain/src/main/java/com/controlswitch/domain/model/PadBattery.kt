package com.controlswitch.domain.model

/**
 * A Switch-protocol pad's charge as the pad itself reports it: [level] 0 (empty) to 4 (full), no
 * finer. Read from its raw reports because the Thor's kernel publishes no battery entry for it.
 */
data class PadBattery(val level: Int, val charging: Boolean)
