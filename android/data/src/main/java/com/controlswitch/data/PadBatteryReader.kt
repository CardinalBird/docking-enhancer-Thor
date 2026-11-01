package com.controlswitch.data

import com.controlswitch.domain.model.PadBattery
import java.io.File

/**
 * Charge of every connected Switch-protocol pad (Pro Controller, 8BitDo in Switch mode, Joy-Cons),
 * keyed by name.
 *
 * Android has no number for these on the Thor: the app sees the firmware's twin, which has no
 * battery, and the kernel's Nintendo driver registers no power supply for the real pad. The pad
 * still sends its charge in byte 2 of every input report, so this reads the first bytes of one
 * report from each /dev/hidraw node. Those nodes are system-only, hence the root shell.
 */
internal fun readPadBatteries(shell: MirrorShell, script: File): Map<String, PadBattery> {
    if (!shell.isAvailable) return emptyMap()
    // Run from a file, as the daemon launch is: PServer garbles a one-liner this involved and the
    // command never runs. One line per hidraw node: "<input name>|<first three bytes in hex>".
    // `timeout` because a pad that only reports on change would otherwise block the read.
    script.writeText(
        """
        for h in /sys/class/hidraw/hidraw*; do
            n=${'$'}(cat ${'$'}h/device/input/input*/name 2>/dev/null | head -n 1)
            [ -n "${'$'}n" ] || continue
            echo "${'$'}n|${'$'}(timeout 1 head -c 3 /dev/${'$'}{h##*/} 2>/dev/null | od -An -tx1)"
        done
        """.trimIndent() + "\n",
    )
    script.setReadable(true, false)
    val out = shell.read("sh ${script.absolutePath}")
    return parsePadBatteries(out)
}

/**
 * Parses [readPadBatteries]' output. Only Switch input reports (0x21, 0x30, 0x31) carry the charge:
 * the high nibble of byte 2 is level × 2, plus 1 while charging. Anything else is skipped.
 */
internal fun parsePadBatteries(output: String): Map<String, PadBattery> =
    output.lineSequence().mapNotNull { line ->
        val name = line.substringBeforeLast('|', "").trim()
        val bytes = line.substringAfterLast('|').trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull(16) }
        if (name.isEmpty() || bytes.size < 3 || bytes[0] !in SWITCH_REPORTS) return@mapNotNull null
        val nibble = bytes[2] shr 4
        name to PadBattery(level = (nibble shr 1).coerceAtMost(4), charging = nibble and 1 == 1)
    }.toMap()

private val SWITCH_REPORTS = setOf(0x21, 0x30, 0x31)
