package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.state.ItemId
import dev.kotlinds.pokemonclient.state.MomParcel
import dev.kotlinds.pokemonclient.state.Named
import dev.kotlinds.pokemonclient.state.PokegearCard

/**
 * The Pokégear's save data (`SavePokegear`, include/save_pokegear.h, save array `SAVE_POKEGEAR`): the cards it has
 * and the items Mom bought, waiting at the delivery man of every Poké Mart.
 *
 * - Cards: `registeredCards` (2 bits of the word at 0x04, from bit 25: `GEARCARD_MAP` = 1, `GEARCARD_RADIO` = 2,
 *   `SavePokegear_RegisterCard`); the Expansion Card is the flag `FLAG_GOT_EXPN_CARD` (the radio reads it for its
 *   Kanto band, `Radio_GetAvailableChannels`).
 * - Mom's parcels: `PhoneCallPersistentState.momGiftQueue[5][2]` ({item, quantity}, src/save_pokegear.c); Mom fills
 *   it when the money she saves reaches a threshold (src/mom_gift.c) and calls to say so; the delivery man of the Poké
 *   Marts shows up while it isn't empty (`MomGiftCheck`, scr_seq_0144.s) and hands the first one.
 */
internal object HgssPokegearSave {

    /** `SAVE_POKEGEAR` (include/constants/save_arrays.h). */
    private const val SAVE_POKEGEAR = 34

    /** `FLAG_GOT_EXPN_CARD` (include/constants/flags.h). */
    const val FLAG_GOT_EXPN_CARD = 0x11F

    private const val CARDS_WORD = 0x04L
    private const val CARDS_SHIFT = 25
    private const val CARD_MAP = 1
    private const val CARD_RADIO = 2

    /** `callPersistentState` (0x4B8) + `momGiftQueue` (after 75 `PhoneRematch` of 4 bytes). */
    private const val MOM_GIFT_QUEUE = 0x4B8L + 75 * 4
    private const val MOM_GIFT_SLOTS = 5

    /** The cards the Pokégear has, null when the save isn't readable. */
    fun cards(mem: HgssMemory, save: HgssSave = HgssSave(mem)): Set<PokegearCard>? {
        val gear = save.array(SAVE_POKEGEAR) ?: return null
        val bits = (mem.u32(gear + CARDS_WORD) shr CARDS_SHIFT).toInt() and 3
        val expansion = save.flag(FLAG_GOT_EXPN_CARD) ?: return null
        return buildSet {
            if (bits and CARD_MAP != 0) add(PokegearCard.MAP)
            if (bits and CARD_RADIO != 0) add(PokegearCard.RADIO)
            if (expansion) add(PokegearCard.EXPANSION)
        }
    }

    /** Mom's parcels waiting at the Poké Marts, oldest first (the delivery man gives the first). */
    fun momParcels(mem: HgssMemory, save: HgssSave = HgssSave(mem)): List<MomParcel> {
        val gear = save.array(SAVE_POKEGEAR) ?: return emptyList()
        return (0 until MOM_GIFT_SLOTS).mapNotNull { i ->
            val slot = gear + MOM_GIFT_QUEUE + 4L * i
            val item = mem.u16(slot)
            val quantity = mem.u16(slot + 2)
            if (quantity == 0 || item == 0) null else MomParcel(Named(ItemId(item), HgssData.itemName(item)), quantity)
        }
    }
}
