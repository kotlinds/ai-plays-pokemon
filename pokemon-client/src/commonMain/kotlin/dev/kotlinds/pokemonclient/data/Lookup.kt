package dev.kotlinds.pokemonclient.data

import dev.kotlinds.pokemonclient.state.ItemId
import dev.kotlinds.pokemonclient.state.MoveId
import dev.kotlinds.pokemonclient.state.SpeciesId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * How much the agent may know beyond what the game shows. It changes what is **shown**, never what is computed.
 */
enum class KnowledgeLevel(val description: String) {
    /** Only what the game displays (and what a human sees on screen). */
    NONE("Only what the game shows."),

    /** Plus the Pokédex: species and move sheets, the type chart, estimated effectiveness in battle. */
    POKEDEX("Plus Pokémon and move sheets, the type chart and estimated effectiveness in battle."),

    /** Plus a walkthrough: trainers' teams, encounters, map events, the next goal and why a way is blocked. */
    POKEDEX_PLUS_WALKTHROUGH("Plus trainers' teams, encounters, the next goal and why a way is blocked."),
    ;

    /** True when this level shows what [required] shows. */
    fun allows(required: KnowledgeLevel) = ordinal >= required.ordinal
}

/** What `lookup` can be asked about. */
enum class LookupKind(val required: KnowledgeLevel, val description: String) {
    /** Items: a human reads their description in the bag and their price in shops. */
    ITEM(KnowledgeLevel.NONE, "an item: pocket, price"),
    SPECIES(KnowledgeLevel.POKEDEX, "a Pokémon species: types, base stats, abilities, evolutions"),
    MOVE(KnowledgeLevel.POKEDEX, "a move: type, category, power, accuracy, PP, priority"),
    LEARNSET(KnowledgeLevel.POKEDEX, "the moves a species learns by level"),
    MACHINE(KnowledgeLevel.POKEDEX, "a TM / HM: the move it teaches"),
    TYPE(KnowledgeLevel.POKEDEX, "an attacking type: what it is super effective / not very effective / useless against"),
}

/**
 * Answers `lookup(kind, id)` from the [GameData], within the [KnowledgeLevel]. Ids are the typed ones (`species:25`,
 * `move:85`, `item:17`, `tm01`, `type:fire`); a name is accepted too ("Pikachu", "Thunderbolt", accents and case
 * ignored), as a convenience.
 */
class Lookup(private val data: GameData, private val level: KnowledgeLevel) {

    /** The answer, or a failure whose message says why (unknown id, not allowed at this knowledge level). */
    fun lookup(kind: LookupKind, id: String): Result<JsonObject> = runCatching {
        require(level.allows(kind.required)) { "$kind needs the knowledge level ${kind.required} (the agent plays with $level)" }
        when (kind) {
            LookupKind.SPECIES -> species(findSpecies(id))
            LookupKind.LEARNSET -> learnset(findSpecies(id))
            LookupKind.MOVE -> move(findMove(id))
            LookupKind.ITEM -> item(findItem(id))
            LookupKind.MACHINE -> machine(id)
            LookupKind.TYPE -> type(findType(id))
        }
    }

    private fun species(info: SpeciesInfo) = buildJsonObject {
        put("id", "species:${info.id.value}")
        put("name", info.name)
        putJsonArray("types") { info.types.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.label)) } }
        putJsonObject("base_stats") {
            put("hp", info.baseStats.hp); put("attack", info.baseStats.attack); put("defense", info.baseStats.defense)
            put("sp_attack", info.baseStats.spAttack); put("sp_defense", info.baseStats.spDefense); put("speed", info.baseStats.speed)
            put("total", info.baseStats.total)
        }
        putJsonArray("abilities") { info.abilities.mapNotNull(data::abilityName).forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        put("catch_rate", info.catchRate)
        putJsonArray("evolutions") {
            info.evolutions.forEach { evo ->
                add(buildJsonObject {
                    put("to", data.species(evo.target)?.name ?: "species:${evo.target.value}")
                    put("method", evo.method.name.lowercase())
                    put("param", evo.param)
                })
            }
        }
    }

    private fun learnset(info: SpeciesInfo) = buildJsonObject {
        put("species", info.name)
        put("moves", buildJsonArray {
            data.learnset(info.id).forEach { lm ->
                add(buildJsonObject {
                    put("level", lm.level)
                    put("move", data.move(lm.move)?.name ?: "move:${lm.move.value}")
                    put("id", "move:${lm.move.value}")
                })
            }
        })
    }

    private fun move(info: MoveInfo) = buildJsonObject {
        put("id", "move:${info.id.value}")
        put("name", info.name)
        put("type", info.type.label)
        put("category", info.category.label)
        put("power", info.power)
        put("accuracy", info.accuracy)
        put("pp", info.pp)
        put("priority", info.priority)
        if (info.effectChance > 0) put("effect_chance", info.effectChance)
    }

    private fun item(info: ItemInfo) = buildJsonObject {
        put("id", "item:${info.id.value}")
        put("name", info.name)
        info.pocket?.let { put("pocket", it.name.lowercase()) }
        put("price", info.price)
        data.machineOf(info.id)?.let { machine -> data.machineMove(machine)?.let { put("teaches", data.move(it)?.name) } }
    }

    private fun machine(id: String): JsonObject {
        val number = Regex("(tm|hm)\\s*0*(\\d+)", RegexOption.IGNORE_CASE).find(id)
            ?: throw IllegalArgumentException("A machine is TM01..TM92 or HM01..HM08, not `$id`")
        val machine = MachineId(if (number.groupValues[1].lowercase() == "hm") MachineId.TM_COUNT + number.groupValues[2].toInt() else number.groupValues[2].toInt())
        val move = data.machineMove(machine)?.let(data::move) ?: throw IllegalArgumentException("No ${machine.label}")
        return buildJsonObject {
            put("machine", machine.label)
            put("move", move(move))
        }
    }

    private fun type(attacking: PokemonType) = buildJsonObject {
        put("type", attacking.label)
        val against = PokemonType.entries.groupBy { data.typeChart.effectiveness(attacking, it) }
        for ((effectiveness, types) in against) {
            if (effectiveness == Effectiveness.NORMAL) continue
            putJsonArray(effectiveness.name.lowercase()) { types.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.label)) } }
        }
    }

    // region Ids and names

    private fun findSpecies(raw: String): SpeciesInfo =
        idOrName(raw, "species", 1..data.speciesCount) { data.species(SpeciesId(it))?.name }?.let { data.species(SpeciesId(it)) }
            ?: throw IllegalArgumentException("Unknown species `$raw`")

    private fun findMove(raw: String): MoveInfo =
        idOrName(raw, "move", 1..data.moveCount) { data.move(MoveId(it))?.name }?.let { data.move(MoveId(it)) }
            ?: throw IllegalArgumentException("Unknown move `$raw`")

    private fun findItem(raw: String): ItemInfo =
        idOrName(raw, "item", 1..data.itemCount) { data.item(ItemId(it))?.name }?.let { data.item(ItemId(it)) }
            ?: throw IllegalArgumentException("Unknown item `$raw`")

    private fun findType(raw: String): PokemonType {
        val value = normalize(raw.removePrefix("type:"))
        return PokemonType.entries.firstOrNull { normalize(it.label) == value || normalize(it.name) == value }
            ?: throw IllegalArgumentException("Unknown type `$raw` (one of ${PokemonType.entries.joinToString { it.label }})")
    }

    /** `prefix:<number>`, a bare number, or a name found among [range] with [name]. */
    private fun idOrName(raw: String, prefix: String, range: IntRange, name: (Int) -> String?): Int? {
        raw.removePrefix("$prefix:").toIntOrNull()?.let { return it.takeIf { id -> id in range } }
        val wanted = normalize(raw)
        return range.firstOrNull { name(it)?.let(::normalize) == wanted }
    }

    private fun normalize(value: String) = value.lowercase()
        .map { c -> ACCENTS[c] ?: c }.filter { it.isLetterOrDigit() }.joinToString("")

    // endregion

    private companion object {
        val ACCENTS = mapOf('é' to 'e', 'è' to 'e', 'ê' to 'e', 'à' to 'a', 'â' to 'a', 'î' to 'i', 'ï' to 'i', 'ô' to 'o', 'ù' to 'u', 'û' to 'u', 'ç' to 'c')
    }
}
