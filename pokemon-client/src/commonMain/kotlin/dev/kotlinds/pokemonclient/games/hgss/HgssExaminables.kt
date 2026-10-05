package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.state.ExaminableKind
import dev.kotlinds.pokemonclient.state.FieldExaminable
import dev.kotlinds.pokemonclient.state.ItemId
import dev.kotlinds.pokemonclient.state.Named

/**
 * The invisible map objects of HeartGold that answer A ([FieldExaminable]).
 *
 * `SPRITE_STOP` objects draw nothing (the game spawns them with the hidden flag: verified live, Mahogany's souvenir
 * shop) but block their tile, and A on one runs its script. Most have no script (0: invisible walls, Will's and
 * Koga's room exits, the Goldenrod basement) or an empty one (`End`: the Vermilion Gym's, the souvenir shop's): those
 * are walls only. The others are listed: the Cerulean Gym's Machine Part (obj_T04GYM0101_stop / stop_2 at (3,10) and
 * (4,10), scr_seq_T04GYM0101_005 gives ITEM_MACHINE_PART then hides both and sets
 * FLAG_HIDE_CERULEAN_GYM_MACHINE_PART), Mt. Moon Square's "We are closed for the day." in front of the shop.
 *
 * Present only while their object is on the map: an object whose event flag is set isn't spawned, and `HidePerson`
 * removes it at once; the flag is checked too, in case of a stale reading.
 *
 * None has a cue: a `SPRITE_STOP` tile looks like the floor or the scenery around it (the Machine Part's tiles are the
 * pool's edge by the buoys, like the rest of the edge). So, by the knowledge rules of the views (see
 * [dev.kotlinds.pokemonclient.view.Sightings]: a hidden item is never "seen"), they are walkthrough knowledge.
 */
object HgssExaminables {

    /**
     * The interactive invisible objects among [objects] (the reader's map objects of zone [mapId]), [world] for the
     * zone scripts and [flag] for the event flags (null: unknown).
     */
    fun of(objects: List<MapObjectInfo>, mapId: Int, world: HgssWorldSource?, flag: (Int) -> Boolean?): List<FieldExaminable> {
        world ?: return emptyList()
        return objects.filter { it.hidden && it.sprite == STOP_SPRITE && it.scriptId in 1 until FIRST_STD_SCRIPT }.mapNotNull { o ->
            if (o.eventFlag != 0 && flag(o.eventFlag) == true) return@mapNotNull null
            val zone = o.mapId.takeIf { it >= 0 } ?: mapId
            if (zone != mapId) return@mapNotNull null
            val file = world.scriptFile(zone) ?: return@mapNotNull null
            if (HgssScripts.scriptStarts(file).getOrNull(o.scriptId - 1) == null || HgssScripts.isEmpty(file, o.scriptId)) return@mapNotNull null
            val item = HgssScripts.givenItem(file, o.scriptId)?.let { Named(ItemId(it), HgssData.itemName(it)) }
            FieldExaminable(
                id = "examine:${o.id}",
                label = item?.name ?: SOMETHING,
                kind = if (item != null) ExaminableKind.ITEM else ExaminableKind.SPOT,
                x = o.x,
                y = o.z,
                item = item,
            )
        }
    }

    /** `SPRITE_STOP` (include/constants/sprites.h): an object that draws nothing. */
    const val STOP_SPRITE = "STOP"

    /** What an examinable that gives no item is called. */
    const val SOMETHING = "something to examine"

    /** `std_signpost`: common scripts start here, a map's own are below. */
    private const val FIRST_STD_SCRIPT = 2000
}
