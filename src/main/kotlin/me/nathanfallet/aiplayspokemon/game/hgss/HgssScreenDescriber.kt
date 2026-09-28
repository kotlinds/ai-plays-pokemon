package me.nathanfallet.aiplayspokemon.game.hgss

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Describes an [HgssState] the way a player perceives the screen, for the AI.
 *
 * The reader's snapshot is exhaustive (ids, pointers-derived flags, raw coordinates...); an AI plays
 * better with what a human actually looks at, most important first:
 * 1. the situation in one sentence (free to walk? a message box? a menu? a battle?),
 * 2. in the overworld: the map around the player, what is right in front of them, and the exits,
 *    people and objects visible nearby, positioned relative to the player,
 * 3. the dialogue text, menu or battle when there is one,
 * 4. the team, the bag and the trainer's progress.
 *
 * This is perception only: nothing here tells the AI where to go or what to press.
 */
object HgssScreenDescriber {

    fun describe(state: HgssState): JsonObject = buildJsonObject {
        put("situation", situation(state))
        state.location?.let { location ->
            put("location", location.locationName?.let { "${location.mapName} ($it)" } ?: location.mapName)
        }

        val overworld = state.mode in setOf(GameMode.OVERWORLD, GameMode.FIELD_BUSY, GameMode.SCRIPT, GameMode.DIALOGUE)
        if (overworld) {
            state.location?.let { location ->
                putJsonObject("you") {
                    put("facing", location.facing)
                    put("tile_in_front", tileInFront(location, state.surroundings))
                    put("position", "x ${location.x}, y ${location.z}")
                }
            }
            state.surroundings?.let { surroundings -> overworld(surroundings) }
        }

        state.dialogue?.takeIf { it.messageBoxOpen || it.text != null }?.let { dialogue ->
            putJsonObject("dialogue") {
                dialogue.text?.let { put("text", it) }
                put("waiting_for", dialogue.waitingFor.replace('_', ' '))
            }
        }
        state.startMenu?.let { menu ->
            putJsonObject("menu") {
                put("entries", JsonArray(menu.items.map { JsonPrimitive(it.lowercase().replace('_', ' ')) }))
                menu.cursor?.let { put("highlighted", menu.items.getOrNull(it)?.lowercase()?.replace('_', ' ')) }
            }
        }
        state.battle?.let { put("battle", battle(it)) }

        if (state.party.isNotEmpty()) put("team", JsonArray(state.party.map(::teamMember)))
        state.bag?.filter { it.items.isNotEmpty() }?.takeIf { it.isNotEmpty() }?.let { pockets ->
            putJsonObject("bag") {
                pockets.forEach { pocket ->
                    put(pocket.pocket.replace('_', ' '), JsonArray(pocket.items.map { JsonPrimitive("${it.name} x${it.quantity}") }))
                }
            }
        }
        state.player?.let { player ->
            putJsonObject("trainer") {
                put("name", player.name)
                put("money", player.money)
                put("badges", player.badgeCount)
            }
        }
    }

    private fun situation(state: HgssState): String = when (state.mode) {
        GameMode.LOADING -> "The game is loading."
        GameMode.INTRO_MOVIE -> "The intro movie is playing."
        GameMode.TITLE_SCREEN -> "Title screen."
        GameMode.MAIN_MENU -> "Main menu (continue / new game)."
        GameMode.NEW_GAME_INTRO -> "New game introduction with the professor."
        GameMode.OVERWORLD -> "You are free to walk around. No message box is open."
        GameMode.FIELD_BUSY -> "Something is happening on the map (transition or animation)."
        GameMode.SCRIPT -> "A scene is playing on the map."
        GameMode.DIALOGUE -> "A message box is open."
        GameMode.START_MENU -> "The main menu is open."
        GameMode.APP -> "A full-screen screen is open: ${state.modeDetail?.takeUnless { it.startsWith("app(") }?.replace('_', ' ') ?: "unknown menu"}."
        GameMode.BATTLE -> "You are in a battle."
        GameMode.UNKNOWN -> "Unknown screen."
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.overworld(surroundings: Surroundings) {
        surroundings.grid?.let { grid ->
            putJsonObject("map") {
                put("how_to_read", "The tiles around you, north at the top, one character per tile. You are `@`.")
                put("rows", JsonArray(grid.rows.map(::JsonPrimitive)))
                val used = grid.rows.joinToString("").map { it.toString() }.toSet()
                put("legend", JsonObject(grid.legend.filterKeys { it in used }.mapValues { JsonPrimitive(it.value) }))
            }
        }
        if (surroundings.warps.isNotEmpty()) putJsonArray("exits") {
            surroundings.warps.forEach { warp ->
                add(buildJsonObject {
                    put("leads_to", warp.destLocationName?.let { "${warp.destMapName} ($it)" } ?: warp.destMapName)
                    put("where", relative(warp.dx, warp.dz))
                })
            }
        }
        val people = surroundings.objects.filter { !it.hidden && it.kind != "follower" }
        if (people.isNotEmpty()) putJsonArray("people_and_objects") {
            people.forEach { obj ->
                add(buildJsonObject {
                    put("what", if (obj.kind == "item_ball") "item ball" else "person (${obj.sprite.lowercase().replace('_', ' ')})")
                    put("where", relative(obj.dx, obj.dz))
                })
            }
        }
        val signs = surroundings.bgEvents.filter { it.type != "hidden_item" }
        if (signs.isNotEmpty()) putJsonArray("things_to_check") {
            signs.forEach { sign ->
                add(buildJsonObject {
                    put("what", if (sign.type == "sign") "sign" else "something you can examine")
                    put("where", relative(sign.dx, sign.dz))
                })
            }
        }
    }

    /** "3 tiles west and 2 tiles north", from offsets relative to the player (x grows east, z grows south). */
    fun relative(dx: Int, dz: Int): String {
        if (dx == 0 && dz == 0) return "where you stand"
        val parts = buildList {
            if (dx != 0) add("${kotlin.math.abs(dx)} tile${if (kotlin.math.abs(dx) > 1) "s" else ""} ${if (dx > 0) "east" else "west"}")
            if (dz != 0) add("${kotlin.math.abs(dz)} tile${if (kotlin.math.abs(dz) > 1) "s" else ""} ${if (dz > 0) "south" else "north"}")
        }
        return parts.joinToString(" and ")
    }

    private fun tileInFront(location: LocationInfo, surroundings: Surroundings?): String {
        val (dx, dz) = when (location.facing) {
            "north" -> 0 to -1
            "south" -> 0 to 1
            "west" -> -1 to 0
            else -> 1 to 0
        }
        val x = location.x + dx
        val z = location.z + dz
        val grid = surroundings?.grid
        val symbol = grid?.rows?.getOrNull(z - grid.originZ)?.getOrNull(x - grid.originX)?.toString()
        return symbol?.let { grid.legend[it] } ?: if (location.facingTileBlocked == true) "blocked" else "walkable"
    }

    private fun teamMember(mon: PartyMon) = buildJsonObject {
        put("pokemon", mon.nickname?.takeIf { it != mon.speciesName }?.let { "$it (${mon.speciesName})" } ?: mon.speciesName)
        put("level", mon.level)
        put("hp", "${mon.hp}/${mon.maxHp}")
        if (mon.status != "OK") put("status", mon.status.lowercase())
        put("types", JsonArray(mon.types.map(::JsonPrimitive)))
        put("moves", JsonArray(mon.moves.map { JsonPrimitive("${it.name} (${it.pp}/${it.maxPp} PP)") }))
    }

    private fun battle(battle: BattleInfo) = buildJsonObject {
        put("kind", if (battle.isWild) "wild Pokémon" else "trainer battle")
        battle.trainers.firstOrNull()?.let { put("opponent_trainer", "${it.trainerClass} ${it.name}") }
        put("your_pokemon", JsonArray(battle.player.map(::battler)))
        put("opponent_pokemon", JsonArray(battle.opponents.map(::battler)))
        battle.menu?.let { put("menu_shown", it.lowercase().replace('_', ' ')) }
        battle.message?.let { put("last_message", it) }
    }

    private fun battler(mon: Battler) = buildJsonObject {
        put("pokemon", mon.speciesName)
        put("level", mon.level)
        put("hp", "${mon.hp}/${mon.maxHp}")
        if (mon.status != "OK") put("status", mon.status.lowercase())
        put("types", JsonArray(mon.types.map(::JsonPrimitive)))
        if (mon.side == "player") put("moves", buildJsonArray {
            mon.moves.forEach { add(JsonPrimitive("${it.name} (${it.type ?: "?"}, ${it.pp}/${it.maxPp} PP)")) }
        })
    }
}
