package com.odininputmirror.domain.model

data class MirrorSettings(
    val source: String? = null,
    val target: String? = null,
    val sourceGuid: String? = null,
    val targetGuid: String? = null,
    val homeMode: HomeButtonMode = HomeButtonMode.HOME,
    val comboHoldKillApp: Boolean = false,
    val virtualMouse: Boolean = false,
    val autoMirrorEnabled: Boolean = true,
    // Mirror whenever an external pad is connected, docked or not. Off means docked only.
    val mirrorWhenHandheld: Boolean = false,
    // A Start + L3 tap sends Home (for pads without a Guide button). Independent of the
    // 3 s hold that closes the app.
    val comboTapHome: Boolean = false,
    val expectedRunning: Boolean = false,
    val startedAt: Long = 0L,
    val manualInternalGuid: String? = null,
    // The pad locked in as player 1 with L + R; null means the first external pad.
    val player1Name: String? = null,
    // The node it was picked on; tells identical pads apart until a reconnect moves it.
    val player1Path: String? = null,
    // Advances every time a live option changes. The running daemon echoes the generation it has
    // actually adopted in its heartbeat, so comparing the two says whether a toggle has taken
    // effect — which is what lets a settings change reach the daemon without restarting it.
    val configGeneration: Long = 0L,
)
