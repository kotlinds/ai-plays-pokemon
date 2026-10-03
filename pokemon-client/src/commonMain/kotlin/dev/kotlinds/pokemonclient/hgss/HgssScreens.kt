package dev.kotlinds.pokemonclient.hgss

/**
 * The screen decoders of HeartGold / SoulSilver, tried in this order before the generic mapping of [HgssState].
 *
 * Each family lives in its own file (battle menus, post-battle screens, party and bag, keyboard / PC / shop / save,
 * text / banners / phone / touch menus).
 */
internal object HgssScreens {
    val decoders: List<HgssScreenDecoder> = listOf(
        HgssPostBattleScreens,
        HgssBattleScreens,
        HgssKeyboardPcShopScreens,
        HgssFlyMapScreens,
        HgssPartyBagScreens,
        HgssTextScreens,
    )
}

