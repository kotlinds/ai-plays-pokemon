package dev.kotlinds.pokemonclient.hgss

/**
 * Early-game story progress from the save's event flags and scene vars (HeartGold, new game until the first gym):
 * the milestones, and the next goal as text (an adapter over [HgssStoryTable], which holds the whole story).
 *
 * Ids come from include/constants/flags.h / vars.h of pokeheartgold and the scripts that set them
 * (files/fielddata/script/scr_seq: 0845_T20R0201 player house 1F, 0843_T20R0101 Elm's lab, 0842_T20 New Bark,
 * 0850_T21 Cherrygrove, 0227_R30 Route 30, 0229_R30R0201 Mr. Pokémon's house, 0225_R29, 0018_D15R0103 Sprout Tower,
 * 0859_T22GYM0101 Violet Gym, 0858_T22FS0101 Violet Mart). Scene vars only increase in this part of the game, so
 * they are compared with >=.
 */
object HgssProgress {

    // Flags (SaveVarsFlags.flags, bit id%8 of byte id/8)
    const val FLAG_GOT_BAG = 0x11B              // Mom's opening talk done (Bag, Trainer Card, Save, Options)
    const val FLAG_GOT_STARTER = 0x6A
    const val FLAG_GOT_POKEDEX = 0x6B           // Oak at Mr. Pokémon's (same scene as the Mystery Egg)
    const val FLAG_GOT_TOGEPI_EGG = 0x70        // from Elm's aide in the Violet Poké Mart
    const val FLAG_GOT_TM51 = 0x73              // talked to Falkner after the badge
    const val FLAG_BEAT_SPROUT_ELDER = 0x76     // FLAG_UNK_076: beat Elder Li (opens the Violet Gym)
    const val FLAG_GAVE_EGG_TO_ELM = 0x79       // FLAG_GAVE_RIVAL_NAME_TO_OFFICER: police scene done, egg given
    const val FLAG_RIVAL_CHERRYGROVE = 0x99     // FLAG_MET_PASSERBY_BOY: rival battle in Cherrygrove done
    const val FLAG_CATCHING_TUTORIAL = 0x9A     // Lyra's catching tutorial on Route 29 done
    const val FLAG_GOT_POKEGEAR = 0x9C
    const val FLAG_ELM_PANIC_CALL = 0xEE        // Elm's "Pokémon stolen" call on Route 30
    const val FLAG_MET_ELM = 0x160              // FLAG_ELMS_LAB_PREVENT_PLAYER_ESCAPE: Elm's intro done, starter not chosen

    // Vars (SaveVarsFlags.vars[id - 0x4000])
    const val VAR_PLAYER_STARTER = 0x4030
    const val VAR_NEW_BARK_TOWN = 0x4072        // 1 starter chosen, 2 Lyra's scene after the lab, 3 egg returned
    const val VAR_CHERRYGROVE = 0x4073          // 1 Guide Gent tour (running shoes), 2 Map Card, 3 rival armed, 4 rival battled
    const val VAR_NEW_BARK_WEST_EXIT = 0x407E   // 1 Elm registered his number at the west exit
    const val VAR_ROUTE_30 = 0x408C             // 1 Apricorn Box, 2 left Mr. Pokémon's, 3 Elm's panic call
    const val VAR_PLAYERS_HOUSE_1F = 0x4106     // 1 Mom's talk done, 2 Lyra's scene outside the house done
    const val VAR_MR_POKEMONS_HOUSE = 0x4107    // 1 got egg + Pokédex, 2 egg returned
    const val VAR_ELMS_LAB = 0x4108             // 1 starter, 2 Potions, 3 egg+dex, 4 egg given, 5 aide, 6 Falkner, 7 Togepi egg

    val FLAGS = listOf(
        FLAG_GOT_BAG, FLAG_GOT_STARTER, FLAG_GOT_POKEDEX, FLAG_GOT_TOGEPI_EGG, FLAG_GOT_TM51, FLAG_BEAT_SPROUT_ELDER,
        FLAG_GAVE_EGG_TO_ELM, FLAG_RIVAL_CHERRYGROVE, FLAG_CATCHING_TUTORIAL, FLAG_GOT_POKEGEAR, FLAG_ELM_PANIC_CALL, FLAG_MET_ELM,
    )
    val VARS = listOf(
        VAR_PLAYER_STARTER, VAR_NEW_BARK_TOWN, VAR_CHERRYGROVE, VAR_NEW_BARK_WEST_EXIT, VAR_ROUTE_30,
        VAR_PLAYERS_HOUSE_1F, VAR_MR_POKEMONS_HOUSE, VAR_ELMS_LAB,
    )

    private class Ctx(story: StoryInfo, val badges: List<String>) {
        val flags = story.flags
        val vars = story.vars
        val shoes = story.hasRunningShoes
        val dex = story.hasPokedex
        fun flag(id: Int) = id in flags
        fun v(id: Int) = vars[id] ?: 0
    }

    /** Milestones reached, oldest first. */
    fun milestones(story: StoryInfo?, player: PlayerInfo?, partySize: Int): List<String> {
        story ?: return emptyList()
        val c = Ctx(story, player?.badges ?: emptyList())
        return buildList {
            if (c.flag(FLAG_GOT_BAG) || c.v(VAR_PLAYERS_HOUSE_1F) >= 1) add("Talked to Mom (got the Bag)")
            if (c.flag(FLAG_MET_ELM) || c.v(VAR_ELMS_LAB) >= 1) add("Met Prof. Elm")
            if (c.flag(FLAG_GOT_STARTER)) {
                val starter = c.v(VAR_PLAYER_STARTER).takeIf { it > 0 }?.let { " (${HgssData.speciesName(it)})" } ?: ""
                add("Has starter Pokémon$starter")
            }
            if (c.flag(FLAG_GOT_POKEGEAR)) add("Has Pokégear")
            if (c.shoes) add("Has Running Shoes")
            if (c.v(VAR_CHERRYGROVE) >= 2) add("Has the Map Card")
            if (c.flag(FLAG_GOT_POKEDEX) || c.dex) add("Has Pokédex")
            if (c.flag(FLAG_GOT_POKEDEX) || c.v(VAR_MR_POKEMONS_HOUSE) >= 1) add("Got the Mystery Egg at Mr. Pokémon's")
            if (c.flag(FLAG_RIVAL_CHERRYGROVE)) add("Battled the rival in Cherrygrove City")
            if (c.flag(FLAG_GAVE_EGG_TO_ELM) || c.v(VAR_ELMS_LAB) >= 4) add("Delivered the Mystery Egg to Prof. Elm")
            if (c.flag(FLAG_CATCHING_TUTORIAL)) add("Saw Lyra's catching tutorial")
            if (c.flag(FLAG_BEAT_SPROUT_ELDER)) add("Beat Elder Li in Sprout Tower")
            c.badges.forEach { add("$it Badge") }
            if (c.flag(FLAG_GOT_TOGEPI_EGG)) add("Got the Togepi Egg")
            if (partySize > 0) add("Party: $partySize Pokémon")
        }
    }

    /**
     * The next objective of the main story ([HgssStoryTable.goal]), said from map [mapId]; null when the story is
     * over (or unknown). [player]'s badges count when [story] has none (a [StoryInfo] built without them).
     */
    fun nextGoal(story: StoryInfo?, player: PlayerInfo?, mapId: Int?): String? {
        story ?: return null
        val facts = if (story.badges.isEmpty() && player != null) {
            story.copy(badges = player.badges.mapNotNull { name -> BADGE_NAMES.indexOf(name).takeIf { it >= 0 } }.toSet())
        } else story
        return HgssStoryTable.goal(facts)?.describe(mapId)
    }

    /** Badge names as [PlayerInfo.badges] lists them, by badge index. */
    private val BADGE_NAMES = listOf(
        "Zephyr", "Hive", "Plain", "Fog", "Storm", "Mineral", "Glacier", "Rising",
        "Boulder", "Cascade", "Thunder", "Rainbow", "Soul", "Marsh", "Volcano", "Earth",
    )
}
