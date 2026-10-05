package dev.kotlinds.pokemonclient.gen4

import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.runtime.InputProbe

/**
 * Base of the Generation 4 games (Diamond / Pearl / Platinum, HeartGold / SoulSilver): what their shared engine gives
 * every one of them, with the per-ROM addresses injected. The generic contract stays [PokemonGame]; the games
 * themselves ([dev.kotlinds.pokemonclient.hgss.HgssGame], [dev.kotlinds.pokemonclient.platinum.PlatinumGame]) decode
 * their own screens.
 *
 * @param gSystem the address of `gSystem` in the running ROM.
 */
abstract class Gen4Game(gSystem: Long) : PokemonGame {

    /** `gSystem.heldKeysRaw`, same structure in every Gen 4 game ([Gen4InputProbe]). */
    override val inputProbe: InputProbe = Gen4InputProbe(gSystem)

    /** ITEM_BICYCLE: the same item id (450) in every Gen 4 game. */
    override val bicycleItem: Int? get() = ITEM_BICYCLE

    private companion object {
        const val ITEM_BICYCLE = 450
    }
}
