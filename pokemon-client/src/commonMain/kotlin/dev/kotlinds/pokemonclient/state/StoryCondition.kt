package dev.kotlinds.pokemonclient.state

/** What a [StoryCondition] is evaluated against: the game's event flags, script variables and badges. */
interface StoryFacts {
    /** True when event flag [id] is set. */
    fun flag(id: Int): Boolean

    /** Value of script variable [id] (0 when unknown). */
    fun variable(id: Int): Int

    /** True when badge [index] is owned (game-specific numbering, e.g. HGSS 0..7 Johto, 8..15 Kanto). */
    fun hasBadge(index: Int): Boolean
}

/**
 * A condition on the story's progress, typed (never parsed from text): a story table step's `done`, or what lifts a
 * blocker. Ids are the game's own (flag and var ids of the decomp's constants).
 */
sealed interface StoryCondition {

    fun holds(facts: StoryFacts): Boolean

    /** Event flag [id] is set. */
    data class FlagSet(val id: Int) : StoryCondition {
        override fun holds(facts: StoryFacts) = facts.flag(id)
        override fun toString() = "flag 0x${id.toString(16)}"
    }

    /** Script variable [id] is at least [value] (scene vars only increase along the story). */
    data class VarAtLeast(val id: Int, val value: Int) : StoryCondition {
        override fun holds(facts: StoryFacts) = facts.variable(id) >= value
        override fun toString() = "var 0x${id.toString(16)} >= $value"
    }

    /** Badge [index] is owned. */
    data class HasBadge(val index: Int) : StoryCondition {
        override fun holds(facts: StoryFacts) = facts.hasBadge(index)
        override fun toString() = "badge $index"
    }

    /** Every condition holds. */
    data class And(val all: List<StoryCondition>) : StoryCondition {
        constructor(vararg all: StoryCondition) : this(all.toList())

        override fun holds(facts: StoryFacts) = all.all { it.holds(facts) }
        override fun toString() = all.joinToString(" and ", "(", ")")
    }

    /** At least one condition holds. */
    data class Or(val any: List<StoryCondition>) : StoryCondition {
        constructor(vararg any: StoryCondition) : this(any.toList())

        override fun holds(facts: StoryFacts) = any.any { it.holds(facts) }
        override fun toString() = any.joinToString(" or ", "(", ")")
    }

    /** Every flag id this condition reads. */
    fun flagIds(): Set<Int> = when (this) {
        is FlagSet -> setOf(id)
        is And -> all.flatMapTo(mutableSetOf()) { it.flagIds() }
        is Or -> any.flatMapTo(mutableSetOf()) { it.flagIds() }
        is VarAtLeast, is HasBadge -> emptySet()
    }

    /** Every variable id this condition reads. */
    fun varIds(): Set<Int> = when (this) {
        is VarAtLeast -> setOf(id)
        is And -> all.flatMapTo(mutableSetOf()) { it.varIds() }
        is Or -> any.flatMapTo(mutableSetOf()) { it.varIds() }
        is FlagSet, is HasBadge -> emptySet()
    }
}
