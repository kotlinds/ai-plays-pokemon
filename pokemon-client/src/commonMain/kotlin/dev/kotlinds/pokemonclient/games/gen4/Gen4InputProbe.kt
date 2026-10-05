package dev.kotlinds.pokemonclient.games.gen4

import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.runtime.InputProbe

/**
 * Which buttons a Generation 4 game has read: `gSystem.heldKeysRaw` (include/system.h, same offset in every Gen 4
 * game), refreshed once per main-loop iteration. Bits are the hardware PAD_* bits (A = 0x1 ... Y = 0x800).
 *
 * @param gSystem the address of `gSystem` in the running ROM.
 */
class Gen4InputProbe(private val gSystem: Long) : InputProbe {

    override fun heldButtons(memory: Memory): Set<Button> {
        val raw = memory.read32(gSystem + Gen4Structs.SYS_HELD_KEYS_RAW).toInt()
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
