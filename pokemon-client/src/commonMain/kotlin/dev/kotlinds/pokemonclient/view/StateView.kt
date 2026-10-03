package dev.kotlinds.pokemonclient.view

import dev.kotlinds.pokemonclient.runtime.kind
import dev.kotlinds.pokemonclient.state.BattleState
import dev.kotlinds.pokemonclient.state.BattlerState
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MajorStatus
import dev.kotlinds.pokemonclient.state.PartyMon
import dev.kotlinds.pokemonclient.state.PuzzleState
import dev.kotlinds.pokemonclient.state.PuzzleTile
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.VolatileStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The game state as agents read it: compact JSON built from the typed [GameState], with stable ids everywhere
 * (`mon:…`, `move:…`, `item:…`, entry ids) so an agent can name things in its actions.
 */
object StateView {

    /** The screen: what the game waits for, the menu entries with the highlighted one, or the text shown. */
    fun screen(screen: Screen): JsonObject = buildJsonObject {
        put("kind", screen.kind)
        put("awaiting", screen.awaiting.name.lowercase())
        when (screen) {
            is Screen.Selectable -> {
                val highlighted = (screen.cursor as? Cursor.At)?.let { screen.entries.getOrNull(it.index) }
                put("highlighted", highlighted?.id ?: "none (the first D-pad press only shows the cursor)")
                putJsonArray("entries") {
                    screen.entries.forEach { e ->
                        add(buildJsonObject {
                            put("id", e.id)
                            put("label", e.label)
                            if (!e.selectable) put("selectable", false)
                            if (e.dangerous) put("dangerous", true)
                            e.touch?.let { put("touch", "${it.x},${it.y}") }
                        })
                    }
                }
                when (screen) {
                    is Screen.YesNo -> screen.question?.let { put("question", it) }
                    is Screen.MoveSelect -> screen.newMove?.let { put("new_move", "move:${it.id.value} ${it.name}") }
                    is Screen.Keyboard -> {
                        put("typed", screen.buffer)
                        put("max_length", screen.maxLength)
                        put("page", screen.page)
                    }
                    is Screen.Bag -> {
                        put("pocket", screen.pocket)
                        put("page", "${screen.page + 1}/${screen.pages}")
                    }
                    is Screen.Shop -> put("money", screen.money)
                    is Screen.BattleCommand -> screen.actor?.let { put("choosing", it.wire) }
                    else -> Unit
                }
            }
            is Screen.Dialogue -> {
                put("source", screen.source.name.lowercase())
                screen.speaker?.let { put("speaker", it) }
                put("text", screen.text)
            }
            is Screen.PressToContinue -> {
                put("reason", screen.reason.name.lowercase())
                screen.text?.let { put("text", it) }
            }
            is Screen.Quantity -> {
                put("value", screen.value)
                put("min", screen.min)
                put("max", screen.max)
            }
            is Screen.Evolution -> {
                put("from", screen.from.name)
                screen.to?.let { put("to", it.name) }
            }
            is Screen.Overworld -> screen.banner?.let { put("sign", it) }
            is Screen.Unknown -> screen.hint?.let { put("hint", it) }
            is Screen.Intro -> put("detail", screen.detail)
            else -> Unit
        }
    }

    /** One party Pokémon on one line-ish object. */
    fun mon(mon: PartyMon): JsonObject = buildJsonObject {
        put("id", mon.id.toString())
        put("name", mon.displayName)
        if (mon.nickname != null) put("species", mon.species.name)
        if (mon.isEgg) return@buildJsonObject
        put("level", mon.level)
        put("hp", "${mon.hp}/${mon.maxHp}")
        mon.status?.let { put("status", status(it)) }
        put("types", mon.types.joinToString("/"))
        mon.heldItem?.let { put("held_item", it.name) }
        put("moves", JsonArray(mon.moves.map { JsonPrimitive("${it.move.name} ${it.pp}/${it.maxPp}") }))
        put("exp", mon.exp)
        mon.expToNextLevel?.let { put("exp_to_next_level", it) }
    }

    fun battle(battle: BattleState): JsonObject = buildJsonObject {
        put("kind", battle.kind.name.lowercase())
        if (battle.isDouble) put("double", true)
        battle.trainers.takeIf { it.isNotEmpty() }?.let { put("trainers", JsonArray(it.map(::JsonPrimitive))) }
        putJsonArray("battlers") { battle.battlers.forEach { add(battler(it)) } }
        battle.message?.let { put("message", it) }
    }

    private fun battler(b: BattlerState): JsonObject = buildJsonObject {
        put("position", b.ref.wire)
        b.mon?.let { put("id", it.toString()) }
        put("name", b.nickname ?: b.species.name)
        put("level", b.level)
        put("hp", "${b.hp}/${b.maxHp}")
        b.status?.let { put("status", status(it)) }
        if (b.volatile.isNotEmpty()) put("volatile", JsonArray(b.volatile.map { JsonPrimitive(volatile(it)) }))
        if (b.statStages.isNotEmpty()) putJsonObject("stat_stages") { b.statStages.forEach { (stat, v) -> put(stat.name.lowercase(), v) } }
        put("types", b.types.joinToString("/"))
        if (b.ref.isPlayerSide) put("moves", JsonArray(b.moves.map { JsonPrimitive("move:${it.move.id.value} ${it.move.name} (${it.type ?: "?"}) ${it.pp}/${it.maxPp}") }))
    }

    /** The full compact state: screen, party, battle, position, money and badges, warnings. */
    fun state(state: GameState): JsonObject = buildJsonObject {
        put("screen", screen(state.screen))
        state.battle?.let { put("battle", battle(it)) }
        state.field?.let { f ->
            putJsonObject("position") {
                put("map", f.mapName)
                put("x", f.x)
                put("y", f.y)
                f.facing?.let { put("facing", it.name.lowercase()) }
                if (f.movement.name != "WALK") put("movement", f.movement.name.lowercase())
            }
            f.puzzle?.let { put("puzzle", puzzle(it)) }
        }
        if (state.party.isNotEmpty()) put("team", buildJsonArray { state.party.forEach { add(mon(it)) } })
        state.player?.let { p ->
            put("money", p.money)
            put("badges", JsonArray(p.badges.map(::JsonPrimitive)))
        }
        if (state.warnings.isNotEmpty()) put("warnings", JsonArray(state.warnings.map { JsonPrimitive(it.detail) }))
    }

    /** The map puzzle: its rule, switches (what they toggle), shutters (open or not, tiles) and teleports. */
    fun puzzle(puzzle: PuzzleState): JsonObject = buildJsonObject {
        fun tiles(tiles: List<PuzzleTile>) = JsonArray(tiles.map { JsonPrimitive("${it.x},${it.y}") })
        put("kind", puzzle.kind.name.lowercase())
        put("rule", puzzle.rule)
        if (puzzle.switches.isNotEmpty()) putJsonArray("switches") {
            puzzle.switches.forEach { s ->
                add(buildJsonObject {
                    put("id", s.id)
                    put("interact", JsonArray(s.targets.map(::JsonPrimitive)))
                    put("at", tiles(s.tiles))
                    s.flipped?.let { put("flipped", it) }
                    if (s.toggles.isNotEmpty()) put("toggles", JsonArray(s.toggles.map(::JsonPrimitive)))
                    if (s.oneShot) put("one_shot", if (s.used) "used" else "unused")
                })
            }
        }
        if (puzzle.barriers.isNotEmpty()) putJsonArray("shutters") {
            puzzle.barriers.forEach { b ->
                add(buildJsonObject {
                    put("id", b.id)
                    put("open", b.open)
                    put("tiles", tiles(b.tiles))
                })
            }
        }
        if (puzzle.teleports.isNotEmpty()) putJsonArray("teleports") {
            puzzle.teleports.forEach { t ->
                add(buildJsonObject {
                    put("id", t.id)
                    put("kind", t.kind.name.lowercase())
                    put("from", tiles(t.from))
                    put("to", "${t.to.x},${t.to.y}")
                })
            }
        }
    }

    private fun status(status: MajorStatus) = when (status) {
        is MajorStatus.Asleep -> "asleep(${status.turns})"
        is MajorStatus.BadlyPoisoned -> "badly poisoned"
        MajorStatus.Burned -> "burned"
        MajorStatus.Frozen -> "frozen"
        MajorStatus.Paralyzed -> "paralyzed"
        MajorStatus.Poisoned -> "poisoned"
    }

    private fun volatile(status: VolatileStatus): String = when (status) {
        is VolatileStatus.Confused -> "confused(${status.turns})"
        is VolatileStatus.Infatuated -> "infatuated" + (status.with?.let { "(with ${it.wire})" } ?: "")
        is VolatileStatus.Bound -> "bound(${status.turns})"
        is VolatileStatus.PerishSong -> "perish_song(${status.turns})"
        else -> status.toString().substringBefore('(').replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()
    }
}
