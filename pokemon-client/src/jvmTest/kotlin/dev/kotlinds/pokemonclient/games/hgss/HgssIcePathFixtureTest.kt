package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.state.FieldObjectKind
import dev.kotlinds.pokemonclient.state.PuzzleKind
import dev.kotlinds.pokemonclient.state.PuzzleTile
import dev.kotlinds.pokemonclient.world.LiveObject
import dev.kotlinds.pokemonclient.world.Node
import dev.kotlinds.pokemonclient.world.Overlay
import dev.kotlinds.pokemonclient.world.Pathfinder
import dev.kotlinds.pokemonclient.world.RouteOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The Ice Path boulders on real saves (bench, DeSmuME, 8-badge save; ROM checks skipped without `POKEMON_ROM`):
 * - `pz_icepath_b1f`: B1F (map 237) at 23,6; during the run boulder 1 (object 0) was pushed into its hole (11,10),
 *   the three others are still on their starting tiles;
 * - `pz_icepath_b2f`: B2F (map 238) at 43,23, with that boulder fallen at 12,13.
 * Live, from B2F's arrival 26,12, `go_to warp:2` (the stairs at 16,17) slid on the ice, stopping on the fallen
 * boulder, and went down to B3F (5,12) in one call.
 */
class HgssIcePathFixtureTest {

    private fun field(name: String) = assertNotNull(HgssGame(HgssVersion.HEARTGOLD_US).state(HgssFixtures.load(name)).field)

    @Test
    fun `B1F lists each boulder with its hole and which ones fell`() {
        val reader = HgssReader(HgssFixtures.load("pz_icepath_b1f"), HgssVersion.HEARTGOLD_US)
        val puzzle = assertNotNull(HgssPuzzles.read(237, HgssPuzzles.reads(reader), null))
        assertEquals(PuzzleKind.BOULDER_HOLES, puzzle.kind)
        assertEquals(listOf(true, false, false, false), puzzle.boulderHoles.map { it.fallen })
        assertEquals(PuzzleTile(11, 10), puzzle.boulderHoles.first { it.boulder == "person:0" }.hole)
        // The boulders still there run the Strength script: they are pushable boulders.
        val boulders = field("pz_icepath_b1f").objects.filter { it.kind == FieldObjectKind.OBSTACLE }
        assertEquals(setOf("person:1", "person:2", "person:3", "person:4"), boulders.filter { it.obstacle != null }.map { it.id }.toSet())
    }

    @Test
    fun `the fallen boulder on B2F is a fixed stopper that the way to B3F needs`() {
        val field = field("pz_icepath_b2f")
        val rock = field.objects.single { it.id == "person:0" }
        assertEquals(12 to 13, rock.x to rock.y)
        // Not a Strength boulder: Strength doesn't work on B2F (field_move.c), its script isn't std_field_strength.
        assertNull(rock.obstacle)
        val area = assertNotNull(HgssWorldRom.require().areaOf(238))
        val withRock = Overlay(listOf(LiveObject(rock.x, rock.y, null)))
        // From the arrival from B1F (26,12) to the stairs down (16,17).
        val found = Pathfinder(area, withRock).route(Node(26, 12), RouteOptions(), setOf(16 to 17)) { it.x == 16 && it.y == 17 }
        assertIs<Pathfinder.Result.Found>(found)
        // Without the fallen boulder, the slides never stop next to the stairs.
        assertIs<Pathfinder.Result.Failed>(Pathfinder(area).route(Node(26, 12), RouteOptions(), setOf(16 to 17)) { it.x == 16 && it.y == 17 })
    }
}
