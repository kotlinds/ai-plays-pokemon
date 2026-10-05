package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.gen4.Gen4InputProbe
import dev.kotlinds.pokemonclient.runtime.InputProbe

/**
 * Which buttons HeartGold / SoulSilver has read: `gSystem.heldKeysRaw` (include/system.h), refreshed by the
 * game once per main-loop iteration (every frame or two).
 *
 * Bits are the hardware PAD_* bits (A = 0x1 ... Y = 0x800). Verified live: a D-pad press is registered after
 * 2 frames, and releasing it as soon as it is seen gives exactly one cursor step in menus.
 */
class HgssInputProbe(version: HgssVersion) : InputProbe by Gen4InputProbe(version.gSystem)
