package com.odininputmirror.domain.model

/**
 * The pad locked in with L + R, if it is here. The exact node it was picked on wins, which is what
 * keeps two identical pads apart; the name is the fallback once a reconnect has moved it to a new
 * node, and the name is what survives the firmware republishing the pad under its own vendor id.
 */
fun List<ControllerDevice>.lockedPlayer1(name: String?, path: String?): ControllerDevice? {
    if (name == null) return null
    return firstOrNull { path != null && it.path == path }
        ?: firstOrNull { it.name.equals(name, ignoreCase = true) }
}

/**
 * The external pad to mirror. With a player 1 locked in, only that pad: an absent lock mirrors
 * nothing, so a stray pad can never take over a slot the user assigned. Without a lock, the first
 * external.
 */
fun List<ControllerDevice>.pickExternal(
    local: ControllerDevice?,
    player1Name: String?,
    player1Path: String? = null,
): ControllerDevice? {
    val externals = filter { !it.isInternal && (local == null || !it.isSamePhysicalControllerAs(local)) }
    if (player1Name != null) return externals.lockedPlayer1(player1Name, player1Path)
    return externals.firstOrNull()
}

private fun ControllerDevice.isSamePhysicalControllerAs(other: ControllerDevice): Boolean =
    controllerNumber > 0 && controllerNumber == other.controllerNumber

data class ControllerDevice(
    val name: String,
    val path: String,
    val guid: String,
    val controllerNumber: Int,
    val handlers: List<String> = emptyList(),
    // Effective internal flag used to pick the mirror target (signature, manual, or default).
    val isInternal: Boolean = false,
    // True only when matched by a recognised handheld signature (e.g. Odin); such a pick is
    // locked and not user-editable. Default/manual picks set isInternal but not this.
    val isKnownInternal: Boolean = false,
    // The /dev/input node the hide-external feature unlinks to remove THIS external controller from
    // the framework's device list — the Odin quirk (vendor 0x2020) node bearing this controller's
    // name. It may be a separate re-exposed twin OR the very node the mirror reads as source (when
    // the firmware exposes only the re-exposed node in /dev); either way it is the framework-visible
    // one. Null for the internal controller and when no such node exists.
    val hideNodePath: String? = null,
    // Identity of the REAL controller behind this entry, used to key a saved mapping. [guid] cannot
    // do that job for an external pad: the firmware republishes every one of them under its own
    // vendor:product, so they all share a guid. Null when no distinct real device was found — an
    // internal controller, or a pad the firmware did not republish.
    val mappingKey: MappingKey? = null,
)
