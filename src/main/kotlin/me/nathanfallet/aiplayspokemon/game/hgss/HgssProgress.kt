package me.nathanfallet.aiplayspokemon.game.hgss

/**
 * Early-game story progress from the save's event flags and scene vars (HeartGold, new game until the first gym).
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
     * The next objective of the main story, until the first badge; null beyond (or when unknown).
     * [mapId] lets the hint say where to go from where the player is.
     */
    fun nextGoal(story: StoryInfo?, player: PlayerInfo?, mapId: Int?): String? {
        story ?: return null
        val c = Ctx(story, player?.badges ?: emptyList())
        if (c.badges.isNotEmpty()) return null
        val inHouse = mapId == MAP_PLAYER_HOUSE_1F || mapId == MAP_PLAYER_HOUSE_2F
        return when {
            c.v(VAR_PLAYERS_HOUSE_1F) < 1 && !c.flag(FLAG_GOT_BAG) ->
                if (mapId == MAP_PLAYER_HOUSE_2F) "Go downstairs (stairs in the top-left corner) and talk to Mom"
                else "Talk to Mom on the first floor of your house"
            c.v(VAR_PLAYERS_HOUSE_1F) < 2 && !c.flag(FLAG_MET_ELM) && c.v(VAR_ELMS_LAB) < 1 ->
                "Leave the house (exit mat at the bottom of the first floor)"
            !c.flag(FLAG_MET_ELM) && c.v(VAR_ELMS_LAB) < 1 ->
                "Go to Prof. Elm's lab (the big building in the west part of New Bark Town) and talk to him"
            !c.flag(FLAG_GOT_STARTER) -> "Choose a starter Pokémon: examine the Poké Balls on the machine in Elm's lab"
            c.v(VAR_ELMS_LAB) < 2 -> "Walk to the lab's exit (Elm's aide has something for you)"
            c.v(VAR_NEW_BARK_TOWN) < 2 -> "Leave Elm's lab"
            !c.flag(FLAG_GOT_POKEGEAR) ->
                if (inHouse) "Talk to Mom (she has your Pokégear)" else "Go home (the house east of Elm's lab) and talk to Mom to get your Pokégear"
            c.v(VAR_NEW_BARK_WEST_EXIT) < 1 -> "Leave New Bark Town to the west, toward Route 29"
            c.v(VAR_CHERRYGROVE) < 1 && !c.shoes -> "Follow Route 29 west to Cherrygrove City and talk to the old man there (Guide Gent)"
            c.v(VAR_CHERRYGROVE) < 2 -> "Head north out of Cherrygrove City toward Route 30 (the Guide Gent has one more thing for you)"
            c.v(VAR_MR_POKEMONS_HOUSE) < 1 && !c.flag(FLAG_GOT_POKEDEX) ->
                "Go to Mr. Pokémon's house: north of Cherrygrove City, along Route 30"
            c.v(VAR_ROUTE_30) < 3 && !c.flag(FLAG_ELM_PANIC_CALL) -> "Leave Mr. Pokémon's house and head back toward New Bark Town"
            !c.flag(FLAG_RIVAL_CHERRYGROVE) -> "Go back south through Cherrygrove City, toward New Bark Town"
            c.v(VAR_ELMS_LAB) < 4 && !c.flag(FLAG_GAVE_EGG_TO_ELM) -> "Return to Prof. Elm's lab in New Bark Town with the Mystery Egg"
            !c.flag(FLAG_CATCHING_TUTORIAL) -> "Head west on Route 29 again (Lyra will show you how to catch Pokémon)"
            !c.flag(FLAG_BEAT_SPROUT_ELDER) ->
                "Go north through Routes 30 and 31 to Violet City, then climb Sprout Tower and beat Elder Li at the top"
            else -> "Challenge Falkner at the Violet City Gym"
        }
    }

    private const val MAP_PLAYER_HOUSE_1F = 63
    private const val MAP_PLAYER_HOUSE_2F = 64
}
