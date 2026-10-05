package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.state.Entry
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.world.Node
import dev.kotlinds.pokemonclient.world.Pathfinder
import dev.kotlinds.pokemonclient.world.WorldRouter

/**
 * What the agent needs to know when Fly can't take it where it asks: the nearest place where Fly works (from indoors,
 * a cave...), and destinations in another region (HGSS: Fly only reaches the region the player is in, Johto or
 * Kanto, except from Indigo Plateau).
 */
internal object FlyHints {

    /**
     * "the nearest place where Fly works: Violet City, via warp:0 (go_to "Violet City")": the closest zone (in steps,
     * through warps) whose map allows Fly, or null when the world doesn't say where Fly works or none is reachable.
     */
    fun nearestFlyable(context: PlanContext): String? {
        val field = context.state().field ?: return null
        val world = context.game.world ?: return null
        val area = world.areaOf(field.mapId) ?: return null
        if (world.flyAllowed(field.mapId) == null) return null
        val overlay = MovePlans.overlay(context, field, emptySet())
        val router = WorldRouter(world) { _, a -> if (a === area) overlay else WorldRouter.staticOverlay(a) }
        val start = Node(field.x, field.y, Pathfinder(area).levelAt(field.x, field.y, field.height * MovePlans.HEIGHT_UNITS))
        val route = router.route(field.mapId, start, WorldTravel.worldRouteOptions(context, field, MoveOptions(acceptOneWay = true))) { place ->
            place.zone?.let { world.flyAllowed(it) } == true
        } ?: return null
        val zone = route.end.zone ?: return null
        val name = context.game.zoneName(zone) ?: "map:$zone"
        val via = route.links.firstOrNull()?.let { ", via ${it.id}" } ?: ""
        return "the nearest place where Fly works: $name$via (go_to \"$name\")"
    }

    /**
     * When [destination] names a town of another region than the one of [startMap] (where the player stood before
     * opening the menus: the fly map has no field state) by `fly:<map id>` or by the map's name: the error, else null.
     */
    fun otherRegion(context: PlanContext, destination: String, startMap: Int?): ActionError? {
        startMap ?: return null
        val world = context.game.world ?: return null
        val here = world.regionOf(startMap) ?: return null
        val zone = destination.removePrefix("fly:").toIntOrNull()?.takeIf { destination.startsWith("fly:") }
            ?: (0 until world.zoneCount).firstOrNull { id -> context.game.zoneName(id)?.let { WorldTravel.sameMapName(it, destination) } == true }
            ?: return null
        val there = world.regionOf(zone) ?: return null
        if (there.id == here.id) return null
        return regionError(context.game.zoneName(zone) ?: destination, here.name, there.name)
    }

    /** The OTHER_REGION error for a town of [there] asked from [here]. */
    fun regionError(town: String, here: String, there: String): ActionError = ActionError.Unavailable(
        UnavailableReason.OTHER_REGION,
        "$town is in $there and you are in $here: Fly only reaches towns of the region you are in",
        // The game's own rule (src/application/pokegear/map/overlay_101_021E9270.c ov101_021EA7E4): from Indigo
        // Plateau every visited town of both regions can be chosen, and Indigo Plateau / Route 26 always can.
        "fly to Indigo Plateau first (it links both regions: from there Fly reaches $there), or travel on foot, by boat or by train",
    )

    /**
     * Why [entry] of the fly map [screen] can't be chosen: in another region ([Screen.FlyMap.otherRegion]; the player
     * stood on [startMap] before the menus), or not visited yet.
     */
    fun notSelectable(context: PlanContext, entry: Entry, screen: Screen?, startMap: Int?): ActionError {
        if ((screen as? Screen.FlyMap)?.otherRegion?.contains(entry.id) == true) {
            val world = context.game.world
            val here = startMap?.let { world?.regionOf(it)?.name } ?: "this region"
            val there = entry.id.removePrefix("fly:").toIntOrNull()?.let { world?.regionOf(it)?.name } ?: "another region"
            return regionError(entry.label, here, there)
        }
        return ActionError.Unavailable(UnavailableReason.NOT_VISITED, "${entry.label} hasn't been visited yet", "walk there once: Fly only reaches towns already visited")
    }
}
