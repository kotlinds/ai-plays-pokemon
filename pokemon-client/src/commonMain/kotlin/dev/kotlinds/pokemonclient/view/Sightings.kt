package dev.kotlinds.pokemonclient.view

import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.PuzzleTeleport
import dev.kotlinds.pokemonclient.state.PuzzleTile

/**
 * What the player has seen so far, for agents not allowed a walkthrough: the views show them only what was seen.
 *
 * The rules ("seen" = what a player looking at the screen would know):
 * - a teleport ([PuzzleTeleport]: warp pad, trap tile, cart station, lift) is seen once one of its source tiles has
 *   been on screen (within [SCREEN_HALF_WIDTH] × [SCREEN_HALF_HEIGHT] tiles of the player, the DS's view), and stays
 *   known afterwards (per map). An invisible trap is "seen" the same way (we can't tell what the tile looks like);
 * - a hidden item is never seen: it isn't drawn in the game, and once found it is gone (picked up). The views leave
 *   hidden items out entirely without a walkthrough.
 *
 * Kept by the application for its whole session ([observe] on every state shown), never written by the game.
 */
class Sightings {

    private val teleports = mutableSetOf<Pair<Int, String>>()

    /** Notes the teleports of [field]'s map on screen now. */
    fun observe(field: FieldState?) {
        field ?: return
        field.puzzle?.teleports.orEmpty().filter { t -> t.from.any { onScreen(field, it) } }.forEach { teleports += field.mapId to it.id }
    }

    /** True when teleport [teleport] of [field]'s map is on screen now or was before. */
    fun seen(field: FieldState, teleport: PuzzleTeleport): Boolean =
        (field.mapId to teleport.id) in teleports || teleport.from.any { onScreen(field, it) }

    companion object {
        /** Tiles the DS shows on each side of the player: 15 × 11 tiles in all (what [MapView.render] draws). */
        const val SCREEN_HALF_WIDTH = 7
        const val SCREEN_HALF_HEIGHT = 5

        /** True when [tile] is on the screen around the player. */
        fun onScreen(field: FieldState, tile: PuzzleTile): Boolean =
            kotlin.math.abs(tile.x - field.x) <= SCREEN_HALF_WIDTH && kotlin.math.abs(tile.y - field.y) <= SCREEN_HALF_HEIGHT
    }
}
