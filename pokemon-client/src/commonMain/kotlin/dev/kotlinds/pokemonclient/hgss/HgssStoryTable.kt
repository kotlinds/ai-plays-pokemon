package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.state.StoryCondition
import dev.kotlinds.pokemonclient.state.StoryCondition.FlagSet
import dev.kotlinds.pokemonclient.state.StoryCondition.HasBadge
import dev.kotlinds.pokemonclient.state.StoryCondition.Or
import dev.kotlinds.pokemonclient.state.StoryCondition.VarAtLeast
import dev.kotlinds.pokemonclient.state.StoryFacts

/** One step of the HeartGold story: [id] is stable (`johto:...` / `kanto:...`), [description] is our walkthrough text. */
data class HgssStoryStep(
    val id: String,
    val description: String,
    /** True once the step is done (and stays true: only flags that are never cleared again, vars that only grow). */
    val done: StoryCondition,
    /** A more precise [description] when the player stands on one of these maps (MAP_ id → text). */
    val descriptionAt: Map<Int, String> = emptyMap(),
    /**
     * A gate of the story that can only be passed once every earlier step is done (a Johto badge, the Hall of
     * Fame...): once it is done, the earlier steps count as done too (see [HgssStoryTable.steps]).
     */
    val checkpoint: Boolean = false,
) {
    /** What to do, said from map [mapId] when known. */
    fun describe(mapId: Int?): String = mapId?.let { descriptionAt[it] } ?: description
}

/**
 * The whole main story of HeartGold, in order: Johto (starter → 8 badges → Elite Four and Lance) then Kanto (S.S.
 * Aqua → 8 Kanto badges → Red at Mt. Silver). The goal is the first step that isn't [HgssStoryStep.done].
 *
 * Every condition comes from the decomp (pret/pokeheartgold): include/constants/flags.h, vars.h and the scripts that
 * set them, files/fielddata/script/scr_seq/scr_seq_<n>_<map>.s (cited per constant). Rules followed:
 * - a flag is only used when no script clears it again (several "hide" flags are reset by the Hall of Fame);
 * - a var is compared with >= only when it only grows along the story;
 * - a step that the game lets the player skip or do later is written so that a later milestone also counts as done
 *   (an [Or] with the next badge), so a skipped optional event never hides the real next step.
 * SoulSilver differences (Tidal Bell, Lugia) are not covered: the Clear Bell / Ho-Oh steps are HeartGold's.
 */
object HgssStoryTable {

    /** Flag ids (include/constants/flags.h), named after what they mean here. */
    object Flags {
        const val GOT_BAG = HgssProgress.FLAG_GOT_BAG
        const val GOT_STARTER = HgssProgress.FLAG_GOT_STARTER
        const val GOT_POKEDEX = HgssProgress.FLAG_GOT_POKEDEX
        const val MET_ELM = HgssProgress.FLAG_MET_ELM
        const val GOT_POKEGEAR = HgssProgress.FLAG_GOT_POKEGEAR
        const val ELM_PANIC_CALL = HgssProgress.FLAG_ELM_PANIC_CALL
        const val RIVAL_CHERRYGROVE = HgssProgress.FLAG_RIVAL_CHERRYGROVE
        const val GAVE_EGG_TO_ELM = HgssProgress.FLAG_GAVE_EGG_TO_ELM
        const val CATCHING_TUTORIAL = HgssProgress.FLAG_CATCHING_TUTORIAL
        const val BEAT_SPROUT_ELDER = HgssProgress.FLAG_BEAT_SPROUT_ELDER

        /** FLAG_GOT_EGG_FROM_ELMS_ASSISTANT: Togepi Egg (scr_seq_0858_T22FS0101.s:54). */
        const val GOT_TOGEPI_EGG = 0x70

        /** FLAG_UNK_1A9: Proton beaten in the Slowpoke Well, hides the grunts and the Gym door guard (scr_seq_0060_D26R0102.s:58). */
        const val BEAT_PROTON_IN_WELL = 0x1A9

        /** FLAG_BEAT_AZALEA_ROCKETS: Slowpoke Well cleared (scr_seq_0060_D26R0102.s:88). */
        const val AZALEA_ROCKETS_BEATEN = 0x7B

        /** FLAG_FOUND_FIRST/SECOND_FARFETCHD (scr_seq_0092_D36R0101.s:246, :634). */
        const val FOUND_FIRST_FARFETCHD = 0x7D
        const val FOUND_SECOND_FARFETCHD = 0x7E

        /** FLAG_GOT_HM01: Cut (scr_seq_0092_D36R0101.s:1294). */
        const val GOT_HM01 = 0x80

        /** FLAG_UNK_0B5: the Route 36 Sudowoodo was defeated or caught (scr_seq_0243_R36.s:110). */
        const val SUDOWOODO_GONE = 0xB5

        /** FLAG_GOT_HM03: Surf, from the Ecruteak Dance Theater's old man (scr_seq_0928_T27R0501.s:380). */
        const val GOT_HM03 = 0xA2

        /** FLAG_UNK_1D8: talked to Jasmine at the top of the Olivine Lighthouse (scr_seq_0066_D27R0107.s:59). */
        const val TALKED_TO_JASMINE_LIGHTHOUSE = 0x1D8

        /** FLAG_GOT_HM02: Fly, from Chuck's wife (scr_seq_0875_T24.s:40). */
        const val GOT_HM02 = 0xBB

        /** FLAG_GOT_SECRETPOTION: Cianwood pharmacy (scr_seq_0881_T24R0501.s:40). */
        const val GOT_SECRETPOTION = 0xB9

        /** FLAG_GOT_RED_SCALE: the Lake of Rage Gyarados was caught or defeated (scr_seq_0938_T29.s:303). */
        const val GOT_RED_SCALE = 0xC9

        /** FLAG_ROCKET_HIDEOUT_CLEARED: Mahogany hideout cleared, Lance gives HM05 Whirlpool (scr_seq_0090_D35R0103.s:710). */
        const val ROCKET_HIDEOUT_CLEARED = 0xCA

        /** FLAG_UNK_1F9: hides the man standing on the Mahogany Gym door (scr_seq_0090_D35R0103.s:713). */
        const val MAHOGANY_GYM_DOOR_FREE = 0x1F9

        /** FLAG_BEAT_RADIO_TOWER_ROCKETS: Archer beaten at the top of the Radio Tower (scr_seq_0034_D23R0106.s:135). */
        const val BEAT_RADIO_TOWER_ROCKETS = 0xC6

        /** FLAG_UNK_093: the Radio Director's Rainbow Wing, needed to enter the Bell Tower (scr_seq_0034_D23R0106.s:79). */
        const val GOT_RAINBOW_WING = 0x93

        /** FLAG_HIDE_ITEMBALL_D39R0101_HM07: HM07 Waterfall picked up in Ice Path 1F. */
        const val GOT_HM07 = 0x457

        /** FLAG_UNK_0D1: Clair beaten in the Blackthorn Gym (scr_seq_0943_T30GYM0101.s:105). */
        const val BEAT_CLAIR = 0xD1

        /** FLAG_UNK_0EA: Dragon's Den elder's test passed, Rising Badge (scr_seq_0112_D44R0103.s:184). */
        const val PASSED_DRAGONS_DEN = 0xEA

        /** FLAG_UNK_103: the five Kimono Girls beaten, Clear Bell received (scr_seq_0928_T27R0501.s:811). */
        const val BEAT_KIMONO_GIRLS = 0x103

        /** FLAG_UNK_10A: the Kimono Girls' dance on the Bell Tower roof (scr_seq_0021_D17R0110.s:341). */
        const val BELL_TOWER_DANCE = 0x10A

        /** FLAG_UNK_108: Ho-Oh caught or defeated (scr_seq_0021_D17R0110.s:83). */
        const val HO_OH_DONE = 0x108

        /** FLAG_SYS_FLYPOINT_VICTORY_ROAD: reached the Pokémon League Reception Gate (scr_seq_0213_R22R0101.s:29). */
        const val REACHED_LEAGUE_GATE = 0x9D1

        /** FLAG_SYS_FLYPOINT_INDIGO: reached the Indigo Plateau (fly point set on entering the town). */
        const val REACHED_INDIGO_PLATEAU = 0x9B9

        /** FLAG_GAME_CLEAR: entered the Hall of Fame (src/sys_flags.c:69). The Elite Four flags are reset at each attempt. */
        const val GAME_CLEAR = 0x964

        /** FLAG_GOT_SS_TICKET_FROM_ELM (scr_seq_0843_T20R0101.s:396). */
        const val GOT_SS_TICKET = 0xF2

        /** FLAG_UNK_168: first arrival at Vermilion port (scr_seq P01R0104:19). */
        const val ARRIVED_IN_VERMILION = 0x168

        /** FLAG_GOT_POWER_PLANT_MANAGERS_STORY (R10R0202:28). */
        const val POWER_PLANT_STORY = 0x120

        /** FLAG_RESTORED_POWER: Machine Part returned (R10R0202:54). */
        const val RESTORED_POWER = 0x118

        /** FLAG_GOT_EXPN_CARD: Poké Flute channel (T05R0701:35). */
        const val GOT_EXPN_CARD = 0x11F

        /** FLAG_SNORLAX_MEET: the Route 11 Snorlax was defeated or caught (R11:58). */
        const val SNORLAX_BEATEN = 0xF9

        /** FLAG_UNLOCKED_WEST_KANTO: first visit to Viridian City (T02:44). */
        const val UNLOCKED_WEST_KANTO = 0x12C

        /** FLAG_UNK_129: Blue on Cinnabar Island sent you to his gym (T09:254). */
        const val BLUE_OPENED_VIRIDIAN_GYM = 0x129

        /** FLAG_UNLOCKED_MT_SILVER: Oak's permission (T01R0301:205). */
        const val UNLOCKED_MT_SILVER = 0x12B
    }

    /** Var ids (include/constants/vars.h). */
    object Vars {
        const val PLAYERS_HOUSE_1F = HgssProgress.VAR_PLAYERS_HOUSE_1F
        const val ELMS_LAB = HgssProgress.VAR_ELMS_LAB
        const val NEW_BARK_TOWN = HgssProgress.VAR_NEW_BARK_TOWN
        const val NEW_BARK_WEST_EXIT = HgssProgress.VAR_NEW_BARK_WEST_EXIT
        const val CHERRYGROVE = HgssProgress.VAR_CHERRYGROVE
        const val MR_POKEMONS_HOUSE = HgssProgress.VAR_MR_POKEMONS_HOUSE
        const val ROUTE_30 = HgssProgress.VAR_ROUTE_30

        /** VAR_UNK_408D: 1 once Elm's aide let you through the Route 32 exit (scr_seq_0232_R32.s:200). */
        const val ROUTE_32_GATE = 0x408D

        /** VAR_UNK_4075: 2 once the rival was battled at Azalea's west exit (scr_seq_0866_T23.s:126). */
        const val AZALEA_RIVAL = 0x4075

        /** VAR_UNK_40A6: 1 once the rival was battled in the Burned Tower (scr_seq_0023_D18R0101.s:56). */
        const val BURNED_TOWER_RIVAL = 0x40A6

        /** VAR_UNK_40A1: 1 once the beasts fled in the Burned Tower basement (scr_seq_0024_D18R0102.s:132). */
        const val BURNED_TOWER_BEASTS = 0x40A1

        /** VAR_UNK_4079: Ecruteak scene; >= 2 after the Burned Tower (the Gym opens). */
        const val ECRUTEAK = 0x4079

        /** VAR_SCENE_LIGHTHOUSE_JASMINE: 1 talked to Jasmine, 2 medicine given, 3 back in Olivine (scr_seq_0066_D27R0107.s). */
        const val LIGHTHOUSE_JASMINE = 0x40A5

        /** VAR_UNK_40A8: 1 once Lance was met at the Lake of Rage, 2 in the hideout (scr_seq_0938_T29.s:361). */
        const val LANCE_LAKE_OF_RAGE = 0x40A8

        /** VAR_SCENE_ROCKET_TAKEOVER: 2 takeover active, 3 Rocket disguise, 4 rival beaten on 1F, 5 done (scr_seq_0034_D23R0106.s:134). */
        const val ROCKET_TAKEOVER = 0x4077

        /** VAR_UNK_407A: 1 once the Radio Tower is freed (the Mahogany Route 44 exit opens, scr_seq_0034_D23R0106.s:142). */
        const val MAHOGANY_EAST_EXIT = 0x407A

        /** VAR_SCENE_NEW_BARK_EAST_EXIT: 1 Rising Badge, 2 visited Elm, 3 Ho-Oh done (scr_seq_0843_T20R0101.s:949). */
        const val NEW_BARK_EAST_EXIT = 0x4081

        /** VAR_UNK_410C: Dance Theater; 4 Kimono Girl's call in Ecruteak (scr_seq_0920_T27.s:122), 6 Clear Bell. */
        const val DANCE_THEATER = 0x410C

        /** VAR_UNK_40F3: 1 once the Bell Tower 1F sage let you in with the Rainbow Wing. */
        const val BELL_TOWER_ENTRY = 0x40F3

        /** VAR_UNK_40CB: S.S. Aqua; 6 once the granddaughter is found (P01R0306:76). */
        const val SS_AQUA = 0x40CB

        /** VAR_SCENE_ROUTE_24_ROCKET: 3 once the Rocket on Route 24 is beaten, 4 once the Machine Part is picked up. */
        const val ROUTE_24_ROCKET = 0x4087

        /** VAR_UNK_4088: Misty; 2 after the Route 25 date (R25:170). */
        const val MISTY = 0x4088

        /** VAR_UNK_40FD: 1 once Red was beaten at the Mt. Silver summit (D41R0108:28). */
        const val BEAT_RED = 0x40FD
    }

    // Badges (PlayerProfile bits, include/constants/badge.h).
    const val ZEPHYR = 0
    const val HIVE = 1
    const val PLAIN = 2
    const val FOG = 3
    const val STORM = 4
    const val MINERAL = 5
    const val GLACIER = 6
    const val RISING = 7
    const val BOULDER = 8
    const val CASCADE = 9
    const val THUNDER = 10
    const val RAINBOW = 11
    const val SOUL = 12
    const val MARSH = 13
    const val VOLCANO = 14
    const val EARTH = 15

    private fun flag(id: Int) = FlagSet(id)
    private fun atLeast(id: Int, value: Int) = VarAtLeast(id, value)
    private fun badge(index: Int) = HasBadge(index)

    private const val MAP_PLAYER_HOUSE_1F = 63
    private const val MAP_PLAYER_HOUSE_2F = 64

    /**
     * Every step, in story order. The `done` of a step also holds once the next [HgssStoryStep.checkpoint] is done:
     * a step the game let the player skip (or whose flag a save doesn't have) never hides the real next step.
     */
    val steps: List<HgssStoryStep> by lazy {
        val written = johtoFirstHalf + johtoSecondHalf + kanto
        // From the end: each step's done also accepts the (already extended) done of the next checkpoint.
        var nextCheckpoint: StoryCondition? = null
        written.asReversed().map { step ->
            val done = nextCheckpoint?.let { Or(step.done, it) } ?: step.done
            if (step.checkpoint) nextCheckpoint = done
            step.copy(done = done)
        }.asReversed()
    }

    private val johtoFirstHalf: List<HgssStoryStep> = listOf(
        // region New Bark Town → first badge (the former HgssProgress.nextGoal)
        HgssStoryStep(
            "johto:talk_to_mom", "Talk to Mom on the first floor of your house",
            Or(flag(Flags.GOT_BAG), atLeast(Vars.PLAYERS_HOUSE_1F, 1)),
            mapOf(MAP_PLAYER_HOUSE_2F to "Go downstairs (stairs in the top-left corner) and talk to Mom"),
        ),
        HgssStoryStep(
            "johto:leave_house", "Leave the house (exit mat at the bottom of the first floor)",
            Or(atLeast(Vars.PLAYERS_HOUSE_1F, 2), flag(Flags.MET_ELM), atLeast(Vars.ELMS_LAB, 1)),
        ),
        HgssStoryStep(
            "johto:meet_elm", "Go to Prof. Elm's lab (the big building in the west part of New Bark Town) and talk to him",
            Or(flag(Flags.MET_ELM), atLeast(Vars.ELMS_LAB, 1)),
        ),
        HgssStoryStep(
            "johto:choose_starter", "Choose a starter Pokémon: examine the Poké Balls on the machine in Elm's lab",
            flag(Flags.GOT_STARTER),
        ),
        HgssStoryStep("johto:lab_aide", "Walk to the lab's exit (Elm's aide has something for you)", atLeast(Vars.ELMS_LAB, 2)),
        HgssStoryStep("johto:leave_lab", "Leave Elm's lab", atLeast(Vars.NEW_BARK_TOWN, 2)),
        HgssStoryStep(
            "johto:pokegear", "Go home (the house east of Elm's lab) and talk to Mom to get your Pokégear",
            flag(Flags.GOT_POKEGEAR),
            mapOf(MAP_PLAYER_HOUSE_1F to "Talk to Mom (she has your Pokégear)", MAP_PLAYER_HOUSE_2F to "Talk to Mom (she has your Pokégear)"),
        ),
        HgssStoryStep("johto:leave_new_bark", "Leave New Bark Town to the west, toward Route 29", atLeast(Vars.NEW_BARK_WEST_EXIT, 1)),
        HgssStoryStep(
            "johto:guide_gent", "Follow Route 29 west to Cherrygrove City and talk to the old man there (Guide Gent)",
            atLeast(Vars.CHERRYGROVE, 1),
        ),
        HgssStoryStep(
            "johto:map_card", "Head north out of Cherrygrove City toward Route 30 (the Guide Gent has one more thing for you)",
            atLeast(Vars.CHERRYGROVE, 2),
        ),
        HgssStoryStep(
            "johto:mr_pokemon", "Go to Mr. Pokémon's house: north of Cherrygrove City, along Route 30",
            Or(atLeast(Vars.MR_POKEMONS_HOUSE, 1), flag(Flags.GOT_POKEDEX)),
        ),
        HgssStoryStep(
            "johto:elm_call", "Leave Mr. Pokémon's house and head back toward New Bark Town",
            Or(atLeast(Vars.ROUTE_30, 3), flag(Flags.ELM_PANIC_CALL)),
        ),
        HgssStoryStep("johto:rival_cherrygrove", "Go back south through Cherrygrove City, toward New Bark Town", flag(Flags.RIVAL_CHERRYGROVE)),
        HgssStoryStep(
            "johto:return_egg", "Return to Prof. Elm's lab in New Bark Town with the Mystery Egg",
            Or(atLeast(Vars.ELMS_LAB, 4), flag(Flags.GAVE_EGG_TO_ELM)),
        ),
        HgssStoryStep(
            "johto:catching_tutorial", "Head west on Route 29 again (Lyra will show you how to catch Pokémon)",
            Or(flag(Flags.CATCHING_TUTORIAL), badge(ZEPHYR)),
        ),
        HgssStoryStep(
            "johto:sprout_tower", "Go north through Routes 30 and 31 to Violet City, then climb Sprout Tower and beat Elder Li at the top",
            Or(flag(Flags.BEAT_SPROUT_ELDER), badge(ZEPHYR)),
        ),
        HgssStoryStep("johto:badge_zephyr", "Challenge Falkner at the Violet City Gym", badge(ZEPHYR), checkpoint = true),
        // endregion
        // region Violet → Ecruteak
        HgssStoryStep(
            "johto:togepi_egg", "Go to the Violet City Poké Mart: Elm's aide waits there with an egg for you (keep a free party slot)",
            Or(flag(Flags.GOT_TOGEPI_EGG), atLeast(Vars.ROUTE_32_GATE, 1)),
        ),
        HgssStoryStep(
            "johto:route_32", "Head south from Violet City down Route 32 (Elm's aide checks your badge and egg at the start), then go through Union Cave to Azalea Town",
            Or(atLeast(Vars.ROUTE_32_GATE, 1), badge(HIVE)),
        ),
        HgssStoryStep(
            "johto:slowpoke_well", "In Azalea Town, talk to Kurt (north-west house), then follow him into the Slowpoke Well (north of town) and beat Team Rocket and their leader Proton",
            Or(flag(Flags.AZALEA_ROCKETS_BEATEN), badge(HIVE)),
        ),
        HgssStoryStep("johto:badge_hive", "Challenge Bugsy at the Azalea Town Gym (bug type: Fire and Flying moves work well)", badge(HIVE), checkpoint = true),
        HgssStoryStep(
            "johto:rival_azalea", "Leave Azalea Town to the west toward Ilex Forest: your rival waits at the exit",
            Or(atLeast(Vars.AZALEA_RIVAL, 2), flag(Flags.GOT_HM01)),
        ),
        HgssStoryStep(
            "johto:ilex_forest", "In Ilex Forest, find the charcoal maker's lost Farfetch'd (follow it and push it back toward the entrance) to get HM01 Cut; teach Cut and cut the tree that blocks the forest path",
            Or(flag(Flags.GOT_HM01), badge(PLAIN)),
        ),
        HgssStoryStep(
            "johto:badge_plain", "Cross Ilex Forest and Route 34 north to Goldenrod City and challenge Whitney at the Gym (Normal type: Fighting moves work well)",
            badge(PLAIN),
            checkpoint = true,
        ),
        HgssStoryStep(
            "johto:sudowoodo", "Get the SquirtBottle at the Goldenrod Flower Shop, then go north (Route 35, National Park, Route 36) and water the odd tree blocking Route 36 (Sudowoodo): beat or catch it",
            Or(flag(Flags.SUDOWOODO_GONE), badge(FOG)),
        ),
        HgssStoryStep(
            "johto:burned_tower", "Go on through Route 37 to Ecruteak City and enter the Burned Tower (north-west): beat your rival, then go down to the basement where the legendary beasts sleep",
            Or(atLeast(Vars.BURNED_TOWER_BEASTS, 1), atLeast(Vars.ECRUTEAK, 2), badge(FOG)),
        ),
        HgssStoryStep("johto:badge_fog", "Challenge Morty at the Ecruteak City Gym (Ghost type: Dark moves work well; Normal and Fighting moves can't hit)", badge(FOG), checkpoint = true),
        // endregion
    )

    private val johtoSecondHalf: List<HgssStoryStep> = listOf(
        HgssStoryStep(
            "johto:surf", "Visit the Ecruteak Dance Theater (south of the Pokémon Center): beat the Rocket grunt bothering the Kimono Girls, then talk to the old man to get HM03 Surf",
            Or(flag(Flags.GOT_HM03), badge(STORM)),
        ),
        HgssStoryStep(
            "johto:lighthouse", "Go west through Route 38 and 39 to Olivine City; the Gym leader Jasmine is away: climb the Olivine Lighthouse (by the sea, south) and talk to her at the top, next to the sick Ampharos",
            Or(atLeast(Vars.LIGHTHOUSE_JASMINE, 1), flag(Flags.TALKED_TO_JASMINE_LIGHTHOUSE), badge(MINERAL)),
        ),
        HgssStoryStep(
            "johto:badge_storm", "Surf west from Olivine City across Routes 40 and 41 to Cianwood City and challenge Chuck at the Gym (Fighting type: Flying and Psychic moves work well)",
            badge(STORM),
        ),
        HgssStoryStep(
            "johto:fly", "Leave the Cianwood Gym: Chuck's wife waits outside with HM02 Fly",
            Or(flag(Flags.GOT_HM02), badge(MINERAL)),
        ),
        HgssStoryStep(
            "johto:secretpotion", "Get the SecretPotion for the sick Ampharos at the Cianwood City pharmacy",
            Or(flag(Flags.GOT_SECRETPOTION), atLeast(Vars.LIGHTHOUSE_JASMINE, 2), badge(MINERAL)),
        ),
        HgssStoryStep(
            "johto:medicine", "Bring the SecretPotion to Jasmine at the top of the Olivine Lighthouse",
            Or(atLeast(Vars.LIGHTHOUSE_JASMINE, 2), badge(MINERAL)),
        ),
        HgssStoryStep("johto:badge_mineral", "Challenge Jasmine at the Olivine City Gym (Steel type: Fire, Fighting and Ground moves work well)", badge(MINERAL)),
        HgssStoryStep(
            "johto:red_gyarados", "Go east from Ecruteak (Route 42, through Mt. Mortar) to Mahogany Town, then north on Route 43 to the Lake of Rage: surf to the red Gyarados and defeat or catch it",
            Or(flag(Flags.GOT_RED_SCALE), atLeast(Vars.LANCE_LAKE_OF_RAGE, 1), flag(Flags.ROCKET_HIDEOUT_CLEARED), badge(GLACIER)),
        ),
        HgssStoryStep(
            "johto:lance_lake", "Talk to Lance on the Lake of Rage shore: he asks you to meet him in Mahogany Town",
            Or(atLeast(Vars.LANCE_LAKE_OF_RAGE, 1), flag(Flags.ROCKET_HIDEOUT_CLEARED), badge(GLACIER)),
        ),
        HgssStoryStep(
            "johto:rocket_hideout", "Enter the Mahogany Town souvenir shop with Lance and clear the Team Rocket hideout below it (beat the Executives, then the Electrode powering the radio wave)",
            Or(flag(Flags.ROCKET_HIDEOUT_CLEARED), badge(GLACIER)),
        ),
        HgssStoryStep("johto:badge_glacier", "Challenge Pryce at the Mahogany Town Gym (Ice type: Fire, Fighting, Rock and Steel moves work well)", badge(GLACIER)),
        HgssStoryStep(
            "johto:radio_tower", "Team Rocket has taken over the Goldenrod Radio Tower: get the Basement Key (Radio Tower 5F) and the Card Key (Director, Goldenrod Underground Warehouse), then free every floor and beat Archer at the top",
            Or(atLeast(Vars.ROCKET_TAKEOVER, 5), flag(Flags.BEAT_RADIO_TOWER_ROCKETS)),
            checkpoint = true,
        ),
        HgssStoryStep(
            "johto:ice_path", "Leave Mahogany Town east by Route 44 into the Ice Path: pick up HM07 Waterfall inside and cross it to Blackthorn City",
            Or(flag(Flags.GOT_HM07), badge(RISING)),
        ),
        HgssStoryStep(
            "johto:clair", "Challenge Clair at the Blackthorn City Gym (Dragon type: Ice moves work well)",
            Or(flag(Flags.BEAT_CLAIR), flag(Flags.PASSED_DRAGONS_DEN), badge(RISING)),
        ),
        HgssStoryStep(
            "johto:badge_rising", "Clair wants you to pass the Dragon's Den test: enter the Den behind the Gym, surf to the shrine and answer the elder's questions (be kind to your Pokémon); Clair then gives you the Rising Badge",
            badge(RISING),
            checkpoint = true,
        ),
        HgssStoryStep(
            "johto:visit_elm", "Go back to New Bark Town and visit Prof. Elm's lab (Mom stops you at the east exit until then)",
            atLeast(Vars.NEW_BARK_EAST_EXIT, 2),
        ),
        HgssStoryStep(
            "johto:kimono_call", "Go to Ecruteak City: a Kimono Girl meets you in town and asks you to come to the Dance Theater",
            Or(atLeast(Vars.DANCE_THEATER, 4), flag(Flags.BEAT_KIMONO_GIRLS)),
        ),
        HgssStoryStep(
            "johto:kimono_girls", "In the Ecruteak Dance Theater, battle the five Kimono Girls one after another (one Eeveelution each: Espeon, Umbreon, Vaporeon, Jolteon, Flareon; heal before starting, there is no break) to get the Clear Bell",
            Or(flag(Flags.BEAT_KIMONO_GIRLS), atLeast(Vars.DANCE_THEATER, 6)),
        ),
        HgssStoryStep(
            "johto:bell_tower", "Go through the gate north-east of Ecruteak City (the sage lets you through) and climb the Bell Tower (the sage on 1F checks the Rainbow Wing) to the roof, where the Kimono Girls dance",
            Or(flag(Flags.BELL_TOWER_DANCE), flag(Flags.HO_OH_DONE)),
        ),
        HgssStoryStep(
            "johto:ho_oh", "Battle Ho-Oh on the Bell Tower roof (level 45): catch it or defeat it",
            Or(flag(Flags.HO_OH_DONE), atLeast(Vars.NEW_BARK_EAST_EXIT, 3)),
            checkpoint = true,
        ),
        HgssStoryStep(
            "johto:league_gate", "Leave New Bark Town east: surf Route 27, climb Tohjo Falls (Waterfall) and follow Route 26 north to the Pokémon League Reception Gate",
            Or(flag(Flags.REACHED_LEAGUE_GATE), flag(Flags.REACHED_INDIGO_PLATEAU), flag(Flags.GAME_CLEAR)),
        ),
        HgssStoryStep(
            "johto:victory_road", "Cross Victory Road (north of the Reception Gate; Strength boulders) to the Indigo Plateau; your rival waits near the exit",
            Or(flag(Flags.REACHED_INDIGO_PLATEAU), flag(Flags.GAME_CLEAR)),
        ),
        HgssStoryStep(
            "johto:elite_four", "Beat the Elite Four (Will, Koga, Bruno, Karen) and Champion Lance in a row at the Indigo Plateau (stock up on healing items first)",
            flag(Flags.GAME_CLEAR),
            checkpoint = true,
        ),
    )

    private val kanto: List<HgssStoryStep> = listOf(
        HgssStoryStep(
            "kanto:ss_ticket", "Go home to New Bark Town and visit Prof. Elm's lab: he gives you the S.S. Ticket",
            Or(flag(Flags.GOT_SS_TICKET), flag(Flags.ARRIVED_IN_VERMILION)),
        ),
        HgssStoryStep(
            "kanto:ss_aqua", "Board the S.S. Aqua at the Olivine City port; on board, find the gentleman's lost granddaughter (ask the sailors and the captain) so the ship can sail",
            Or(atLeast(Vars.SS_AQUA, 6), flag(Flags.ARRIVED_IN_VERMILION)),
        ),
        HgssStoryStep("kanto:vermilion", "Get off the ship at Vermilion City, Kanto", flag(Flags.ARRIVED_IN_VERMILION), checkpoint = true),
        HgssStoryStep(
            "kanto:badge_thunder", "Challenge Lt. Surge at the Vermilion City Gym (find the switches under the trash cans; Electric type: Ground moves work well)",
            badge(THUNDER),
        ),
        HgssStoryStep("kanto:badge_marsh", "Go north to Saffron City and challenge Sabrina at the Gym (Psychic type: Dark, Ghost and Bug moves work well)", badge(MARSH)),
        HgssStoryStep("kanto:badge_rainbow", "Go west to Celadon City and challenge Erika at the Gym (Grass type: Fire, Ice and Flying moves work well)", badge(RAINBOW)),
        HgssStoryStep(
            "kanto:power_plant", "Go to the Power Plant (Route 10, north of Lavender Town via Route 9 from Cerulean City) and talk to the manager: a Machine Part was stolen",
            Or(flag(Flags.POWER_PLANT_STORY), flag(Flags.RESTORED_POWER)),
        ),
        HgssStoryStep(
            "kanto:route_24_rocket", "Go to the Cerulean City Gym (a Rocket grunt runs away), then beat him on Route 24 north of Cerulean City (Nugget Bridge)",
            Or(atLeast(Vars.ROUTE_24_ROCKET, 3), flag(Flags.RESTORED_POWER)),
        ),
        HgssStoryStep(
            "kanto:machine_part", "Get the Machine Part hidden in the Cerulean City Gym: it lies on the pool's left edge by the buoys (tiles 3,10 and 4,10, nothing drawn there): stand north of it (4,9), face south and press A (examine:N in examinables)",
            Or(atLeast(Vars.ROUTE_24_ROCKET, 4), flag(Flags.RESTORED_POWER)),
        ),
        HgssStoryStep("kanto:restore_power", "Bring the Machine Part back to the Power Plant manager", flag(Flags.RESTORED_POWER)),
        HgssStoryStep(
            "kanto:misty", "Find Misty on Route 25 (Cerulean Cape, north-east of Cerulean City): she then returns to her Gym",
            Or(atLeast(Vars.MISTY, 2), badge(CASCADE)),
        ),
        HgssStoryStep("kanto:badge_cascade", "Challenge Misty at the Cerulean City Gym (Water type: Electric and Grass moves work well)", badge(CASCADE)),
        HgssStoryStep(
            "kanto:expansion_card", "Go to the Lavender Town Radio Station and talk to the director (top floor) to get the Expansion Card for the Pokégear radio",
            Or(flag(Flags.GOT_EXPN_CARD), flag(Flags.SNORLAX_BEATEN), flag(Flags.UNLOCKED_WEST_KANTO)),
        ),
        HgssStoryStep(
            "kanto:snorlax", "Wake the sleeping Snorlax in front of Diglett's Cave (Route 11, east of Vermilion City): play the Poké Flute channel on the Pokégear radio next to it, then defeat or catch it",
            Or(flag(Flags.SNORLAX_BEATEN), flag(Flags.UNLOCKED_WEST_KANTO)),
        ),
        HgssStoryStep(
            "kanto:viridian", "Go through Diglett's Cave and north along Route 2 to reach western Kanto (Viridian City)",
            flag(Flags.UNLOCKED_WEST_KANTO),
        ),
        HgssStoryStep("kanto:badge_boulder", "Go north to Pewter City and challenge Brock at the Gym (Rock type: Water, Grass and Fighting moves work well)", badge(BOULDER)),
        HgssStoryStep("kanto:badge_soul", "Go to Fuchsia City and challenge Janine at the Gym (invisible walls; Poison type: Ground and Psychic moves work well)", badge(SOUL)),
        HgssStoryStep(
            "kanto:badge_volcano", "Surf from Pallet Town down Route 21 to Cinnabar Island, then on to the Seafoam Islands: Blaine's Gym is inside (Fire type: Water and Ground moves work well)",
            badge(VOLCANO),
        ),
        HgssStoryStep(
            "kanto:blue_cinnabar", "With seven Kanto badges, talk to Blue on Cinnabar Island: he goes back to open the Viridian City Gym",
            Or(flag(Flags.BLUE_OPENED_VIRIDIAN_GYM), badge(EARTH)),
        ),
        HgssStoryStep("kanto:badge_earth", "Challenge Blue at the Viridian City Gym", badge(EARTH), checkpoint = true),
        HgssStoryStep(
            "kanto:mt_silver_permission", "Visit Prof. Oak in his Pallet Town lab: with all 16 badges he lets you enter Mt. Silver",
            flag(Flags.UNLOCKED_MT_SILVER),
            checkpoint = true,
        ),
        HgssStoryStep(
            "kanto:red", "Go through the Pokémon League gate (Route 22, west of Viridian City) to Route 28 and Mt. Silver, climb to the summit and beat Red",
            atLeast(Vars.BEAT_RED, 1),
        ),
    )

    /** The next step: the first one that isn't done, or null when the whole story is done. */
    fun goal(facts: StoryFacts): HgssStoryStep? = steps.firstOrNull { !it.done.holds(facts) }

    /** The step with id [id], or null. */
    fun step(id: String): HgssStoryStep? = steps.firstOrNull { it.id == id }

    /** Every flag read by the steps and by the curated blockers (what [HgssReader] puts in [StoryInfo.flags]). */
    val flagIds: Set<Int> by lazy { steps.flatMapTo(mutableSetOf()) { it.done.flagIds() } + HgssBlockers.flagIds }

    /** Every var read by the steps and by the curated blockers. */
    val varIds: Set<Int> by lazy { steps.flatMapTo(mutableSetOf()) { it.done.varIds() } + HgssBlockers.varIds }
}
