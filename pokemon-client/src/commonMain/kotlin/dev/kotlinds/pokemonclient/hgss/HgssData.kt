package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.data.TextBankId
import dev.kotlinds.pokemonclient.data.PokemonType
import dev.kotlinds.pokemonclient.readBundledText
import dev.kotlinds.pokemonclient.state.MoveId
import dev.kotlinds.pokemonclient.state.SpeciesId
import kotlin.concurrent.Volatile

/**
 * Static game data, by id, for the decoders.
 *
 * Transitional facade: when a ROM is loaded ([useGameData], done by [HgssGame]), species, moves, items, abilities,
 * trainer classes, species types and TM data come from the ROM ([HgssGameData]); otherwise (no ROM, tests) they come
 * from the bundled tables below. The charmap is always the Kotlin table [HgssCharmap]. The map tables (maps, map
 * locations / sections / types, BG labels, sprites, tile behaviors) are still bundled only.
 *
 * Bundled tables (resources under /hgss, generated from the pokeheartgold decomp):
 *  - species.txt        msgdata msg_0237 (species names), index = species id
 *  - moves.txt          msgdata msg_0750 (move names), index = move id
 *  - move_data.tsv      files/poketool/waza/waza_tbl.narc: id, name, type, category, power, accuracy, pp, priority
 *  - items.txt          msgdata msg_0222 (item names), index = item id
 *  - abilities.txt      msgdata msg_0720
 *  - maps.txt           include/constants/maps.h (MAP_* constant, prettified), index = map id
 *  - map_locations.txt  in-game location name of each map (map header mapsec -> msg_0279)
 *  - map_sections.txt   msgdata msg_0279 (mapsec names)
 *  - map_types.txt      map header mapType of each map (src/data/map_headers.h), index = map id
 *  - bg_labels.tsv      map id, x, z, label of examinable BG events (keyword of the first message their script prints)
 *  - trainer_classes.txt msgdata msg_0730
 *  - sprites.tsv        include/constants/sprites.h (id \t name)
 *  - species_types.txt  files/poketool/personal/personal.json ("Grass/Poison")
 *  - tile_behaviors.txt include/constants/metatile_behavior.h + flags from src/metatile_behavior.c
 *                       (name \t flags; flag 1 = surfable, 2 = wild encounters)
 *  - charmap.txt        charmap.txt (Gen 4 character encoding, HEX=char); unused, see [HgssCharmap]
 */
object HgssData {

    /** The ROM data, when a ROM is loaded. */
    @Volatile
    var gameData: HgssGameData? = null
        private set

    /** Makes the lookups below read [data] (null: back to the bundled tables). */
    fun useGameData(data: HgssGameData?) {
        gameData = data
    }

    /**
     * The lines of a decoding table: compiled in ([HgssTables]); the decomp's game-data tables only exist as test
     * resources, for the tests run without a ROM (at run time, that data comes from the ROM).
     */
    internal fun lines(name: String): List<String> =
        (HgssTables.files[name] ?: runCatching { readBundledText("/hgss/$name") }.getOrNull())
            ?.lines()?.dropLastWhile { it.isEmpty() } ?: emptyList()

    private val bundledSpecies: List<String> by lazy { lines("species.txt") }
    private val bundledMoves: List<String> by lazy { lines("moves.txt") }
    private val bundledItems: List<String> by lazy { lines("items.txt") }
    private val bundledAbilities: List<String> by lazy { lines("abilities.txt") }
    private val bundledTrainerClasses: List<String> by lazy { lines("trainer_classes.txt") }
    private val bundledSpeciesTypes: List<String> by lazy { lines("species_types.txt") }

    /** Species names, index = species id. */
    val species: List<String> get() = gameData?.speciesNames ?: bundledSpecies

    /** Move names, index = move id. */
    val moves: List<String> get() = gameData?.moveNames ?: bundledMoves

    /** Item names, index = item id. */
    val items: List<String> get() = gameData?.itemNames ?: bundledItems

    /** Ability names, index = ability id. */
    val abilities: List<String> get() = gameData?.abilityNames ?: bundledAbilities
    val maps: List<String> by lazy { lines("maps.txt") }
    val mapLocations: List<String> by lazy { lines("map_locations.txt") }
    val mapSections: List<String> by lazy { lines("map_sections.txt") }
    val mapTypes: List<String> by lazy { lines("map_types.txt") }
    /** Trainer class names, index = trainer class id. */
    val trainerClasses: List<String> get() = gameData?.trainerClassNames ?: bundledTrainerClasses

    /** Types of each species as "Grass/Poison" (English [PokemonType.label]s), index = species id. */
    val speciesTypes: List<String> get() = romSpeciesTypes ?: bundledSpeciesTypes

    private val romSpeciesTypes: List<String>? get() = gameData?.let { data ->
        romCache(data).speciesTypes
    }

    val sprites: Map<Int, String> by lazy {
        lines("sprites.tsv").mapNotNull { l ->
            val p = l.split('\t')
            p.getOrNull(0)?.toIntOrNull()?.let { it to (p.getOrNull(1) ?: "") }
        }.toMap()
    }

    data class MoveData(
        val id: Int, val name: String, val type: String, val category: String,
        val power: Int, val accuracy: Int, val pp: Int, val priority: Int,
    )

    /** Battle data of each move (type and category as English labels), by move id. */
    val moveData: Map<Int, MoveData> get() = gameData?.let { romCache(it).moveData } ?: bundledMoveData

    private val bundledMoveData: Map<Int, MoveData> by lazy {
        lines("move_data.tsv").mapNotNull { l ->
            val p = l.split('\t')
            if (p.size < 8) return@mapNotNull null
            val id = p[0].toIntOrNull() ?: return@mapNotNull null
            id to MoveData(
                id, p[1], p[2], p[3], p[4].toIntOrNull() ?: 0, p[5].toIntOrNull() ?: 0,
                p[6].toIntOrNull() ?: 0, p[7].toIntOrNull() ?: 0,
            )
        }.toMap()
    }

    /** The tables derived from one [HgssGameData] in the shapes of this facade, built once per ROM. */
    private class RomTables(data: HgssGameData) {
        val speciesTypes: List<String> by lazy {
            (0 until data.speciesCount).map { id ->
                data.species(SpeciesId(id))?.types?.joinToString("/") { it.label } ?: ""
            }
        }
        val moveData: Map<Int, MoveData> by lazy {
            (0 until data.moveCount).mapNotNull { id ->
                val m = data.move(MoveId(id)) ?: return@mapNotNull null
                id to MoveData(id, m.name, m.type.label, m.category.label, m.power, m.accuracy, m.pp, m.priority)
            }.toMap()
        }
    }

    @Volatile
    private var romTables: Pair<HgssGameData, RomTables>? = null

    private fun romCache(data: HgssGameData): RomTables =
        romTables?.takeIf { it.first === data }?.second ?: RomTables(data).also { romTables = data to it }

    /** behavior id (0..255) -> name */
    val tileBehaviorNames: List<String> by lazy { lines("tile_behaviors.txt").map { it.substringBefore('\t') } }

    /** behavior id (0..255) -> flags (1 surfable, 2 encounters) */
    val tileBehaviorFlags: IntArray by lazy {
        val l = lines("tile_behaviors.txt")
        IntArray(256) { i -> l.getOrNull(i)?.substringAfter('\t', "0")?.toIntOrNull() ?: 0 }
    }

    /** Behavior id by name (e.g. "TALL_GRASS"), -1 if unknown. */
    fun behaviorId(name: String): Int = tileBehaviorNames.indexOf(name)

    /** The Gen 4 character encoding (u16 code → text), see [HgssCharmap]. */
    val charmap: Map<Int, String> get() = dev.kotlinds.pokemonclient.gen4.Gen4Charmap.table

    /** English type labels, index = game type id ([PokemonType.label]). */
    val types: List<String> = PokemonType.entries.map { it.label }

    fun speciesName(id: Int): String = species.getOrNull(id)?.takeIf { it.isNotEmpty() } ?: "SPECIES_$id"
    fun moveName(id: Int): String = moves.getOrNull(id)?.takeIf { it.isNotEmpty() } ?: "MOVE_$id"
    fun itemName(id: Int): String = items.getOrNull(id)?.takeIf { it.isNotEmpty() } ?: "ITEM_$id"
    fun abilityName(id: Int): String = abilities.getOrNull(id)?.takeIf { it.isNotEmpty() } ?: "ABILITY_$id"
    fun mapName(id: Int): String = maps.getOrNull(id)?.takeIf { it.isNotEmpty() } ?: "MAP_$id"
    /**
     * The place name shown in game for map [id] ("Ecruteak City"): from the ROM (the map header's map section, named
     * in text bank [LOCATION_BANK]) when one is loaded, else from the decomp table.
     */
    fun mapLocation(id: Int): String? {
        val data = gameData
        val header = world?.header(id)
        if (data != null && header != null) return data.text(TextBankId(LOCATION_BANK), header.mapsec)?.takeIf { it.isNotEmpty() }
        return mapLocations.getOrNull(id)?.takeIf { it.isNotEmpty() }
    }

    /** CITY_TOWN, ROUTE, INTERIOR, CAVE, UNDERGROUND (map header mapType, from the ROM when loaded), or null. */
    fun mapType(id: Int): String? {
        world?.header(id)?.let { return MAP_TYPE_NAMES.getOrNull(it.mapType) }
        return mapTypes.getOrNull(id)?.takeIf { it.isNotEmpty() }
    }

    /** The ROM's maps, for [mapLocation] / [mapType] (set by [HgssGame] with the ROM). */
    var world: HgssWorldSource? = null
        private set

    fun useWorld(source: HgssWorldSource?) {
        world = source
    }

    /** Text bank of the map section (place) names (msg_0279). */
    private const val LOCATION_BANK = 279

    /** `enum MapType` (include/map_header.h), by value. */
    private val MAP_TYPE_NAMES = listOf("INVALID", "CITY_TOWN", "ROUTE", "CAVE", "INTERIOR", "POKEMON_CENTER", "UNDERGROUND")
    fun spriteName(id: Int): String = sprites[id] ?: "SPRITE_$id"
    fun typeName(id: Int): String = types.getOrNull(id) ?: "TYPE_$id"
    fun trainerClassName(id: Int): String = trainerClasses.getOrNull(id)?.takeIf { it.isNotEmpty() } ?: "CLASS_$id"
    fun speciesTypes(id: Int): List<String> = speciesTypes.getOrNull(id)?.split('/')?.filter { it.isNotEmpty() } ?: emptyList()
}

