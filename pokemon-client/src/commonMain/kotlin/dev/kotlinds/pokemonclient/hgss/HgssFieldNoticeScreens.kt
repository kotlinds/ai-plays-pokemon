package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.data.TextBankId
import dev.kotlinds.pokemonclient.state.AnimationKind
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TextSource

/** RAM layout of the screens of [HgssFieldNoticeScreens] (HeartGold US). */
internal object HgssFieldNoticeAddresses {
    /** `Task_ShowPrintedBlackoutMessage` (src/blackout.c), the field task showing the whiteout text. */
    const val FN_TASK_BLACKOUT_MESSAGE = 0x020526D4L

    /** `BlackoutScreenEnvironment.state` (include/blackout.h). */
    const val BLACKOUT_STATE = 0x00L

    /** `STATE_SHOW_PRINTED_BLACKOUT_FADE_OUT_INPUT`: the text is shown, A / B / touch goes on. */
    const val BLACKOUT_STATE_INPUT = 2

    /** The whiteout texts (msg_0203): 3 "scurried to a Pokémon Center...", 4 "scurried back home..." (in the house). */
    val BLACKOUT_BANK = TextBankId(203)
    const val BLACKOUT_LINE_CENTER = 3
    const val BLACKOUT_LINE_HOME = 4

    /** `MAP_NEW_BARK_PLAYER_HOUSE_1F`: whiting out there "scurries back home". */
    const val MAP_PLAYER_HOUSE_1F = 63
}

/**
 * Screens of the field that wait without a script message box: the whiteout text after losing a battle ("ACE
 * scurried to a Pokémon Center...", A goes on) and the legendary cinematic (Ho-Oh / Lugia appearing: an animation
 * that reads no key, overlay 106).
 */
internal object HgssFieldNoticeScreens : HgssScreenDecoder {
    private val N = HgssFieldNoticeAddresses

    override fun decode(mem: HgssMemory, state: HgssState): Screen? {
        if (mem.version != HgssVersion.HEARTGOLD_US) return null
        if (state.mode == GameMode.APP && state.modeDetail == "legendary_cinematic") return Screen.Animation(AnimationKind.CUTSCENE)
        if (state.mode !in FIELD_MODES) return null
        val fs = mem.ptr(mem.version.fieldSystemPtr) ?: return null
        val blackout = HgssFieldTasks.chain(mem, fs).firstOrNull { it.function == N.FN_TASK_BLACKOUT_MESSAGE }?.env ?: return null
        val awaiting = if (mem.s32(blackout + N.BLACKOUT_STATE) == N.BLACKOUT_STATE_INPUT) Awaiting.INPUT else Awaiting.ANIMATION
        return Screen.Dialogue(TextSource.FIELD, null, blackoutText(state), awaiting)
    }

    /** The whiteout text from the ROM with the player's name, or a plain description without the ROM's text. */
    private fun blackoutText(state: HgssState): String {
        val home = state.location?.mapId == N.MAP_PLAYER_HOUSE_1F
        val raw = HgssData.gameData?.rawLine(N.BLACKOUT_BANK, if (home) N.BLACKOUT_LINE_HOME else N.BLACKOUT_LINE_CENTER)
        val player = state.player?.name ?: "You"
        return raw?.let { HgssText.decode(it, firstPlaceholder = player).replace('\n', ' ').replace("  ", " ").trim() }
            ?: "$player whited out and scurried ${if (home) "back home" else "to a Pokémon Center"}."
    }

    private val FIELD_MODES = setOf(GameMode.OVERWORLD, GameMode.FIELD_BUSY, GameMode.SCRIPT, GameMode.DIALOGUE)
}
