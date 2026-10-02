package me.nathanfallet.aiplayspokemon.game.hgss

import me.nathanfallet.aiplayspokemon.game.Direction
import me.nathanfallet.aiplayspokemon.game.LocalMap
import me.nathanfallet.aiplayspokemon.game.PointOfInterest
import me.nathanfallet.aiplayspokemon.game.Tile

/**
 * Turns the reader's [Surroundings] into what the agent and the describer use: a [LocalMap] of [Tile]s with
 * points of interest (exits, people, objects to examine, item balls), all in global map coordinates.
 */
object HgssMapView {

    /** Objects drawn on the map (not hidden, not the camera). */
    fun visibleObjects(s: Surroundings): List<MapObjectInfo> = s.objects.filter { !it.hidden }

    /** BG events and furniture worth examining (hidden items are invisible). */
    fun examinables(s: Surroundings): List<BgEventInfo> = s.bgEvents.filter { it.type != "hidden_item" }

    fun terrainTile(c: Char): Tile = when (c) {
        '.' -> Tile.WALKABLE
        '"' -> Tile.TALL_GRASS
        '~' -> Tile.WATER
        '#' -> Tile.BLOCKED
        '_' -> Tile.LEDGE_SOUTH
        '=' -> Tile.LEDGE_NORTH
        '{' -> Tile.LEDGE_WEST
        '}' -> Tile.LEDGE_EAST
        else -> Tile.UNKNOWN
    }

    fun exitLabel(w: WarpInfo): String = "${w.kind} to ${w.destMapName}"

    fun localMap(s: Surroundings): LocalMap? {
        val grid = s.grid ?: return null
        val tiles = MutableList(grid.height) { r -> MutableList(grid.width) { c -> terrainTile(grid.rows[r][c]) } }
        fun set(x: Int, z: Int, tile: Tile) {
            val r = z - grid.originZ
            val c = x - grid.originX
            if (r in tiles.indices && c in tiles[r].indices) tiles[r][c] = tile
        }
        s.warps.forEach { set(it.x, it.z, Tile.WARP) }
        // The follower Pokémon swaps places with the player: it doesn't block.
        visibleObjects(s).filter { it.kind != "follower" }.forEach { set(it.x, it.z, Tile.OCCUPIED) }

        val pois = buildList {
            s.warps.forEach { w ->
                add(PointOfInterest(PointOfInterest.Kind.EXIT, w.x, w.z, exitLabel(w), Direction.parse(w.pressDirection)))
            }
            visibleObjects(s).filter { it.kind != "follower" }.forEach { o ->
                val kind = when (o.kind) {
                    "item_ball" -> PointOfInterest.Kind.ITEM
                    "obstacle" -> PointOfInterest.Kind.OBJECT
                    else -> PointOfInterest.Kind.PERSON
                }
                add(PointOfInterest(kind, o.x, o.z, o.label))
            }
            examinables(s).forEach { b -> add(PointOfInterest(PointOfInterest.Kind.OBJECT, b.x, b.z, b.label)) }
        }
        return LocalMap(grid.originX, grid.originZ, tiles, pois)
    }
}
