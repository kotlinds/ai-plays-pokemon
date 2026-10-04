package dev.kotlinds.pokemonclient.world

import dev.kotlinds.pokemonclient.Direction

/**
 * Use field move [move] facing [direction] (A, then YES to the game's question), part of a route when the party can
 * use it ([RouteOptions.fieldMoves]):
 * - [FieldMoveKind.SURF]: from the shore onto the water tile [to] (landing back on land is a plain step);
 * - [FieldMoveKind.WATERFALL]: up the waterfall [tiles] (going north), to the water at the top [to] (going down
 *   needs no move: surfing into it from above is an [Edge.Slide]);
 * - [FieldMoveKind.WHIRLPOOL]: across the whirlpool [tiles], to the water behind it [to];
 * - [FieldMoveKind.CUT], [FieldMoveKind.ROCK_SMASH]: the obstacle object on [to] disappears, then the player steps
 *   onto [to].
 */
data class FieldMoveEdge(
    override val to: Node,
    override val direction: Direction,
    val move: FieldMoveKind,
    override val tiles: List<Node>,
    override val cost: Int,
) : Edge {
    /** True when the move clears an obstacle object and the player still has to step onto [to] afterwards. */
    val clearsObstacle: Boolean get() = move == FieldMoveKind.CUT || move == FieldMoveKind.ROCK_SMASH
}

/**
 * Walk into a movable object at [to] going [direction], which pushes it to [objectTo]: a Strength boulder (one tile;
 * Strength must have been used on this map first, [needsStrength]) or an ice block slid into from the ice (it slides
 * until a wall, a tile that isn't ice, or another block). The player ends on [to] for a boulder, and stays on the
 * ice tile before the block ([to] is then that tile) for an ice block.
 */
data class PushEdge(
    override val to: Node,
    override val direction: Direction,
    /** Where the object stood before the push. */
    val objectFrom: Pair<Int, Int>,
    /** Where the object stops. */
    val objectTo: Pair<Int, Int>,
    val needsStrength: Boolean,
    override val tiles: List<Node> = listOf(to),
    override val cost: Int = PUSH_COST,
) : Edge {
    companion object {
        /** A push is slower than a step, and routes should push as little as possible: worth several steps. */
        const val PUSH_COST = 10
    }
}
