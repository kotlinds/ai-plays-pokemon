package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.data.TextBankId

/**
 * The text banks of HeartGold / SoulSilver used by [HgssGameData]: members of the message archive
 * (`msgdata/msg.narc`, ROM path [HgssGameData.MESSAGE_NARC]), by index (`NARC_msg_msg_XXXX_bin`, files/msgdata/msg.naix).
 *
 * The indices are the same in every language release: a translated ROM has the same banks with translated lines.
 */
object HgssTextBanks {
    /** Item names, index = item id (src/item.c GetItemNameIntoString). */
    val ITEM_NAMES = TextBankId(222)

    /** Species names, index = species id (src/message_format.c BufferSpeciesName). */
    val SPECIES_NAMES = TextBankId(237)

    /** Map section (location) names, index = mapsec id. */
    val MAP_SECTION_NAMES = TextBankId(279)

    /** Ability names, index = ability id (BufferAbilityName). */
    val ABILITY_NAMES = TextBankId(720)

    /** NPC trainer names, index = trainer id (src/trainer_data.c EnemyTrainerSet_Init, msg_0729). */
    val TRAINER_NAMES = TextBankId(729)

    /** Trainer class names, index = trainer class id (BufferTrainerClassName). */
    val TRAINER_CLASS_NAMES = TextBankId(730)

    /** Type names, index = type id (BufferTypeName). */
    val TYPE_NAMES = TextBankId(735)

    /** Move names, index = move id (BufferMoveName). */
    val MOVE_NAMES = TextBankId(750)
}

