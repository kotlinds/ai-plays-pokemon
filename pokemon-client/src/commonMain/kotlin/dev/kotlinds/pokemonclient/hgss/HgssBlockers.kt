package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.state.Blocker
import dev.kotlinds.pokemonclient.state.StoryCondition

/**
 * People and coordinate triggers of the current map that block a way, with why and how to get past.
 *
 * Everything comes from the RAM (the zone's live map objects and its loaded coordinate events, see
 * [HgssReader]): an object of the zone whose event flag is set isn't spawned, so every live object is one whose
 * event isn't done yet.
 * - **Curated** ([curated]): the well-known story blockers, from the decomp scripts, with our walkthrough reason.
 *   A curated person blocks while it is on the map and its [Curated.liftedWhen] doesn't hold yet (some never
 *   leave: they step aside once the condition holds); a curated trigger blocks while its var condition holds.
 * - **Generic**: any other trigger whose var condition holds now (stepping on it starts a story scene; skipped when
 *   a map has more than [MAX_GENERIC_TRIGGERS] of them: gym pits, hideout traps), and any other person that will
 *   leave after a story event (non-zero event flag, not a trainer) standing in a one-tile passage of the terrain.
 *
 * Targets are `person:<event id>` (the object's local id, as in [dev.kotlinds.pokemonclient.state.FieldObject.id])
 * and `trigger:<index>` (index in the zone's coordinate events).
 */
object HgssBlockers {

    /** What a curated blocker is attached to, on map [mapId] (MAP_ ids of include/constants/maps.h). */
    sealed interface Target {
        val mapId: Int

        /** The person with local event id [objectId] (obj_<map>_... index of the zone's events). */
        data class Person(override val mapId: Int, val objectId: Int) : Target

        /** The coordinate trigger at [index] of the zone's coordinate events. */
        data class Trigger(override val mapId: Int, val index: Int) : Target
    }

    /** A known blocker: [reason] says why it blocks and how to lift it; it stops blocking once [liftedWhen] holds. */
    data class Curated(val target: Target, val reason: String, val liftedWhen: StoryCondition? = null)

    /** The blockers of the map the player stands on. Empty outside the field. */
    fun of(state: HgssState): List<Blocker> {
        val mapId = state.location?.mapId ?: return emptyList()
        val around = state.surroundings ?: return emptyList()
        val story = state.story ?: StoryInfo()
        val out = mutableListOf<Blocker>()
        for (o in around.objects) {
            if (o.hidden || o.kind != "npc") continue
            val zone = o.mapId.takeIf { it >= 0 } ?: mapId
            val known = byPerson[Target.Person(zone, o.id)]
            if (known != null) {
                if (known.liftedWhen?.holds(story) != true) out += Blocker("person:${o.id}", known.reason)
            } else if (zone == mapId && o.eventFlag != 0 && o.type !in HgssZoneEvents.TRAINER_TYPES && standsInPassage(around.grid, o.x, o.z)) {
                out += Blocker(
                    "person:${o.id}",
                    "Stands in a narrow passage and only leaves after a story event: follow the story goal (or talk to them to learn what they wait for).",
                )
            }
        }
        val active = around.triggers.filter { it.active == true }
        val (known, unknown) = active.partition { Target.Trigger(mapId, it.index) in byTrigger }
        for (t in known) out += Blocker("trigger:${t.index}", byTrigger.getValue(Target.Trigger(mapId, t.index)).reason)
        // Many armed triggers on one map are a mechanism (gym pits, hideout traps, puzzle tiles), not story gates.
        if (unknown.size <= MAX_GENERIC_TRIGGERS) for (t in unknown) {
            out += Blocker(
                "trigger:${t.index}",
                "Stepping here (x ${t.x}..${t.x + maxOf(t.width, 1) - 1}, y ${t.z}..${t.z + maxOf(t.height, 1) - 1}) starts a story scene now; it may stop you or send you back until the story moves on.",
            )
        }
        return out
    }

    /**
     * True when (x, z) is a walkable tile between two walls (west/east or north/south) with a way on at least one
     * open side: whoever stands there closes the passage.
     */
    internal fun standsInPassage(grid: LocalGrid?, x: Int, z: Int): Boolean {
        grid ?: return false
        fun open(c: Char) = c == '.' || c == '"'
        fun wall(c: Char) = c == '#' || c == '-' || c == '~'
        if (!open(grid.at(x, z))) return false
        val west = grid.at(x - 1, z)
        val east = grid.at(x + 1, z)
        val north = grid.at(x, z - 1)
        val south = grid.at(x, z + 1)
        return (wall(west) && wall(east) && (open(north) || open(south))) ||
            (wall(north) && wall(south) && (open(west) || open(east)))
    }

    /** Above this many armed, non-curated triggers on a map, they are a puzzle or trap mechanism: not reported. */
    private const val MAX_GENERIC_TRIGGERS = 3

    // Map ids (include/constants/maps.h).
    private const val MAP_ECRUTEAK_GYM = 80
    private const val MAP_ROUTE_32 = 36
    private const val MAP_ROUTE_36 = 40
    private const val MAP_AZALEA = 74
    private const val MAP_BURNED_TOWER_1F = 7
    private const val MAP_BURNED_TOWER_B1F = 217
    private const val MAP_BELL_TOWER_BARRIER_STATION = 83
    private const val MAP_BELL_TOWER_1F = 111
    private const val MAP_NEW_BARK = 60
    private const val MAP_OLIVINE_LIGHTHOUSE_TOP = 225
    private const val MAP_MAHOGANY = 87
    private const val MAP_ROUTE_43_GATE = 245
    private const val MAP_RADIO_TOWER_1F = 112
    private const val MAP_RADIO_TOWER_3F = 187
    private const val MAP_GOLDENROD_TUNNEL_B1F = 199
    private const val MAP_BLACKTHORN = 89
    private const val MAP_ROUTE_11 = 19
    private const val MAP_ROUTE_19 = 91
    private const val MAP_ROUTE_24 = 28
    private const val MAP_VIRIDIAN = 50
    private const val MAP_LEAGUE_GATE = 299

    /** The well-known blockers, from the decomp's scripts and zone events (files/fielddata). */
    val curated: List<Curated> = listOf(
        // Route 32 north exit: scr_seq_0232_R32.s turns the player back until the Zephyr Badge and the Togepi Egg.
        Curated(
            Target.Trigger(MAP_ROUTE_32, 0),
            "Elm's aide stops you here until you have the Zephyr Badge AND the egg from Elm's aide in the Violet City Poké Mart.",
        ),
        // Azalea: the Rocket on the Gym door (obj_T23_rocketm_2, FLAG_UNK_1A9) leaves once Proton is beaten in the well.
        Curated(
            Target.Person(MAP_AZALEA, 1),
            "A Team Rocket grunt blocks the Gym door: clear the Slowpoke Well (north of town, Kurt opens it) to make him leave.",
            StoryCondition.FlagSet(HgssStoryTable.Flags.BEAT_PROTON_IN_WELL),
        ),
        Curated(
            Target.Person(MAP_AZALEA, 0),
            "A Team Rocket grunt guards the Slowpoke Well: talk to Kurt (house in the north-west of Azalea) first; he goes in and the way opens.",
            StoryCondition.FlagSet(HgssStoryTable.Flags.AZALEA_ROCKETS_BEATEN),
        ),
        Curated(
            Target.Trigger(MAP_AZALEA, 0),
            "Your rival waits at the west exit toward Ilex Forest: stepping here starts the battle (heal first).",
        ),
        // Route 36: the Sudowoodo (obj_R36_usokky) needs the SquirtBottle (Goldenrod Flower Shop, after the Plain Badge).
        Curated(
            Target.Person(MAP_ROUTE_36, 4),
            "The odd tree (a Sudowoodo) blocks Route 36: water it with the SquirtBottle (Goldenrod Flower Shop, given once you have the Plain Badge) and defeat or catch it.",
            StoryCondition.FlagSet(HgssStoryTable.Flags.SUDOWOODO_GONE),
        ),
        Curated(
            Target.Trigger(MAP_BURNED_TOWER_1F, 1),
            "Your rival waits here in the Burned Tower: stepping here starts the battle (heal first).",
        ),
        Curated(
            Target.Trigger(MAP_BURNED_TOWER_B1F, 0),
            "The legendary beasts sleep here: stepping here starts the scene where they flee (it opens the Ecruteak Gym).",
        ),
        // The gym's old man (obj gsoldman1, FLAG_UNK_247) walks the player out until the Burned Tower is done.
        Curated(
            Target.Person(MAP_ECRUTEAK_GYM, 6),
            "The Gym is closed: Morty is at the Burned Tower (north-west of Ecruteak). Go there first (rival battle, then the basement scene).",
            StoryCondition.VarAtLeast(HgssStoryTable.Vars.BURNED_TOWER_BEASTS, 1),
        ),
        // Olivine Lighthouse top: an invisible "stop" object (FLAG_UNK_1D8) keeps the player by Jasmine until they talk.
        Curated(
            Target.Person(MAP_OLIVINE_LIGHTHOUSE_TOP, 4),
            "Jasmine stands by the sick Ampharos: talk to her (the way on opens after that).",
            StoryCondition.FlagSet(HgssStoryTable.Flags.TALKED_TO_JASMINE_LIGHTHOUSE),
        ),
        // Mahogany: the man on the Gym door (FLAG_UNK_1F9) leaves once the Rocket hideout is cleared.
        Curated(
            Target.Person(MAP_MAHOGANY, 2),
            "A man blocks the Gym door: clear the Team Rocket hideout under the souvenir shop first (meet Lance at the Lake of Rage, north on Route 43).",
            StoryCondition.FlagSet(HgssStoryTable.Flags.MAHOGANY_GYM_DOOR_FREE),
        ),
        // Mahogany: the Rage Candy Bar seller pushes the player back from the Route 44 exit until the Radio Tower is freed.
        Curated(
            Target.Trigger(MAP_MAHOGANY, 0),
            "The east exit to Route 44 is closed: a man stops you and sends you back until Team Rocket is driven out of the Goldenrod Radio Tower.",
        ),
        Curated(
            Target.Person(MAP_ROUTE_43_GATE, 0),
            "Team Rocket grunts charge a toll in this gate (pay or go around); they leave once their Mahogany hideout is cleared.",
            StoryCondition.FlagSet(HgssStoryTable.Flags.ROCKET_HIDEOUT_CLEARED),
        ),
        Curated(
            Target.Person(MAP_ROUTE_43_GATE, 1),
            "Team Rocket grunts charge a toll in this gate (pay or go around); they leave once their Mahogany hideout is cleared.",
            StoryCondition.FlagSet(HgssStoryTable.Flags.ROCKET_HIDEOUT_CLEARED),
        ),
        // Radio Tower 1F: the grunt on the stairs only lets a disguised player up (takeover var 3).
        Curated(
            Target.Person(MAP_RADIO_TOWER_1F, 6),
            "A Rocket grunt guards the stairs: he only lets someone in a Team Rocket uniform up. Get it in the Goldenrod Underground (Basement), from the Rocket hideout there.",
            StoryCondition.VarAtLeast(HgssStoryTable.Vars.ROCKET_TAKEOVER, 3),
        ),
        Curated(
            Target.Person(MAP_RADIO_TOWER_3F, 7),
            "A locked shutter: it opens with the Card Key the Radio Director gives you in the Goldenrod Underground Warehouse.",
        ),
        Curated(
            Target.Person(MAP_RADIO_TOWER_3F, 8),
            "A locked shutter: it opens with the Card Key the Radio Director gives you in the Goldenrod Underground Warehouse.",
        ),
        Curated(
            Target.Person(MAP_GOLDENROD_TUNNEL_B1F, 11),
            "A locked door: it opens with the Basement Key (from the Rocket executive on Radio Tower 5F).",
        ),
        // Blackthorn: the Gym guard leaves after the Radio Tower; the Dragon's Den guard after Clair is beaten.
        Curated(
            Target.Person(MAP_BLACKTHORN, 0),
            "A man blocks the Gym door: he leaves once Team Rocket is out of the Goldenrod Radio Tower.",
            StoryCondition.FlagSet(HgssStoryTable.Flags.BEAT_RADIO_TOWER_ROCKETS),
        ),
        Curated(
            Target.Person(MAP_BLACKTHORN, 1),
            "A man guards the way to the Dragon's Den (behind the Gym): beat Clair at the Gym first.",
            StoryCondition.FlagSet(HgssStoryTable.Flags.BEAT_CLAIR),
        ),
        // Bell Tower: the barrier station sage (Fog Badge, steps aside for the visit) and the 1F sage (Rainbow Wing).
        Curated(
            Target.Person(MAP_BELL_TOWER_BARRIER_STATION, 0),
            "A sage guards the way to the Bell Tower: he lets you through (for this visit) if you have the Fog Badge; talk to him.",
            StoryCondition.FlagSet(HgssStoryTable.Flags.HO_OH_DONE),
        ),
        Curated(
            Target.Trigger(MAP_BELL_TOWER_1F, 0),
            "The Bell Tower's sage stops you: only someone holding the Rainbow Wing (from the Goldenrod Radio Director) may climb.",
        ),
        Curated(
            Target.Trigger(MAP_BELL_TOWER_1F, 1),
            "The Bell Tower's sage stops you: only someone holding the Rainbow Wing (from the Goldenrod Radio Director) may climb.",
        ),
        // New Bark east exit (the way to Route 27 and the League): Mom, then Lyra, stop the player.
        Curated(
            Target.Trigger(MAP_NEW_BARK, 2),
            "Mom stops you at the east exit: visit Prof. Elm's lab first.",
        ),
        Curated(
            Target.Trigger(MAP_NEW_BARK, 3),
            "Your friend stops you at the east exit: the Kimono Girls' story must be finished first (Clear Bell, then Ho-Oh at the top of the Bell Tower).",
        ),
        // Kanto.
        Curated(
            Target.Trigger(MAP_ROUTE_24, 0),
            "The Rocket grunt who fled the Cerulean Gym waits here: stepping here starts the battle.",
        ),
        *listOf(4, 7, 8, 9).map { id ->
            Curated(
                Target.Person(MAP_ROUTE_11, id),
                "A sleeping Snorlax blocks the entrance of Diglett's Cave: get the Expansion Card (Lavender Radio Station director, after restoring the Power Plant), tune the Pokégear radio to the Poké Flute channel next to it, then defeat or catch it.",
                StoryCondition.FlagSet(HgssStoryTable.Flags.SNORLAX_BEATEN),
            )
        }.toTypedArray(),
        Curated(
            Target.Person(MAP_VIRIDIAN, 0),
            "An old man stands in front of the Gym door: the Gym leader (Blue) is away on Cinnabar Island; talk to him there once you have seven Kanto badges.",
            StoryCondition.FlagSet(HgssStoryTable.Flags.BLUE_OPENED_VIRIDIAN_GYM),
        ),
        Curated(
            Target.Person(MAP_LEAGUE_GATE, 2),
            "A guard closes the way west to Route 22 and Viridian City until you have been to western Kanto (through Diglett's Cave).",
            StoryCondition.FlagSet(HgssStoryTable.Flags.UNLOCKED_WEST_KANTO),
        ),
        Curated(
            Target.Trigger(MAP_LEAGUE_GATE, 2),
            "A guard closes the way west to Route 22 and Viridian City until you have been to western Kanto (through Diglett's Cave).",
        ),
        Curated(
            Target.Person(MAP_LEAGUE_GATE, 1),
            "A guard closes the way to Route 28 and Mt. Silver: only Prof. Oak's permission (all 16 badges) opens it.",
            StoryCondition.FlagSet(HgssStoryTable.Flags.UNLOCKED_MT_SILVER),
        ),
        Curated(
            Target.Trigger(MAP_LEAGUE_GATE, 1),
            "A guard closes the way to Route 28 and Mt. Silver: only Prof. Oak's permission (all 16 badges) opens it.",
        ),
        *listOf(13, 14).map { id ->
            Curated(
                Target.Person(MAP_ROUTE_19, id),
                "Workmen close the sea route south of Fuchsia City until Blaine is beaten: reach Cinnabar Island from Pallet Town (Route 21) instead.",
                StoryCondition.HasBadge(HgssStoryTable.VOLCANO),
            )
        }.toTypedArray(),
    )

    private val byPerson: Map<Target.Person, Curated> = curated.filter { it.target is Target.Person }.associateBy { it.target as Target.Person }
    private val byTrigger: Map<Target.Trigger, Curated> = curated.filter { it.target is Target.Trigger }.associateBy { it.target as Target.Trigger }

    /** Every flag the curated blockers read. */
    val flagIds: Set<Int> get() = curated.flatMapTo(mutableSetOf()) { it.liftedWhen?.flagIds().orEmpty() }

    /** Every var the curated blockers read. */
    val varIds: Set<Int> get() = curated.flatMapTo(mutableSetOf()) { it.liftedWhen?.varIds().orEmpty() }
}
