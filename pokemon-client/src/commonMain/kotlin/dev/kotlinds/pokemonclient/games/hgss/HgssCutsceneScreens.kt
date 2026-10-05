package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.state.AnimationKind
import dev.kotlinds.pokemonclient.state.Screen

/**
 * Field sub-applications that only play a scene, as [Screen.Animation]s with a hint on how to get through them:
 * - the credits (overlay 76, `Credits_Init`, src/credits/credits.c), after the Hall of Fame: the game was saved when
 *   the team was registered, so a soft reset skips them with nothing lost (back home in New Bark Town);
 * - the S.S. Aqua crossing between Olivine City and Vermilion City (overlay 105, launched by `sub_0203FC90`): it ends
 *   by itself at the other port.
 */
internal object HgssCutsceneScreens : HgssScreenDecoder {

    /** What [Screen.Animation.hint] says on the credits. */
    const val CREDITS_HINT = "The credits after the Hall of Fame: the game was saved when the team was registered, " +
        "so soft_reset now skips them with nothing lost (you wake up at home in New Bark Town); waiting takes minutes"

    /** What [Screen.Animation.hint] says during the crossing. */
    const val SHIP_HINT = "The S.S. Aqua crossing between Olivine City and Vermilion City: wait, it ends by itself at the other port"

    override fun decode(mem: HgssMemory, state: HgssState): Screen? {
        if (state.mode != GameMode.APP) return null
        return when (state.modeDetail) {
            "credits" -> Screen.Animation(AnimationKind.CUTSCENE, CREDITS_HINT)
            "ship_crossing" -> Screen.Animation(AnimationKind.CUTSCENE, SHIP_HINT)
            else -> null
        }
    }
}
