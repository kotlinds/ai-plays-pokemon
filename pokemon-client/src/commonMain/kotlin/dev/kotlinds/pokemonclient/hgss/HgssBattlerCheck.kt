package dev.kotlinds.pokemonclient.hgss

/**
 * Plausibility of a battler read from `BattleMon` (the battle's own copy of each Pokémon on the field), like
 * [HgssMonCheck] for the party: a reading caught while the game rewrites the structure (a switch, a capture, the
 * start of a battle) can show impossible values ("SPECIES_54116 Lv93"). Such a reading is never shown: the mapper
 * keeps the battler's last valid reading instead.
 */
object HgssBattlerCheck {
    /** Why [battler] can't be a real Pokémon (empty when it can). */
    fun problems(battler: Battler): List<String> = buildList {
        if (battler.species !in 1..MAX_SPECIES) add("species ${battler.species}")
        if (battler.level !in 1..100) add("level ${battler.level}")
        if (battler.maxHp !in 1..MAX_HP) add("max HP ${battler.maxHp}")
        if (battler.hp !in 0..battler.maxHp) add("HP ${battler.hp}/${battler.maxHp}")
        battler.moves.forEach { move ->
            if (move.id !in 1..MAX_MOVE) add("move ${move.id}")
            else if (move.pp !in 0..move.maxPp || move.maxPp > MAX_PP) add("PP ${move.pp}/${move.maxPp} of move ${move.id}")
        }
        if (battler.statStages.values.any { it !in -6..6 }) add("stat stages ${battler.statStages}")
    }

    /** Pokémon of Generation 4 (Arceus is 493). */
    private const val MAX_SPECIES = 493

    /** Highest HP any Pokémon can reach (Blissey, level 100). */
    private const val MAX_HP = 714

    /** Moves of Generation 4 (Shadow Force is 467). */
    private const val MAX_MOVE = 467

    /** 3 PP Ups on a 40 PP move. */
    private const val MAX_PP = 64
}
