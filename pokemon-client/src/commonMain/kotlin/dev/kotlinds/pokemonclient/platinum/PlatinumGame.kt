package dev.kotlinds.pokemonclient.platinum

import dev.kotlinds.NdsRom
import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.gen4.Gen4Game
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.Screen

/**
 * [dev.kotlinds.pokemonclient.PokemonGame] for Pokémon Platinum (early support): the intro (opening movie, title
 * screen, main menu, Professor Rowan's new game with its menus, the naming keyboard and the TV programme), the
 * player's position, walking and the scripts' messages in the field, and the map of the current zone from the ROM.
 * Party, bag, battles, menus of the field are not decoded yet ([Screen.Unknown]).
 *
 * Built on the Gen 4 layer ([Gen4Game]: input probe, safe reads, text, naming keyboard, map formats); only the
 * addresses ([PlatinumVersion]) and the screens that differ from HGSS are Platinum's own.
 */
class PlatinumGame(private val version: PlatinumVersion, rom: NdsRom? = null) : Gen4Game(version.gSystem) {

    override val name: String = version.displayName

    /** The maps of the ROM, and its text banks (location names, intro text blocks). */
    override val world: PlatinumWorldSource? = rom?.let { PlatinumWorldSource(it, version) }

    private val text: PlatinumText? = world?.text

    private val names = HashMap<Int, String?>()

    /** "Twinleaf Town (T01R0202)"-like names would need the internal names: the location name for now. */
    override fun zoneName(id: Int): String? = names.getOrPut(id) { world?.locationName(id)?.let { "$it #$id" } }

    override fun state(memory: Memory): GameState {
        val mem = PlatinumMemory(memory, version)
        val top = PlatinumTopApp.read(mem)
        val fs = PlatinumField.fieldSystem(mem, top)
        val field = fs?.let { PlatinumField.position(mem, it, ::zoneName) }
        val screen = PlatinumIntroScreens.decode(mem, top, text)
            ?: fs?.let { PlatinumField.screen(mem, it, field) }
            ?: when (top.app) {
                PlatinumApp.NONE, PlatinumApp.LOADING -> Screen.Intro("loading", Awaiting.ANIMATION)
                else -> Screen.Unknown("application not decoded yet for Platinum (main 0x${top.manager?.let { mem.fn(it + 4) }?.toString(16)})", Awaiting.INPUT)
            }
        return GameState(
            frame = memory.read32(version.gSystem + dev.kotlinds.pokemonclient.gen4.Gen4Structs.SYS_VBLANK_COUNTER),
            screen = screen,
            player = null,
            party = emptyList(),
            bag = null,
            battle = null,
            field = field?.copy(flyAllowed = world?.flyAllowed(field.mapId), bikeAllowed = world?.bikeAllowed(field.mapId)),
        )
    }
}
