package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.runtime.InputProbe
import dev.kotlinds.pokemonclient.hgss.HgssAddresses as A

/**
 * Which buttons HeartGold / SoulSilver has read: `gSystem.heldKeysRaw` (include/system.h), refreshed by the
 * game once per main-loop iteration (every frame or two).
 *
 * Bits are the hardware PAD_* bits (A = 0x1 ... Y = 0x800). Verified live: a D-pad press is registered after
 * 2 frames, and releasing it as soon as it is seen gives exactly one cursor step in menus.
 */
class HgssInputProbe(private val version: HgssVersion) : InputProbe {

    override fun heldButtons(memory: Memory): Set<Button> {
        val raw = memory.read32(version.gSystem + A.SYS_HELD_KEYS_RAW).toInt()
        return PAD_BITS.filterValues { raw and it != 0 }.keys
    }

    private companion object {
        val PAD_BITS = mapOf(
            Button.A to 0x001, Button.B to 0x002, Button.SELECT to 0x004, Button.START to 0x008,
            Button.RIGHT to 0x010, Button.LEFT to 0x020, Button.UP to 0x040, Button.DOWN to 0x080,
            Button.R to 0x100, Button.L to 0x200, Button.X to 0x400, Button.Y to 0x800,
        )
    }
}
