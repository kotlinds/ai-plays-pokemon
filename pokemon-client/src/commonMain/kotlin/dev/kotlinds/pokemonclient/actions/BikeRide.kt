package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.state.MovementMode

/**
 * The `bike` option of `go_to` / `step`: gets on the bicycle before walking, with the registered button when it's
 * registered (Y / its touch button), else bag → the Bicycle → USE ([PartyBagPlans.activateKeyItem]). Called again
 * after each warp (entering a building gets the player off; leaving it doesn't put them back on).
 *
 * Never an error: where cycling isn't possible (indoors, no bicycle, the game says no) the walk goes on on foot and
 * the note says why.
 */
internal object BikeRide {

    /** Gets on the bicycle when [options] ask for it and the player walks; a note for the agent, or null. */
    fun mount(context: PlanContext, options: MoveOptions): String? {
        if (!options.bike) return null
        val state = context.navigator.settle()
        val field = state.field ?: return null
        if (field.movement == MovementMode.BIKE || field.movement == MovementMode.SURF) return null
        if (field.bikeAllowed == false) return "$NO_CYCLING ${field.mapName}: walked"
        val item = context.game.bicycleItem ?: return "this game has no bicycle: walked"
        val owned = state.bag.orEmpty().any { pocket -> pocket.items.any { it.item.id.value == item } }
        if (!owned) return "no Bicycle in the bag: walked"
        val used = PartyBagPlans.activateKeyItem(context, ItemRef("item:$item"))
        // A refusal is a message ("no cycling here"): read it, back to the field.
        PartyBagPlans.closeToOverworld(context)
        val now = context.navigator.settle().field
        return when {
            used is Step.Failed -> "couldn't get on the Bicycle (${used.error.message}): walked"
            now?.movement == MovementMode.BIKE -> RODE
            else -> "the game didn't let the player ride here: walked"
        }
    }

    /** The note of a ride. */
    const val RODE = "rode the Bicycle"

    /** The start of the note of a map where cycling isn't allowed. */
    const val NO_CYCLING = "no cycling on"
}
