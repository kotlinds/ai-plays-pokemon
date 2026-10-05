package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.games.hgss.HgssIlexFarfetchd.Spot
import dev.kotlinds.pokemonclient.games.hgss.HgssIlexFarfetchd.State
import dev.kotlinds.pokemonclient.state.HerdOutcome
import dev.kotlinds.pokemonclient.state.HerdStep
import dev.kotlinds.pokemonclient.state.PuzzleKind
import dev.kotlinds.pokemonclient.state.PuzzleTile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Ilex Forest Farfetch'd rules ([HgssIlexFarfetchd], scr_seq_0092_D36R0101.s) and the plans they give. */
class HgssIlexFarfetchdTest {

    @Test
    fun birdOneIsCaughtFromTheNorthAfterTheSouthTwigSetsItsBlindSpot() {
        // On entering (Azalea Rockets beaten): bird 1 on its bottom-left spot, twig sticks1 active.
        val start = State(Spot.BOTTOM_LEFT, twigs = 1, blind = false)
        // Talked to from the north without the blind spot: it runs east to the bottom-right spot.
        assertEquals(Spot.BOTTOM_RIGHT, HgssIlexFarfetchd.talk(1, start, Direction.SOUTH).to)
        // From the west or the east: it runs north.
        assertEquals(Spot.TOP_LEFT, HgssIlexFarfetchd.talk(1, start, Direction.EAST).to)
        assertEquals(
            listOf(
                HerdStep.StepOnTwig("trigger:0", PuzzleTile(25, 66)),
                HerdStep.TalkFrom(PuzzleTile(25, 61), Direction.SOUTH, HerdOutcome.Caught),
            ),
            HgssIlexFarfetchd.plan(1, start),
        )
    }

    @Test
    fun birdTwoIsDrivenToItsTopRightSpotThenCaughtFromTheWest() {
        val start = State(Spot.TOP_LEFT, twigs = 1 shl 3, blind = false)
        assertEquals(
            listOf(
                HerdStep.TalkFrom(PuzzleTile(41, 55), Direction.NORTH, HerdOutcome.Flees(PuzzleTile(49, 54))),
                HerdStep.StepOnTwig("trigger:4", PuzzleTile(52, 53)),
                HerdStep.TalkFrom(PuzzleTile(48, 54), Direction.EAST, HerdOutcome.Caught),
            ),
            HgssIlexFarfetchd.plan(2, start),
        )
    }

    @Test
    fun aTwigOnlyWorksWhileActiveAndBirdOneLooksLeftAfterRunningDown() {
        val s = State(Spot.TOP_LEFT, twigs = 0, blind = false)
        assertNull(HgssIlexFarfetchd.step(1, s, 0))
        // From the top-right, talked to from the west: it runs down and looks up; the twigs then only make it look left.
        val down = HgssIlexFarfetchd.talk(1, State(Spot.TOP_RIGHT, 0, false), Direction.EAST).next
        assertTrue(down.facingUp)
        val stepped = assertNotNull(HgssIlexFarfetchd.step(1, down, 0)).next
        assertFalse(stepped.blind)
        assertEquals(0, stepped.twigs)
    }

    @Test
    fun theForestPuzzleListsTheBirdsWithTheirLiveTwigs() {
        val vars = mapOf(0x4099 to 1, 0x409B to 2, 0x409A to 2, 0x409C to 2, 0x409D to 2, 0x409E to 1)
        val reads = object : HgssPuzzles.Reads {
            override fun variable(id: Int) = vars[id] ?: 0
            override fun flag(id: Int) = false
            override fun gymmick(): ByteArray? = null
        }
        val puzzle = assertNotNull(
            HgssPuzzles.read(117, reads, null, listOf(HgssIlexFarfetchd.ObjectAt(0, 25, 62), HgssIlexFarfetchd.ObjectAt(2, 41, 54))),
        )
        assertEquals(PuzzleKind.HERDING, puzzle.kind)
        assertEquals(listOf("person:0", "person:2"), puzzle.herds.map { it.id })
        assertEquals(listOf(true, false), puzzle.herds[0].twigs.map { it.active })
        assertEquals(2, puzzle.herds[0].plan.size)
        // A bird already found is no longer on the map: no herd for it.
        assertEquals(listOf("person:2"), HgssPuzzles.read(117, reads, null, listOf(HgssIlexFarfetchd.ObjectAt(2, 41, 54)))!!.herds.map { it.id })
    }
}
