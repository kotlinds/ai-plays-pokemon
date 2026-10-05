package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.games.gen4.Gen4Memory

/**
 * Safe typed reads of HeartGold / SoulSilver RAM, shared by the screen decoders: the Gen 4 reads ([Gen4Memory]: every
 * read outside main RAM returns 0 / null) with the HG/SS address table at hand.
 *
 * @param version the address table of the running ROM.
 */
class HgssMemory(memory: Memory, val version: HgssVersion) : Gen4Memory(memory, version.gSystem)

/**
 * One family of screens decoded straight from RAM (battle menus, bag, party, keyboard...). Returns the screen when
 * one of its screens is up, null otherwise. Decoders are tried in order before the generic mapping.
 */
fun interface HgssScreenDecoder {
    fun decode(mem: HgssMemory, state: HgssState): dev.kotlinds.pokemonclient.state.Screen?
}
