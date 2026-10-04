package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.hgss.HgssRomBytes.u16
import dev.kotlinds.pokemonclient.hgss.HgssRomBytes.u8

/**
 * Which trainer a person on the map is, from its script (never from its sprite or what it says), with the ROM's
 * trainer data (class, name).
 *
 * Three ways, in order:
 * 1. a common trainer script `std_trainer(id)` / `std_trainer_2(id)` (script ids 3000+ / 5000+,
 *    `ScriptNumToTrainerNum`, src/fieldmap.c) — the route trainers, who also have a sight range;
 * 2. a `TrainerBattle id` command in the object's own map script (gym leaders, the Elite Four...);
 * 3. a `TrainerBattle id` of another script of the zone just after an `ApplyMovement` of this object: scenes
 *    where one script battles several people in a row (the Kimono Girls of the Dance Theater,
 *    scr_seq_T27R0501_016).
 *
 * Whether a trainer was beaten: its trainer flag, or for a scripted battle the flag its script sets once the battle
 * is won (`CheckBattleWon` then `SetFlag`): the Elite Four only set `FLAG_DEFEATED_<NAME>` (scr_seq_T10R0501_001).
 */
internal object HgssTrainers {

    /** The trainer id of map object [localId] (script [scriptId]) of zone [zoneId], or null when it isn't a trainer. */
    fun trainerOf(zoneId: Int, localId: Int, scriptId: Int): Int? {
        commonScriptTrainer(scriptId)?.let { return it }
        val scenes = zoneScenes(zoneId) ?: return null
        return scenes.own[scriptId] ?: scenes.moved[localId]
    }

    /** The flag zone [zoneId]'s scripts set after beating [trainerId] (null when they set none). */
    fun wonFlag(zoneId: Int, trainerId: Int): Int? = zoneScenes(zoneId)?.wonFlags?.get(trainerId)

    /** `ScriptNumToTrainerNum` for the common trainer scripts, null for other scripts. */
    fun commonScriptTrainer(scriptId: Int): Int? = when (scriptId) {
        in STD_TRAINER until STD_TRAINER_2 -> scriptId - STD_TRAINER + FIRST_TRAINER_INDEX
        in STD_TRAINER_2 until STD_ITEM_BALL -> scriptId - STD_TRAINER_2 + FIRST_TRAINER_INDEX
        else -> null
    }

    /** "Psychic" / "Eli" for trainer [trainerId], from the ROM (null without a ROM). */
    fun names(trainerId: Int): Pair<String, String>? {
        val data = HgssData.gameData ?: return null
        val classId = data.trainerClassOf(trainerId) ?: return null
        val name = data.trainerNames.getOrNull(trainerId).orEmpty()
        return HgssData.trainerClassName(classId) to name
    }

    /** What the scripts of one zone tell about its trainers. */
    class Scenes(
        /** Event script id (1-based) → the trainer its own script battles. */
        val own: Map<Int, Int>,
        /** Object local id → the trainer battled right after this object was moved by a scene script. */
        val moved: Map<Int, Int>,
        /** Trainer id → the flag set once its scripted battle is won. */
        val wonFlags: Map<Int, Int> = emptyMap(),
    )

    private val scenes = HashMap<Int, Scenes?>()

    private fun zoneScenes(zoneId: Int): Scenes? = synchronized(scenes) {
        scenes.getOrPut(zoneId) { HgssData.world?.scriptFile(zoneId)?.let(::scan) }
    }

    /** Finds every `TrainerBattle` of [file] and attributes it to its script and to the object moved just before. */
    fun scan(file: ByteArray): Scenes {
        val starts = HgssScripts.scriptStarts(file)
        val count = HgssData.gameData?.trainerCount ?: LAST_TRAINER_INDEX + 1
        val code = starts.minOrNull() ?: return Scenes(emptyMap(), emptyMap())
        val battles = (code..file.size - BATTLE_SIZE).filter { o -> isTrainerBattle(file, o, count) }
        val own = HashMap<Int, Int>()
        for ((index, start) in starts.withIndex()) {
            val end = starts.filter { it > start }.minOrNull() ?: file.size
            // Rematch versions of a trainer come later in the trainer table: keep the first one.
            battles.filter { it in start until end }.minOfOrNull { u16(file, it + 2) }?.let { own[index + 1] = it }
        }
        val moved = HashMap<Int, Int>()
        for (o in battles) {
            val trainer = u16(file, o + 2)
            val mover = (o - 1 downTo maxOf(code, o - MOVE_LOOKBACK)).firstOrNull { m -> isApplyMovement(file, m) }
                ?.let { u16(file, it + 2) } ?: continue
            moved.putIfAbsent(mover, trainer)
        }
        val wonFlags = HashMap<Int, Int>()
        for (o in battles) {
            val won = (o + BATTLE_SIZE until minOf(file.size - 4, o + WON_LOOKAHEAD)).firstOrNull { c -> isCheckBattleWon(file, c) } ?: continue
            val set = (won + 4 until minOf(file.size - 4, won + SET_FLAG_LOOKAHEAD)).firstOrNull { f -> u16(file, f) == SET_FLAG } ?: continue
            wonFlags.putIfAbsent(u16(file, o + 2), u16(file, set + 2))
        }
        return Scenes(own, moved, wonFlags)
    }

    /** `CheckBattleWon VAR_SPECIAL_RESULT` (command 220): the script tests the battle it just ran. */
    private fun isCheckBattleWon(file: ByteArray, o: Int): Boolean = u16(file, o) == CHECK_BATTLE_WON && u16(file, o + 2) == VAR_SPECIAL_RESULT

    /** `TrainerBattle trainer, u16, u8, u8` (script command 213, asm/macros/script.inc) with plausible arguments. */
    private fun isTrainerBattle(file: ByteArray, o: Int, trainerCount: Int): Boolean =
        u16(file, o) == TRAINER_BATTLE && u16(file, o + 2) in FIRST_TRAINER_INDEX until trainerCount &&
            u16(file, o + 4) < MAX_BATTLE_ARG && u8(file, o + 6) < MAX_BATTLE_ARG && u8(file, o + 7) < MAX_BATTLE_ARG

    /** `ApplyMovement object, offset` (command 94) on a map object (not the player 0xFF nor the camera). */
    private fun isApplyMovement(file: ByteArray, o: Int): Boolean =
        o + 8 <= file.size && u16(file, o) == APPLY_MOVEMENT && u16(file, o + 2) < MAX_LOCAL_ID

    private const val STD_TRAINER = 3000
    private const val STD_TRAINER_2 = 5000
    private const val STD_ITEM_BALL = 7000
    private const val FIRST_TRAINER_INDEX = 1
    private const val LAST_TRAINER_INDEX = 740
    private const val TRAINER_BATTLE = 213
    private const val APPLY_MOVEMENT = 94
    private const val CHECK_BATTLE_WON = 220
    private const val SET_FLAG = 30
    private const val VAR_SPECIAL_RESULT = 0x800C

    /** From a battle to its `CheckBattleWon` (a rematch battle and a `GoTo` may sit in between, scr_seq_T10R0501_001). */
    private const val WON_LOOKAHEAD = 0x30

    /** From `CheckBattleWon` to the `SetFlag` of a win: `Compare` and `GoToIfEq` (the loss) in between. */
    private const val SET_FLAG_LOOKAHEAD = 0x18
    private const val BATTLE_SIZE = 8
    private const val MAX_BATTLE_ARG = 8
    private const val MAX_LOCAL_ID = 64

    /** A scene moves the trainer forward, prints a line and waits a little before the battle: a short lookback. */
    private const val MOVE_LOOKBACK = 0x30
}
