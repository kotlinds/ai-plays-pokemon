package dev.kotlinds.pokemonclient.games.platinum

import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.games.gen4.Gen4Memory
import dev.kotlinds.pokemonclient.games.gen4.Gen4NamingAddresses

/**
 * Version-specific absolute addresses of Pokémon Platinum (ARM9 main binary and overlay functions / globals), one
 * instance per ROM game code.
 *
 * [PLATINUM_US] ("CPUE") comes from `build/main.nef.xMAP` of the local pret/pokeplatinum build (revision 1): its ARM9
 * binary and every overlay are byte-identical to the user's ROM (only 5 data files differ). Function pointers are
 * stored without the Thumb bit. Overlay functions share addresses between overlays (TitleScreen_Init, RowanIntro_Init
 * and GameStartRowanIntro_Init are all 0x021D0D80): compare the `main` function of the template (unique here) rather
 * than `init`.
 */
data class PlatinumVersion(
    val gameCode: String,
    val displayName: String,

    // --- Globals ---
    /** `static Application sApplication` (src/main.c:56): the top-level application (overlay id, manager). */
    val application: Long,
    /** `System gSystem` (include/system.h). */
    val gSystem: Long,
    /** `static FieldSystem *sFieldSystem` (src/field_system.c): set by the field app, not cleared on exit. */
    val fieldSystemPtr: Long,
    /** `static SaveData *sSaveDataPtr` (src/savedata.c). */
    val saveDataPtr: Long,
    /** `static ScreenFadeManager sScreenFadeManager` (src/screen_fade.c); `active` is at +0x14C. */
    val screenFadeManager: Long,
    /** `static SysTask *sTextPrinterTasks[8]` (src/text.c). */
    val textPrinterTasks: Long,
    /** The naming keyboard (src/applications/naming_screen.c). */
    val naming: Gen4NamingAddresses,

    // --- Application main functions (ApplicationManagerTemplate.main) ---
    val fnOpeningMain: Long,        // ov77_021D2D94 (gOpeningCutsceneAppTemplate, overlay game_opening)
    val fnTitleScreenMain: Long,    // TitleScreen_Main (overlay game_opening)
    val fnMainMenuMain: Long,       // MainMenu_Main (overlay main_menu)
    val fnRowanIntroMain: Long,     // RowanIntro_Main (overlay rowan_intro)
    val fnRowanIntroTvMain: Long,   // RowanIntroTv_Main (overlay rowan_intro)
    val fnNamingScreenMain: Long,   // NamingScreen_Main (main binary)
    val fnFieldMain: Long,          // ExecuteFieldProcesses (gFieldSystemNewGameTemplate / gFieldSystemContinueTemplate)
    /**
     * Transitional applications with nothing to show (a black screen for a few frames): sub_0209A300
     * (src/unk_0209A2C4.c, before the main menu), GameStartRowanIntro_Main, GameStartNewSave_Main and
     * GameStartLoadSave_Main (src/game_start.c: create or load the save).
     */
    val fnLoadingMains: Set<Long>,

    // --- Field tasks and script waits (src/script_manager.c, src/scrcmd.c) ---
    val fnFieldTaskRunScript: Long,           // FieldTask_RunScript
    /**
     * Tasks of map changes: the FieldTask_* functions of src/field_transition.c and src/field_map_change.c (map loads,
     * warps, fades), the door / stairs walks of src/unk_02056B30.c and their door animations (overlay 5,
     * src/overlay005/ov5_021D431C.c).
     */
    val fnMapTransitionTasks: Set<Long>,
    val fnScrWaitForFinishedPrinting: Long,   // ScriptContext_WaitForFinishedPrinting
    val fnScrCheckABPress: Long,              // ScriptContext_CheckABPress
    val fnScrDecrementABPressTimer: Long,     // ScriptContext_DecrementABPressTimer
    val fnScrCheckABXPadPress: Long,          // ScriptContext_CheckABXPadPress
    val fnScrCheckABPadPress: Long,           // ScriptContext_CheckABPadPress
    val fnScrWaitForYesNoResult: Long,        // ScriptContext_WaitForYesNoResult
    val fnScrSignpostPrinting: Long,          // WaitScrollingSignpostInput: a sign's message printing
    val fnScrSignpostInput: Long,             // HandleSignpostInput: a sign's message waiting for A / B (or a turn)

    // --- ROM data ---
    /** `sMapHeaders` (src/map_header.c, .rodata): [PlatinumMapHeaders.COUNT] headers of 24 bytes. */
    val mapHeaders: Long,
) {
    companion object {
        /** Pokémon Platinum Version (USA), game code CPUE, decomp build pokeplatinum.us revision 1. */
        val PLATINUM_US = PlatinumVersion(
            gameCode = "CPUE",
            displayName = "Pokémon Platinum (USA)",
            application = 0x02101D28L,
            gSystem = 0x021BF67CL,
            fieldSystemPtr = 0x021C07DCL,
            saveDataPtr = 0x021C0794L,
            screenFadeManager = 0x021BF474L,
            textPrinterTasks = 0x021C04E0L,
            naming = Gen4NamingAddresses(
                vblankCallback = 0x02087190L, appData = 0x021C0A30L, delayCounterOffset = 0x5CCL,
                cursorSprite = (0x32CL + 4 * 8) to 0x34L, // uiSprites[NMS_SPRITE_CURSOR = 8], Sprite.draw
            ),
            fnOpeningMain = 0x021D2D94L,
            fnTitleScreenMain = 0x021D0E3CL,
            fnMainMenuMain = 0x0222BE24L,
            fnRowanIntroMain = 0x021D0E20L,
            fnRowanIntroTvMain = 0x021D3280L,
            fnNamingScreenMain = 0x02086B64L,
            fnFieldMain = 0x0203CCCCL,
            fnLoadingMains = setOf(0x0209A300L, 0x021D0D98L, 0x021D0DE0L, 0x021D0E34L),
            fnFieldTaskRunScript = 0x0203E950L,
            fnMapTransitionTasks = setOf(
                0x02053570L, 0x020535E8L, 0x02053718L, 0x02053878L, 0x02053930L, 0x020539A0L, 0x020539E8L, 0x02053A04L, 0x02053A80L, 0x02053AB4L, 0x02053AFCL, 0x02053B44L, 0x02053BD4L, 0x02053C70L, 0x02053CB4L, 0x02053CD4L, 0x02053D0CL, 0x02053DB4L, 0x02053E5CL, 0x02053E98L, 0x02054064L, 0x02054084L, 0x0205430CL, 0x0205444CL, 0x02054778L, 0x02054800L, 0x0205578CL, 0x02055808L, 0x02055850L, 0x02055898L, 0x02055934L, 0x02055984L,
                0x02056B30L, 0x02056B70L, 0x02056BDCL, 0x02056C18L, 0x02056CFCL, 0x02056DE4L, 0x02056E20L, 0x02056EA4L, 0x02056F1CL, 0x02056FC0L, 0x02057008L, 0x02057050L, 0x0205711CL, 0x020571A0L, 0x02057218L, 0x020572B8L, 0x02057300L, 0x02057368L,
                0x021D4E10L, 0x021D4F14L, 0x021D4FA0L, 0x021D5020L, 0x021D5150L,
            ),
            fnScrWaitForFinishedPrinting = 0x02040014L,
            fnScrCheckABPress = 0x02040190L,
            fnScrDecrementABPressTimer = 0x020401D0L,
            fnScrCheckABXPadPress = 0x02040204L,
            fnScrCheckABPadPress = 0x02040294L,
            fnScrWaitForYesNoResult = 0x02040824L,
            fnScrSignpostPrinting = 0x02040670L,
            fnScrSignpostInput = 0x02040730L,
            mapHeaders = 0x020E601CL,
        )

        /** Every supported Platinum ROM. */
        val ALL: List<PlatinumVersion> = listOf(PLATINUM_US)

        fun forGameCode(code: String): PlatinumVersion? = ALL.firstOrNull { it.gameCode == code }
    }
}

/** Gen 4 safe reads ([Gen4Memory]) with the Platinum address table at hand. */
class PlatinumMemory(memory: Memory, val version: PlatinumVersion) : Gen4Memory(memory, version.gSystem) {

    /** A screen fade (`sScreenFadeManager.active`) is running: the screen ignores input meanwhile. */
    val fading: Boolean get() = u16(version.screenFadeManager + SCREEN_FADE_ACTIVE) != 0

    private companion object {
        /** `ScreenFadeManager.active` (src/screen_fade.c:51, offsetof with the decomp's compiler). */
        const val SCREEN_FADE_ACTIVE = 0x14CL
    }
}
