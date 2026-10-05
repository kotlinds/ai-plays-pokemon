package dev.kotlinds.pokemonclient.games.hgss

/**
 * Which live map objects the field state lists, and their ids.
 *
 * - **Ids**: an object is `person:<local id>` (its index in its zone's events). On the overworld, the objects of a
 *   neighbouring zone stay loaded next to the player's (walking from Route 8 to Lavender Town): their local ids clash
 *   with the zone's own, so they are qualified with their zone, `person:<local id>@<zone id>` (still language
 *   independent). [HgssReader] gives such a carried-over object its real zone (the game re-tags it with the new one). Ids are unique over the objects the player sees, and actions take them as they are listed.
 * - **Props**: an object without any script (talking to it does nothing) standing in a wall is a cutscene prop parked
 *   out of the way (Prof. Elm's `obj_T20_doctor` in New Bark's top-right corner, waiting for a scene): never listed.
 */
internal object HgssObjectIds {

    /** The id of [o] seen from zone [playerZone]. */
    fun idOf(o: MapObjectInfo, playerZone: Int?): String {
        val zone = o.mapId
        return if (zone < 0 || playerZone == null || zone == playerZone) "person:${o.id}" else "person:${o.id}@$zone"
    }

    /** True when [o] is a cutscene prop (no script, in a wall of zone [zone]'s map): not shown, not a target. */
    fun isProp(o: MapObjectInfo, zone: Int?): Boolean {
        if (o.scriptId != 0 || o.kind != "npc") return false
        val area = (o.mapId.takeIf { it >= 0 } ?: zone)?.let { HgssData.world?.areaOf(it) } ?: return false
        val tile = area.tile(o.x, o.z) ?: return false
        return tile.blocked
    }
}
