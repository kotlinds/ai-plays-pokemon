package dev.kotlinds.pokemonclient.world

import dev.kotlinds.pokemonclient.Direction

/**
 * A way from a tile of [zone] to another zone: a door, stairs or ladder ([Kind.WARP]), or a hole the player falls
 * through ([Kind.HOLE], one way). [id] is the target id actions take (`warp:N`, `hole:N`).
 */
data class ZoneLink(
    val kind: Kind,
    val id: String,
    val zone: Int,
    val x: Int,
    val y: Int,
    /** For warps taken by pressing a direction once on them (exit mats, stairs): that direction. */
    val exitDirection: Direction?,
    val targetZone: Int,
    /** Where the player arrives (the destination warp's tile, the hole's landing tile), when known. */
    val toX: Int?,
    val toY: Int?,
) {
    enum class Kind { WARP, HOLE }

    /** True when there is no way back through it (holes). */
    val oneWay: Boolean get() = kind == Kind.HOLE
}

/**
 * An edge of [zone] where the player walks into the neighbouring zone [toZone] of the same area (the Gen 4
 * overworld: routes and towns side by side). [tiles] are the tiles of [zone] from which one step [direction] enters
 * [toZone]; [byWater] when some of them are water (Surf).
 */
data class MapConnection(
    val zone: Int,
    val direction: Direction,
    val toZone: Int,
    val tiles: List<Pair<Int, Int>>,
    val byWater: Boolean,
) {
    /** The target id actions take: `exit:east`. */
    val id: String get() = "exit:${direction.name.lowercase()}"
}

/** The ways out of a zone, computed from the static [Area]s of a [WorldSource]. */
object WorldLinks {

    /** Warps of [zone] (with their arrival tile) and its holes. */
    fun links(world: WorldSource?, area: Area, zone: Int): List<ZoneLink> {
        val warps = area.warps.filter { it.zone == zone }.map { w ->
            val arrival = world?.areaOf(w.targetZone)?.warps?.firstOrNull { it.zone == w.targetZone && it.id == w.targetWarp }
            ZoneLink(ZoneLink.Kind.WARP, "warp:${w.id}", zone, w.x, w.y, w.exitDirection, w.targetZone, arrival?.x, arrival?.y)
        }
        val holes = area.triggerWarps.filter { it.zone == zone }.map { h ->
            ZoneLink(ZoneLink.Kind.HOLE, "hole:${h.trigger}", zone, h.x, h.y, null, h.targetZone, h.toX, h.toY)
        }
        return warps + holes
    }

    /**
     * The edges of [zone] towards the other zones of [area], grouped by direction and neighbour. Only edges the
     * player can cross: both tiles free (water counts, with [MapConnection.byWater]).
     */
    fun connections(area: Area, zone: Int): List<MapConnection> {
        val bounds = area.zoneBounds[zone] ?: return emptyList()
        val found = LinkedHashMap<Pair<Direction, Int>, MutableList<Pair<Int, Int>>>()
        val water = HashSet<Pair<Direction, Int>>()
        for (y in bounds[1]..bounds[3]) for (x in bounds[0]..bounds[2]) {
            if (area.zoneAt(x, y) != zone) continue
            val here = area.tile(x, y) ?: continue
            if (!crossable(here)) continue
            for (d in Direction.entries) {
                val other = area.zoneAt(x + d.dx, y + d.dy) ?: continue
                if (other == zone) continue
                val next = area.tile(x + d.dx, y + d.dy) ?: continue
                if (!crossable(next)) continue
                found.getOrPut(d to other) { mutableListOf() } += x to y
                if (isWater(here) || isWater(next)) water += d to other
            }
        }
        return found.map { (k, tiles) -> MapConnection(zone, k.first, k.second, tiles, k in water) }
    }

    private fun crossable(tile: TileInfo): Boolean = when (tile.kind) {
        TileKind.Wall, TileKind.Lava, TileKind.Pc, TileKind.Counter -> false
        is TileKind.Water, is TileKind.Bridge, is TileKind.Ledge -> true
        else -> !tile.blocked
    }

    private fun isWater(tile: TileInfo) = tile.kind is TileKind.Water || (tile.kind as? TileKind.Bridge)?.overWater == true
}
