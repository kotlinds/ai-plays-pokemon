package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.state.FieldObject
import dev.kotlinds.pokemonclient.state.FieldObjectKind
import dev.kotlinds.pokemonclient.state.FieldTrainer
import dev.kotlinds.pokemonclient.world.PersonTemplate
import kotlin.test.Test
import kotlin.test.assertEquals

/** B2: avoid_trainers must not avoid trainers already beaten (they never stop the player again). */
class TrainerSightTest {

    private val template = PersonTemplate(zone = 1, id = 3, sprite = 0, x = 5, y = 5, facing = Direction.WEST, sightRange = 4, script = 0, hiddenByFlag = 0)

    private fun sage(defeated: Boolean?, sight: Int = 4) = FieldObject(
        "person:3", "Sage Nico", FieldObjectKind.PERSON, 5, 5, Direction.WEST,
        trainer = FieldTrainer(1, "Sage", "Nico", defeated, sight),
    )

    @Test
    fun aBeatenTrainerWatchesNothing() {
        assertEquals(0, MovePlans.sightRange(sage(defeated = true), listOf(template)))
    }

    @Test
    fun anUnbeatenTrainerWatchesWhatItSees() {
        assertEquals(4, MovePlans.sightRange(sage(defeated = false), listOf(template)))
        // Unknown status: assume it still watches.
        assertEquals(4, MovePlans.sightRange(sage(defeated = null), listOf(template)))
    }

    @Test
    fun withoutTrainerDataTheMapTemplateTells() {
        val plain = FieldObject("person:3", "sage", FieldObjectKind.PERSON, 5, 5, Direction.WEST)
        assertEquals(4, MovePlans.sightRange(plain, listOf(template)))
        assertEquals(0, MovePlans.sightRange(plain.copy(id = "person:9"), listOf(template)))
    }
}
