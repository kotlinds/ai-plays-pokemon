package dev.kotlinds.pokemonclient.view

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.state.FieldObjectKind
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.TeleportKind
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.MapConnection
import dev.kotlinds.pokemonclient.world.SignKind
import dev.kotlinds.pokemonclient.world.TileKind
import dev.kotlinds.pokemonclient.world.WorldLinks
import dev.kotlinds.pokemonclient.world.WorldSource
import dev.kotlinds.pokemonclient.world.ZoneLink
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The text map of the player's surroundings, the same for every game: built from the ROM's [Area] (tiles, warps,
 * signs) and the live [FieldState] (position, people). Everything listed has the id actions take (`warp:N`, `hole:N`,
 * `exit:<direction>`, `person:N`, `item:N`, `sign:N`, `hidden_item:N`) and both absolute coordinates (for `go_to`) and
 * relative ones ("3 west, 2 north"). Exits name the destination map and the arrival tile, when [world] is given.
 */
object MapView {

    /**
     * Renders the [width] × [height] tiles around the player (the DS screen shows about 15 × 11). [zoneName] names the
     * map a warp leads to, when known; [world] gives the arrival tiles of warps (and the other zones' maps).
     * [showHidden]: show the hidden items (never seen by the player: walkthrough knowledge, see [Sightings]).
     */
    fun render(
        area: Area,
        field: FieldState,
        zoneName: (Int) -> String? = { null },
        width: Int = 15,
        height: Int = 11,
        world: WorldSource? = null,
        showHidden: Boolean = true,
    ): JsonObject {
        val left = field.x - width / 2
        val top = field.y - height / 2
        val warps = area.warps.filter { it.zone == field.mapId || area.zoneAt(it.x, it.y) == field.mapId }
        val links = WorldLinks.links(world, area, field.mapId)
        val arrivals = links.associate { it.id to it }
        val holes = links.filter { it.kind == ZoneLink.Kind.HOLE }
        val connections = WorldLinks.connections(area, field.mapId)
        val signs = area.signs.filter { it.zone == field.mapId && it.kind == SignKind.SIGN }
        val hiddenItems = if (!showHidden) emptyList()
        else area.signs.filter { it.zone == field.mapId && it.kind == SignKind.HIDDEN_ITEM && "hidden_item:${it.id}" !in field.pickedUp }
        fun shown(x: Int, y: Int) = x in left until left + width && y in top until top + height
        val objects = field.objects.filter { shown(it.x, it.y) }
        // The live puzzle: closed gates / shutters, and the tiles that start a teleport, a ride or a lift.
        val closed = field.puzzle?.barriers.orEmpty().filterNot { it.open }.flatMap { b -> b.tiles.map { it.x to it.y } }.toSet()
        val teleports = field.puzzle?.teleports.orEmpty().flatMap { t -> t.from.map { (it.x to it.y) to (if (t.kind == TeleportKind.LIFT) 'L' else 'W') } }.toMap()
        val platformTiles = field.puzzle?.platforms.orEmpty().flatMap { p -> p.tiles.map { it.x to it.y } }.toSet()
        val platformTriggers = field.puzzle?.platforms.orEmpty().flatMap { p -> p.triggers.map { it.tile.x to it.tile.y } }.toSet()
        val used = linkedSetOf<Char>()
        val rows = (top until top + height).map { y ->
            val cells = (left until left + width).joinToString(" ") { x ->
                val c = when {
                    x == field.x && y == field.y -> PLAYER.getValue(field.facing ?: Direction.SOUTH)
                    else -> objects.firstOrNull { it.x == x && it.y == y }?.let(::objectSymbol)
                        ?: warps.firstOrNull { it.x == x && it.y == y }?.let { 'E' }
                        ?: teleports[x to y]
                        ?: 'G'.takeIf { (x to y) in closed }
                        ?: holes.firstOrNull { it.x == x && it.y == y }?.let { 'O' }
                        ?: (x to y).takeIf { it in platformTriggers }?.let { 'X' }
                        ?: (x to y).takeIf { it in platformTiles }?.let { '&' }
                        ?: (x to y).takeIf { it in field.activeTriggers }?.let { 'x' }
                        ?: signs.firstOrNull { it.x == x && it.y == y }?.let { 'S' }
                        ?: hiddenItems.firstOrNull { it.x == x && it.y == y }?.let { '$' }
                        ?: area.tile(x, y)?.let { tile -> symbol(tile.kind, tile.blocked) }
                        ?: ' '
                }
                used += c
                c.toString()
            }
            "${y.toString().padStart(4)} $cells"
        }
        return buildJsonObject {
            put("map", JsonArray(listOf("     " + (left until left + width).joinToString(" ") { (it % 10).toString() }).plus(rows).map(::JsonPrimitive)))
            put("map_origin", "x $left..${left + width - 1}, y $top..${top + height - 1} (columns show x mod 10)")
            put("legend", used.mapNotNull { c -> LEGEND[c]?.let { "$c $it" } }.joinToString(" · "))
            levels(area, field, left, top, width, height)?.let { (grid, mine) ->
                put("levels", JsonArray(grid.map(::JsonPrimitive)))
                put("levels_legend", "height level of each walkable tile (0 = lowest in view; you are on level $mine); a level is left only by stairs or slopes, never by stepping off an edge. b = a bridge (two levels on the tile), blank = not walkable")
            }
            // Replaces the state's position object in the agent's view: it carries the movement mode too.
            // The height tells bridges, walkways and platforms apart (the same x, y on two levels).
            put("position", "${field.x},${field.y} facing ${field.facing?.name?.lowercase() ?: "?"}, ${field.movement.name.lowercase()}, height ${field.height}")
            val exits = buildList {
                warps.forEach { w ->
                    val arrival = arrivals["warp:${w.id}"]?.takeIf { it.zone == w.zone }
                    val to = zoneName(w.targetZone)?.let { name -> " → $name" + (arrival?.toX?.let { " (${it},${arrival.toY})" } ?: "") } ?: ""
                    add(distance(field, w.x, w.y) to "warp:${w.id} at ${w.x},${w.y} (${relative(field, w.x, w.y)})$to" +
                        (w.exitDirection?.let { " (step on it, then press ${it.name.lowercase()})" } ?: ""))
                }
                holes.forEach { h ->
                    add(distance(field, h.x, h.y) to "${h.id} at ${h.x},${h.y} (${relative(field, h.x, h.y)}) → " +
                        "${zoneName(h.targetZone) ?: "map:${h.targetZone}"} (${h.toX},${h.toY}) (a hole: you fall through, one way)")
                }
                connections.forEach { c ->
                    val (nx, ny) = c.tiles.minBy { (x, y) -> distance(field, x, y) }
                    add(distance(field, nx, ny) to "${c.id} → ${zoneName(c.toZone) ?: "map:${c.toZone}"} along ${span(c)} (nearest ${nx},${ny}: ${relative(field, nx, ny)})" +
                        (if (c.byWater) " (partly over water)" else ""))
                }
            }
            if (exits.isNotEmpty()) put("exits", JsonArray(exits.sortedBy { it.first }.map { JsonPrimitive(it.second) }))
            val people = objects.filter { it.kind != FieldObjectKind.FOLLOWER && it.kind != FieldObjectKind.ITEM_BALL }
            if (people.isNotEmpty()) put("people", JsonArray(people.sortedBy { distance(field, it.x, it.y) }.map { o ->
                JsonPrimitive("${o.id} ${o.label} at ${o.x},${o.y} (${relative(field, o.x, o.y)})" + (o.facing?.let { " facing ${it.name.lowercase()}" } ?: "") +
                    (o.role?.let { " [${it.name.lowercase()}]" } ?: ""))
            }))
            // Everyone else on the map (the game tracks every object of the map, not only those on screen): compact.
            val far = field.objects.filter { !shown(it.x, it.y) && it.kind == FieldObjectKind.PERSON }.sortedBy { distance(field, it.x, it.y) }
            if (far.isNotEmpty()) put("people_off_screen", JsonArray(far.take(MAX_FAR_PEOPLE).map { o ->
                JsonPrimitive("${o.id} ${o.label} at ${o.x},${o.y}" + (o.role?.let { " [${it.name.lowercase()}]" } ?: ""))
            } + listOfNotNull((far.size - MAX_FAR_PEOPLE).takeIf { it > 0 }?.let { JsonPrimitive("… and $it more (go_to or interact them by id)") })))
            val items = objects.filter { it.kind == FieldObjectKind.ITEM_BALL }
            if (items.isNotEmpty()) put("items", JsonArray(items.sortedBy { distance(field, it.x, it.y) }.map { o ->
                JsonPrimitive("item:${o.id.substringAfter(':')} ${o.label} at ${o.x},${o.y} (${relative(field, o.x, o.y)})")
            }))
            val nearSigns = signs.filter { shown(it.x, it.y) }
            if (nearSigns.isNotEmpty()) put("signs", JsonArray(nearSigns.map { s -> JsonPrimitive("sign:${s.id} at ${s.x},${s.y} (${relative(field, s.x, s.y)})") }))
            if (hiddenItems.isNotEmpty()) put("hidden_items", JsonArray(hiddenItems.sortedBy { distance(field, it.x, it.y) }.map { s ->
                JsonPrimitive("hidden_item:${s.id} at ${s.x},${s.y} (${relative(field, s.x, s.y)}): face it and press A (interact)")
            }))
        }
    }

    /** "895,402..405" / "896..899,388": the edge tiles of a connection. */
    private fun span(c: MapConnection): String {
        val xs = c.tiles.map { it.first }
        val ys = c.tiles.map { it.second }
        fun range(v: List<Int>) = if (v.min() == v.max()) "${v.min()}" else "${v.min()}..${v.max()}"
        return "${range(xs)},${range(ys)}"
    }

    /**
     * The height levels of the window's walkable tiles, when it has more than one ([field] for the player's level):
     * the grid rows (same layout as the map) and the player's level. Heights closer than a step
     * ([dev.kotlinds.pokemonclient.world.RouteOptions.DEFAULT_MAX_CLIMB]) are the same level; null on a flat view.
     */
    private fun levels(area: Area, field: FieldState, left: Int, top: Int, width: Int, height: Int): Pair<List<String>, Int>? {
        fun walkable(t: dev.kotlinds.pokemonclient.world.TileInfo) = !t.blocked && t.kind != TileKind.Wall
        val heights = (top until top + height).flatMap { y -> (left until left + width).mapNotNull { x -> area.tile(x, y)?.takeIf(::walkable)?.heights } }
            .flatten().distinct().sorted()
        if (heights.size < 2) return null
        // Group heights into levels: a gap bigger than a step starts a new level.
        val levelOf = HashMap<Int, Int>()
        var level = 0
        heights.forEachIndexed { i, h ->
            if (i > 0 && h - heights[i - 1] > dev.kotlinds.pokemonclient.world.RouteOptions.DEFAULT_MAX_CLIMB) level++
            levelOf[h] = level
        }
        if (level == 0 || level > 9) return null
        val playerHeight = field.height * PLAYER_HEIGHT_UNITS
        val mine = area.tile(field.x, field.y)?.heights?.minByOrNull { kotlin.math.abs(it - playerHeight) }?.let { levelOf[it] } ?: return null
        val rows = (top until top + height).map { y ->
            "${y.toString().padStart(4)} " + (left until left + width).joinToString(" ") { x ->
                val tile = area.tile(x, y)?.takeIf(::walkable)
                when {
                    tile == null || tile.heights.isEmpty() -> " "
                    tile.heights.map { levelOf[it] }.distinct().size > 1 -> "b"
                    else -> levelOf[tile.heights.first()].toString()
                }
            }
        }
        return rows to mine
    }

    /** The symbol of a map object: obstacles by what clears them. */
    private fun objectSymbol(o: dev.kotlinds.pokemonclient.state.FieldObject): Char = when (o.obstacle) {
        dev.kotlinds.pokemonclient.state.ObstacleKind.CUT_TREE -> 'T'
        dev.kotlinds.pokemonclient.state.ObstacleKind.SMASH_ROCK -> 'K'
        dev.kotlinds.pokemonclient.state.ObstacleKind.BOULDER -> 'B'
        dev.kotlinds.pokemonclient.state.ObstacleKind.ICE_BLOCK -> 'I'
        null -> OBJECT.getValue(o.kind)
    }

    /** Field heights are in units of 8 BDHC height units ([dev.kotlinds.pokemonclient.world.TileInfo.heights]). */
    private const val PLAYER_HEIGHT_UNITS = 8

    /** The symbol of a tile kind. */
    fun symbol(kind: TileKind, blocked: Boolean): Char = when (kind) {
        TileKind.Floor, TileKind.Sand, TileKind.Cave, is TileKind.Railing -> if (blocked) '#' else '.'
        is TileKind.Bridge -> ':'
        TileKind.TallGrass -> '"'
        TileKind.Wall -> '#'
        is TileKind.Ledge -> when (kind.jump) {
            Direction.NORTH -> '^'
            Direction.SOUTH -> 'v'
            Direction.WEST -> '<'
            Direction.EAST -> '>'
        }
        is TileKind.Water -> if (kind.fishable) '~' else ';'
        TileKind.Waterfall -> '|'
        TileKind.Whirlpool -> '@'
        TileKind.Ice -> '_'
        TileKind.Lava -> '!'
        TileKind.Ladder -> 'H'
        TileKind.Counter -> '='
        TileKind.Pc -> 'C'
        TileKind.Door -> 'E'
        is TileKind.Spinner -> '*'
        TileKind.SpinnerStop -> '+'
        TileKind.RockClimb -> '%'
        is TileKind.Unknown -> if (blocked) '#' else '?'
    }

    private fun distance(field: FieldState, x: Int, y: Int) = kotlin.math.abs(x - field.x) + kotlin.math.abs(y - field.y)

    /** People listed off screen at most (the nearest ones). */
    private const val MAX_FAR_PEOPLE = 12

    /** "3 west, 2 north", "here". */
    fun relative(field: FieldState, x: Int, y: Int): String {
        val dx = x - field.x
        val dy = y - field.y
        val parts = listOfNotNull(
            dx.takeIf { it != 0 }?.let { "${kotlin.math.abs(it)} ${if (it > 0) "east" else "west"}" },
            dy.takeIf { it != 0 }?.let { "${kotlin.math.abs(it)} ${if (it > 0) "south" else "north"}" },
        )
        return if (parts.isEmpty()) "here" else parts.joinToString(", ")
    }

    private val PLAYER = mapOf(Direction.NORTH to 'A', Direction.SOUTH to 'V', Direction.WEST to '{', Direction.EAST to '}')
    private val OBJECT = mapOf(
        FieldObjectKind.PERSON to 'P',
        FieldObjectKind.FOLLOWER to 'f',
        FieldObjectKind.ITEM_BALL to 'o',
        FieldObjectKind.OBSTACLE to 'R',
    )
    private val LEGEND = mapOf(
        'A' to "you (facing north)", 'V' to "you (facing south)", '{' to "you (facing west)", '}' to "you (facing east)",
        '.' to "floor", '#' to "wall / obstacle", '"' to "tall grass (wild Pokémon)", '^' to "ledge (jump north only)",
        'v' to "ledge (jump south only)", '<' to "ledge (jump west only)", '>' to "ledge (jump east only)", '~' to "water (Surf; a rod fishes when facing it)", ';' to "water where no rod bites (top of a waterfall)",
        '|' to "waterfall", '@' to "whirlpool", '_' to "ice (slides)", '!' to "lava", 'H' to "ladder", '=' to "counter (talk across it)",
        'C' to "PC", 'E' to "exit / door (see exits)", '*' to "arrow tile (pushes)", '+' to "stop tile (ends a push)", '%' to "rocky wall (Rock Climb)", '?' to "unknown floor", 'P' to "person (see people)",
        ':' to "bridge (walk on it; water or a lower floor under it)", 'O' to "hole (you fall to the floor below, see exits)",
        '$' to "hidden item (see hidden_items)",
        'W' to "teleport tile: a pad, pit or cart station that moves you (see puzzle.teleports)",
        'L' to "lift: stepping on it takes you to the other floor (see puzzle)", 'G' to "closed gate / shutter (see puzzle)",
        'f' to "your Pokémon (follows you)", 'o' to "item ball (see items)", 'R' to "obstacle", 'S' to "sign (see signs)",
        'T' to "small tree (Cut)", 'K' to "cracked rock (Rock Smash)", 'B' to "boulder (Strength pushes it)",
        'I' to "ice block (slide into it on the ice to push it)",
        '&' to "moving platform over the lava (walkable; see puzzle.platforms)", 'X' to "platform trigger: stepping here turns or slides the platform (see puzzle.platforms)",
        'x' to "trigger: stepping here starts a scene or an event now (see blocked_by / puzzle)",
    )
}
