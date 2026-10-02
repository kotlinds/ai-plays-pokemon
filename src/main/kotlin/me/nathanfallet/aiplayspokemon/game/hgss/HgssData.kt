package me.nathanfallet.aiplayspokemon.game.hgss

/**
 * Static game data tables (resources under /hgss, generated from the pokeheartgold decomp):
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
 *  - charmap.txt        charmap.txt (Gen 4 character encoding, HEX=char)
 */
object HgssData {

    internal fun lines(name: String): List<String> = try {
        HgssData::class.java.getResourceAsStream("/hgss/$name")?.bufferedReader(Charsets.UTF_8)?.use { r ->
            r.readLines()
        } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    val species: List<String> by lazy { lines("species.txt") }
    val moves: List<String> by lazy { lines("moves.txt") }
    val items: List<String> by lazy { lines("items.txt") }
    val abilities: List<String> by lazy { lines("abilities.txt") }
    val maps: List<String> by lazy { lines("maps.txt") }
    val mapLocations: List<String> by lazy { lines("map_locations.txt") }
    val mapSections: List<String> by lazy { lines("map_sections.txt") }
    val mapTypes: List<String> by lazy { lines("map_types.txt") }
    val trainerClasses: List<String> by lazy { lines("trainer_classes.txt") }
    val speciesTypes: List<String> by lazy { lines("species_types.txt") }

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

    val moveData: Map<Int, MoveData> by lazy {
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

    /** behavior id (0..255) -> name */
    val tileBehaviorNames: List<String> by lazy { lines("tile_behaviors.txt").map { it.substringBefore('\t') } }

    /** behavior id (0..255) -> flags (1 surfable, 2 encounters) */
    val tileBehaviorFlags: IntArray by lazy {
        val l = lines("tile_behaviors.txt")
        IntArray(256) { i -> l.getOrNull(i)?.substringAfter('\t', "0")?.toIntOrNull() ?: 0 }
    }

    /** Behavior id by name (e.g. "TALL_GRASS"), -1 if unknown. */
    fun behaviorId(name: String): Int = tileBehaviorNames.indexOf(name)

    val charmap: Map<Int, String> by lazy {
        lines("charmap.txt").mapNotNull { l ->
            val eq = l.indexOf('=')
            if (eq != 4) return@mapNotNull null
            val code = l.substring(0, 4).toIntOrNull(16) ?: return@mapNotNull null
            val v = when (val s = l.substring(5)) {
                "\\n" -> "\n"
                "\\r" -> "\n"
                "\\f" -> "\n\n"
                // Bag pocket icons (0x113..0x11A) drawn inline in messages ("in the [icon]MEDICINE Pocket").
                in POCKET_ICON_GLYPHS -> ""
                else -> s
            }
            code to v
        }.toMap()
    }

    private val POCKET_ICON_GLYPHS = setOf("♈", "♉", "♊", "♋", "♌", "♍", "♎", "♏")

    val types = listOf(
        "Normal", "Fighting", "Flying", "Poison", "Ground", "Rock", "Bug", "Ghost", "Steel", "???",
        "Fire", "Water", "Grass", "Electric", "Psychic", "Ice", "Dragon", "Dark",
    )

    fun speciesName(id: Int): String = species.getOrNull(id)?.takeIf { it.isNotEmpty() } ?: "SPECIES_$id"
    fun moveName(id: Int): String = moves.getOrNull(id)?.takeIf { it.isNotEmpty() } ?: "MOVE_$id"
    fun itemName(id: Int): String = items.getOrNull(id)?.takeIf { it.isNotEmpty() } ?: "ITEM_$id"
    fun abilityName(id: Int): String = abilities.getOrNull(id)?.takeIf { it.isNotEmpty() } ?: "ABILITY_$id"
    fun mapName(id: Int): String = maps.getOrNull(id)?.takeIf { it.isNotEmpty() } ?: "MAP_$id"
    fun mapLocation(id: Int): String? = mapLocations.getOrNull(id)?.takeIf { it.isNotEmpty() }

    /** CITY_TOWN, ROUTE, INTERIOR, CAVE, UNDERGROUND (map header mapType), or null. */
    fun mapType(id: Int): String? = mapTypes.getOrNull(id)?.takeIf { it.isNotEmpty() }
    fun spriteName(id: Int): String = sprites[id] ?: "SPRITE_$id"
    fun typeName(id: Int): String = types.getOrNull(id) ?: "TYPE_$id"
    fun trainerClassName(id: Int): String = trainerClasses.getOrNull(id)?.takeIf { it.isNotEmpty() } ?: "CLASS_$id"
    fun speciesTypes(id: Int): List<String> = speciesTypes.getOrNull(id)?.split('/')?.filter { it.isNotEmpty() } ?: emptyList()
}

/** Gen 4 string decoding (charmap.txt + control codes, src/string_control_code.c). */
object HgssText {
    const val EOS = 0xFFFF
    const val CTRL = 0xFFFE

    const val NEWLINE = 0xE000
    const val SCROLL = 0x25BC   // \r: wait for A then scroll one line
    const val NEW_PAGE = 0x25BD // \f: wait for A then clear the box

    /**
     * What the 2-line message box shows after printing [printed] chars (all of them when null): the last two lines
     * of the current page. Lines are split by newlines and scrolls; a new page clears them.
     */
    fun visibleLines(chars: IntArray, printed: Int?): String {
        val end = printed?.coerceAtMost(chars.size) ?: chars.size
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        // A new page only clears the box once its first character prints (at the page break the old page is still shown).
        var newPage = false
        var i = 0
        while (i < end) {
            when (val c = chars[i]) {
                EOS -> break
                CTRL -> {
                    if (i + 2 >= chars.size) break
                    i += 3 + chars[i + 2]
                    continue
                }
                NEWLINE, SCROLL -> { lines += line.toString(); line.clear() }
                NEW_PAGE -> { lines += line.toString(); line.clear(); newPage = true }
                else -> {
                    if (newPage) { lines.clear(); newPage = false }
                    line.append(HgssData.charmap[c] ?: if (c == 0) "" else "?")
                }
            }
            i++
        }
        if (line.isNotEmpty()) lines += line.toString()
        return lines.filter { it.isNotEmpty() }.takeLast(2).joinToString("\n").trim()
    }

    /** Decodes a u16 character array (stops at EOS). Control sequences FFFE,cmd,argc,args... are skipped. */
    fun decode(chars: IntArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < chars.size) {
            val c = chars[i]
            when {
                c == EOS -> break
                c == CTRL -> {
                    if (i + 2 >= chars.size) break
                    val argc = chars[i + 2]
                    i += 3 + argc
                    continue
                }
                else -> sb.append(HgssData.charmap[c] ?: if (c == 0) "" else "?")
            }
            i++
        }
        return sb.toString()
    }
}
