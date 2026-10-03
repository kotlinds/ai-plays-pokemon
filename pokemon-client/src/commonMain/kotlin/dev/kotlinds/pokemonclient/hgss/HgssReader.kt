package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.hgss.HgssAddresses as A

/**
 * Reads a structured snapshot of Pokémon HeartGold / SoulSilver from NDS main RAM.
 *
 * The code is shared by every HG/SS build; absolute addresses come from a [HgssVersion] chosen by the ROM game
 * code found in the cartridge header copy in RAM (0x023FFE0C), or forced with [version]. Only "IPKE"
 * (HeartGold USA) is filled in for now.
 *
 * Entry points:
 *  - [read]: full snapshot ([HgssState]), or null if the RAM does not look like a running HGSS at all.
 *
 * Every pointer is validated to be inside main RAM before being followed; any unexpected value produces a
 * partial state (+ a warning) instead of an exception.
 */
class HgssReader(private val memory: Memory, private val version: HgssVersion? = null) {

    /** Addresses of the version being read, resolved at the start of each [read]. */
    private var v: HgssVersion = version ?: HgssVersion.HEARTGOLD_US

    /**
     * Picks the address table: the forced [version], else the one matching the game code in RAM.
     * Returns null + the game code when the ROM is not a supported version.
     */
    private fun resolveVersion(): Pair<HgssVersion?, String?> {
        version?.let { return it to it.gameCode }
        val code = HgssVersion.readGameCode(memory) ?: return HgssVersion.HEARTGOLD_US to null
        return HgssVersion.forGameCode(code) to code
    }

    // ================================================================================================
    // Low-level helpers
    // ================================================================================================

    private fun inRam(addr: Long, size: Long = 1) = addr >= A.MAIN_RAM_START && addr + size <= A.MAIN_RAM_END

    private fun u8(addr: Long): Int = if (inRam(addr)) memory.read8(addr) and 0xFF else 0
    private fun u16(addr: Long): Int = if (inRam(addr, 2)) memory.read16(addr) and 0xFFFF else 0
    private fun s8(addr: Long): Int = u8(addr).toByte().toInt()
    private fun s16(addr: Long): Int = u16(addr).toShort().toInt()
    private fun u32(addr: Long): Long = if (inRam(addr, 4)) memory.read32(addr) and 0xFFFFFFFFL else 0L
    private fun s32(addr: Long): Int = u32(addr).toInt()

    /** Follows a pointer stored at [addr]; returns null if NULL / outside main RAM / misaligned. */
    private fun ptr(addr: Long, align: Int = 4): Long? {
        val p = u32(addr)
        if (p == 0L || !inRam(p, 4) || p % align != 0L) return null
        return p
    }

    private fun bytes(addr: Long, size: Int): ByteArray? =
        if (size >= 0 && inRam(addr, size.toLong())) memory.readBytes(addr, size) else null

    private fun fn(addr: Long): Long = u32(addr) and A.THUMB_MASK

    private fun chars(addr: Long, count: Int): IntArray = IntArray(count) { u16(addr + 2L * it) }

    /** Reads a `String` object (include/pm_string.h) if its magic matches. */
    private fun readGameString(strPtr: Long?): String? {
        if (strPtr == null) return null
        if (u32(strPtr + A.STR_MAGIC) != A.STRING_MAGIC) return null
        val max = u16(strPtr + A.STR_MAXSIZE)
        val size = u16(strPtr + A.STR_SIZE)
        if (size > max || size > 2048) return null
        return HgssText.decode(chars(strPtr + A.STR_DATA, size))
    }

    private class Ctx {
        val warnings = mutableListOf<String>()
    }

    // ================================================================================================
    // Snapshot
    // ================================================================================================

    fun read(): HgssState? = try {
        val (resolved, code) = resolveVersion()
        when {
            resolved != null -> {
                v = resolved
                readInternal()?.let { st ->
                    if (code == null) st.copy(warnings = st.warnings + "game code unreadable, assumed ${v.gameCode}") else st
                }
            }
            // A HG/SS ROM we have no address table for: say so instead of reading garbage.
            code != null && code.take(3) in HgssVersion.HGSS_GAME_CODE_PREFIXES ->
                HgssState(frame = 0, mode = GameMode.UNKNOWN, modeDetail = "unsupported HG/SS version $code")
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    private fun readInternal(): HgssState? {
        val ctx = Ctx()
        val frame = u32(v.gSystem + A.SYS_VBLANK_COUNTER)
        val mainOvy = s32(v.mainAppState + A.MAIN_APP_OVERLAY_ID)
        val om = ptr(v.mainAppState + A.MAIN_APP_OVERLAY_MANAGER)
            ?: return HgssState(frame = frame, mode = GameMode.LOADING, modeDetail = "no main app")
        val init = fn(om + A.OM_INIT)
        val tmplOvy = s32(om + A.OM_OVY_ID)
        val execState = s32(om + A.OM_EXEC_STATE)

        if (init == v.fnFieldContinueAppInit || init == v.fnFieldNewGameAppInit) {
            return readField(ctx, frame, om, execState)
        }
        val (mode, detail) = when {
            mainOvy == A.OVY_INTRO_TITLE && init == v.fnIntroMovieInit -> GameMode.INTRO_MOVIE to null
            mainOvy == A.OVY_INTRO_TITLE && init == v.fnTitleScreenInit -> GameMode.TITLE_SCREEN to null
            mainOvy == A.OVY_MAIN_MENU && init == v.fnMainMenuInit -> GameMode.MAIN_MENU to null
            tmplOvy == A.OVY_OAK_SPEECH -> GameMode.NEW_GAME_INTRO to "oak_speech"
            init == v.fnCheckSaveInit -> GameMode.LOADING to "check_save"
            mainOvy == A.OVY_36 -> GameMode.LOADING to "starting_game"
            else -> GameMode.UNKNOWN to "mainOverlay=$mainOvy templateOverlay=$tmplOvy init=0x${init.toString(16)}"
        }
        // Professor Oak's intro prints its dialog from OakSpeechData.string (src/oaks_speech.c:949). The String is
        // freed once printed but normally stays readable until the next message: treat it as "last message".
        val dialogue = if (mode == GameMode.NEW_GAME_INTRO) {
            ptr(om + A.OM_DATA)?.let { data -> readGameString(ptr(data + A.OAK_STRING)) }
                ?.let { DialogueInfo(text = it, messageBoxOpen = true, waitingFor = "intro") }
        } else null
        // Outside the field we can only tell that nothing is fading (menus/intro wait for a button most of the time).
        val awaiting = mode in setOf(GameMode.INTRO_MOVIE, GameMode.TITLE_SCREEN, GameMode.MAIN_MENU, GameMode.NEW_GAME_INTRO) && !isFading()
        return HgssState(frame = frame, mode = mode, modeDetail = detail, dialogue = dialogue, awaitingInput = awaiting)
    }

    private fun readField(ctx: Ctx, frame: Long, om: Long, execState: Int): HgssState {
        val omData = ptr(om + A.OM_DATA)
        var fs = ptr(v.fieldSystemPtr)
        if (fs == null || (omData != null && fs != omData)) {
            if (omData != null) {
                ctx.warnings += "sFieldSysPtr (${fs?.toString(16)}) != field app data (${omData.toString(16)}), using app data"
                fs = omData
            }
        }
        if (fs == null || execState < 2) {
            return HgssState(frame = frame, mode = GameMode.LOADING, modeDetail = "field starting", warnings = ctx.warnings)
        }

        val sub0 = ptr(fs + A.FS_SUB0)
        val fieldMapApp = sub0?.let { ptr(it + A.FSS0_FIELD_MAP_APP) }
        val subApp = sub0?.let { ptr(it + A.FSS0_SUB_APP) }
        val paused = sub0?.let { u32(it + A.FSS0_IS_PAUSED) != 0L } ?: true
        val taskman = ptr(fs + A.FS_TASKMAN)
        val mapReady = u32(fs + A.FS_MAP_READY) != 0L
        val playerControllable = subApp == null && fieldMapApp != null && taskman == null && mapReady && !paused

        var mode: GameMode
        var detail: String? = null
        var dialogue: DialogueInfo? = null
        var startMenu: StartMenuInfo? = null
        var menu: MenuInfo? = null
        var app: AppInfo? = null
        var battle: BattleInfo? = null
        var battleSystem: Long? = null

        if (subApp != null) {
            val appInit = fn(subApp + A.OM_INIT)
            val appOvy = s32(subApp + A.OM_OVY_ID)
            val name = v.appByInit[appInit] ?: A.APP_BY_OVERLAY[appOvy] ?: "app(ovy=$appOvy,init=0x${appInit.toString(16)})"
            if (name == "battle") {
                mode = GameMode.BATTLE
                val procState = s32(subApp + A.OM_PROC_STATE)
                val appExec = s32(subApp + A.OM_EXEC_STATE)
                if (appExec == 2 && procState == A.BATTLE_STATE_MAIN) {
                    battleSystem = ptr(subApp + A.OM_DATA)
                    battle = battleSystem?.let { readBattle(ctx, it) }
                    detail = "battle"
                } else {
                    detail = "battle_transition(state=$procState)"
                }
            } else {
                mode = GameMode.APP
                detail = name
                app = runCatching { readApp(subApp, name, ptr(v.saveDataPtr)) }.getOrNull()
            }
        } else if (taskman != null) {
            val tasks = taskChain(taskman)
            val scriptEnv = tasks.firstNotNullOfOrNull { (_, env) -> env?.takeIf { u32(it + A.SE_CHECK) == A.SCRIPT_ENV_MAGIC } }
            val startMenuTask = tasks.firstOrNull { (func, _) -> func == v.fnTaskStartMenu }
            val followerTask = tasks.firstOrNull()?.first == v.fnTaskFollowMonInteract
            when {
                followerTask -> {
                    dialogue = readFollowerMessage(fs)
                    mode = if (dialogue.messageBoxOpen) GameMode.DIALOGUE else GameMode.SCRIPT
                    detail = "follower_" + dialogue.waitingFor
                }

                scriptEnv != null -> {
                    dialogue = readDialogue(scriptEnv)
                    mode = if (dialogue.messageBoxOpen) GameMode.DIALOGUE else GameMode.SCRIPT
                    detail = dialogue.waitingFor
                    if (dialogue.waitingFor == "yes_no" || dialogue.waitingFor == "multichoice") {
                        menu = runCatching { readScriptMenu(fs, scriptEnv, dialogue.waitingFor) }.getOrNull()
                    }
                }

                startMenuTask != null -> {
                    mode = GameMode.START_MENU
                    startMenu = startMenuTask.second?.let { readStartMenu(fs, it) }
                }

                else -> {
                    mode = GameMode.FIELD_BUSY
                    detail = when (tasks.firstOrNull()?.first) {
                        v.fnTaskWildEncounter -> "wild_encounter_start"
                        else -> "task=0x${tasks.firstOrNull()?.first?.toString(16)}"
                    }
                }
            }
        } else if (fieldMapApp != null && mapReady && !paused) {
            mode = GameMode.OVERWORLD
        } else {
            mode = GameMode.FIELD_BUSY
            detail = "map_not_ready"
        }

        val saveData = ptr(v.saveDataPtr) ?: ptr(fs + A.FS_SAVE_DATA)
        val player = saveData?.let { runCatching { readPlayer(it) }.getOrNull() }
        // In battle the game works on its own copy of the party (BattleSystem.trainerParty[0], same slot order as the
        // save, values updated live: HP, status, PP); the save's party is only written back when the battle ends.
        val battleParty = if (battle != null) battleSystem?.let { bs -> ptr(bs + A.BS_TRAINER_PARTY)?.let { runCatching { readPartyAt(ctx, it) }.getOrNull() } } else null
        val party = battleParty?.takeIf { it.isNotEmpty() }
            ?: saveData?.let { runCatching { readParty(ctx, it) }.getOrNull() } ?: emptyList()
        val bag = saveData?.let { runCatching { readBag(it) }.getOrNull() }
        val registered = saveData?.let { sd -> saveArray(sd, A.SAVE_BAG) }
            ?.let { b -> (0 until 2).map { u16(b + A.BAG_REGISTERED_ITEMS + 2L * it) } }.orEmpty()
        val story = saveData?.let { runCatching { readStory(it) }.getOrNull() }

        // The overworld structures (map loader, map objects) are only alive while the field map app runs.
        val fieldAlive = subApp == null && fieldMapApp != null
        val location = runCatching { readLocation(ctx, fs, fieldAlive) }.getOrNull()
        val surroundings = if (fieldAlive && mapReady) runCatching { readSurroundings(ctx, fs, saveData) }.getOrNull() else null

        val fading = isFading()
        val scenePending = mode == GameMode.OVERWORLD && sceneScriptPending(fs, saveData)
        val awaiting = !fading && when (mode) {
            GameMode.OVERWORLD -> playerControllable && location?.moving == false && !scenePending
            GameMode.DIALOGUE, GameMode.SCRIPT -> when (dialogue?.waitingFor) {
                "waiting_button" -> true
                "yes_no", "multichoice" -> menu?.waiting != false
                else -> false
            }
            GameMode.START_MENU -> startMenu?.waiting == true
            GameMode.APP -> app?.waiting ?: (subApp != null && s32(subApp + A.OM_EXEC_STATE) == 2)
            GameMode.BATTLE -> battle?.awaitingInput == true
            else -> false
        }
        return HgssState(
            frame = frame,
            mode = mode,
            modeDetail = detail,
            playerControllable = playerControllable,
            awaitingInput = awaiting,
            fading = fading,
            scenePending = scenePending,
            player = player,
            location = location,
            dialogue = dialogue,
            startMenu = startMenu,
            menu = menu,
            app = app,
            story = story,
            party = party,
            battle = battle,
            surroundings = surroundings,
            bag = bag,
            registeredItems = registered,
            warnings = ctx.warnings,
        )
    }

    /**
     * True when a map scene script is about to start: the game checks the map's "on frame" table (var == value)
     * before reading the player's input (src/fieldmap.c GetMapSceneScriptId, FieldInput_Process), e.g. Mom's
     * scene right after arriving on the first floor.
     */
    private fun sceneScriptPending(fs: Long, saveData: Long?): Boolean {
        if (u32(fs + A.FS_SCRIPTS_DISABLED) != 0L) return false
        val header = ptr(fs + A.FS_MAP_EVENTS)?.let { it + A.ME_SCRIPT_HEADER } ?: return false
        var p = header
        var table: Long? = null
        while (p < header + 0x100) {
            val type = u8(p)
            if (type == 0) break
            if (type == 1) {
                val ofs = u32(p + 1)
                if (ofs != 0L) table = p + 5 + ofs
                break
            }
            p += 5
        }
        var t = table ?: return false
        repeat(32) {
            val var1 = u16(t)
            if (var1 == 0) return false
            val a = varGet(saveData, var1)
            val b = varGet(saveData, u16(t + 2))
            if (a != null && a == b) return true
            t += 6
        }
        return false
    }

    /** A palette fade / wipe or a master-brightness transition is running (warps, apps opening, script fades). */
    private fun isFading(): Boolean =
        u16(v.paletteFadeActive) != 0 || u32(v.brightnessSubActive) != 0L || u32(v.brightnessMainActive) != 0L

    /** Walks TaskManager->prev from the top task. Returns (func, env) pairs, top first. */
    private fun taskChain(top: Long): List<Pair<Long, Long?>> {
        val out = mutableListOf<Pair<Long, Long?>>()
        var t: Long? = top
        val seen = HashSet<Long>()
        while (t != null && out.size < 16 && seen.add(t)) {
            out += fn(t + A.TM_FUNC) to ptr(t + A.TM_ENV)
            t = ptr(t + A.TM_PREV)
        }
        return out
    }

    // ================================================================================================
    // Dialogue / script / start menu
    // ================================================================================================

    private fun readDialogue(env: Long): DialogueInfo {
        val open = u8(env + A.SE_MSGBOX_OPEN) != 0
        val natives = (0 until 3).mapNotNull { i ->
            val sc = ptr(env + A.SE_SCRIPT_CONTEXTS + 4L * i) ?: return@mapNotNull null
            if (u8(sc + A.SC_MODE) == 2) fn(sc + A.SC_NATIVE) else null
        }
        val waiting = when {
            v.fnScrYesNo in natives || v.fnScrTouchYesNo in natives -> "yes_no"
            v.fnScrMenuWait1 in natives || v.fnScrMenuWait2 in natives || v.fnScrTouchMenu in natives -> "multichoice"
            natives.any {
                it == v.fnScrWaitABPress || it == v.fnScrWaitButton ||
                    it == v.fnScrWaitButtonOrDpad || it == v.fnScrWaitButtonOrDelay
            } -> "waiting_button"
            v.fnScrWaitTextPrint in natives -> "printing"
            v.fnScrWaitMovement in natives -> "waiting_movement"
            v.fnScrWaitApp in natives || v.fnScrWaitAppDestroy in natives -> "waiting_app"
            v.fnScrPauseTimer in natives -> "pause"
            natives.isNotEmpty() -> "native(0x${natives.first().toString(16)})"
            else -> "running"
        }
        val strPtr = if (open) ptr(env + A.SE_STRING_BUFFER_0) else null
        val text = readGameString(strPtr)
        // The field message printer: alive while printing or waiting at a page break (\r / \f) for A.
        val printer = ptr(v.textPrinterTasks + 4L * u8(env + A.SE_TEXT_PRINTER))?.let { ptr(it + A.SYSTASK_DATA) }
        val printerState = printer?.let { u8(it + A.TP_STATE) }
        val pageBreak = printerState != null && printerState in A.TEXT_PRINTER_WAIT_STATES
        var printedChars: Int? = null
        if (strPtr != null && printer != null && text != null) {
            val data = strPtr + A.STR_DATA
            val cur = u32(printer + A.TP_CURRENT_CHAR)
            val size = u16(strPtr + A.STR_SIZE)
            if (cur >= data && cur <= data + 2L * size) printedChars = ((cur - data) / 2).toInt()
        }
        // The printer's progress only matters while the script waits for it (after that the slot may be stale).
        val visible = if (strPtr != null && text != null) {
            val size = u16(strPtr + A.STR_SIZE).coerceAtMost(2048)
            HgssText.visibleLines(chars(strPtr + A.STR_DATA, size), if (printer != null && waiting == "printing") printedChars else null)
        } else null
        val waitingFor = when {
            pageBreak && waiting == "printing" -> "waiting_button"
            waiting == "printing" -> "printing"
            else -> waiting
        }
        return DialogueInfo(
            text = text,
            visibleText = visible,
            printing = waiting == "printing" && !pageBreak,
            messageBoxOpen = open,
            waitingFor = waitingFor,
            scriptId = u16(env + A.SE_ACTIVE_SCRIPT),
        )
    }

    /**
     * Talking to the following Pokémon: Task_FollowMonInteract (overlay 2) prints its own message, outside the script
     * environment. Its work (FieldSystem.unk120) holds the message String at +0x10 and a state byte at +0x869 that is 6
     * while the message box is up (verified live).
     */
    private fun readFollowerMessage(fs: Long): DialogueInfo {
        val work = ptr(fs + A.FS_FOLLOW_INTERACT)
        val shown = work != null && u8(work + A.FOLLOW_INTERACT_STATE) == A.FOLLOW_INTERACT_MESSAGE_SHOWN
        if (!shown) return DialogueInfo(messageBoxOpen = false, waitingFor = "running")
        val strPtr = ptr(work!! + A.FOLLOW_INTERACT_STRING)
        val text = readGameString(strPtr) ?: return DialogueInfo(messageBoxOpen = true, waitingFor = "waiting_button")
        val data = strPtr!! + A.STR_DATA
        val size = u16(strPtr + A.STR_SIZE)
        // The printer currently printing this string, if any.
        var printed: Int? = null
        var pageBreak = false
        for (i in 0 until 8) {
            val printer = ptr(v.textPrinterTasks + 4L * i)?.let { ptr(it + A.SYSTASK_DATA) } ?: continue
            val cur = u32(printer + A.TP_CURRENT_CHAR)
            if (cur >= data && cur <= data + 2L * size) {
                printed = ((cur - data) / 2).toInt()
                pageBreak = u8(printer + A.TP_STATE) in A.TEXT_PRINTER_WAIT_STATES
            }
        }
        val printing = printed != null && !pageBreak
        return DialogueInfo(
            text = text,
            visibleText = HgssText.visibleLines(chars(data, size.coerceAtMost(2048)), if (printing) printed else null),
            printing = printing,
            messageBoxOpen = true,
            waitingFor = if (printing) "printing" else "waiting_button",
        )
    }

    /**
     * The script menu waiting for a choice. Most of them are drawn on the touch screen by overlay 27
     * (ScrCmd_GetMenuChoice / ScrCmd_MenuExec): FieldSystem.unkD8 -> bottom-screen manager (app 3) -> touch menu
     * {state, FieldMenu*, cursor}. A few use the top screen: ScriptEnvironment.unk24 (ListMenu2D, yes/no) or
     * ScriptEnvironment.unk10 (FieldMenu with its ListMenu2D). Option labels are the expanded Strings of the FieldMenu.
     */
    private fun readScriptMenu(fs: Long, env: Long, kind: String): MenuInfo? {
        val natives = (0 until 3).mapNotNull { i ->
            val sc = ptr(env + A.SE_SCRIPT_CONTEXTS + 4L * i) ?: return@mapNotNull null
            if (u8(sc + A.SC_MODE) == 2) fn(sc + A.SC_NATIVE) else null
        }
        fun items(fieldMenu: Long, offset: Long): List<String> {
            val n = u8(fieldMenu + A.FMENU_COUNT)
            if (n !in 1..28) return emptyList()
            return (0 until n).map { i -> readGameString(ptr(fieldMenu + offset + i * A.LIST_MENU_ITEM_SIZE))?.replace('\n', ' ') ?: "?" }
        }
        if (v.fnScrTouchYesNo in natives || v.fnScrTouchMenu in natives) {
            val manager = ptr(fs + A.FS_BOTTOM_SCREEN_TASK)?.let { ptr(it + A.SYSTASK_DATA) } ?: return null
            if (u8(manager + A.BSM_APP_ID) != A.BOTTOM_APP_SCRIPT_MENU) return null
            val tm = ptr(manager + A.BSM_APP_TASK)?.let { ptr(it + A.SYSTASK_DATA) } ?: return null
            val state = s32(tm + A.TOUCH_MENU_STATE)
            val cursor = s32(tm + A.TOUCH_MENU_CURSOR)
            return if (v.fnScrTouchYesNo in natives) {
                MenuInfo("yes_no", listOf("YES", "NO"), cursor.takeIf { it in 0..1 }, waiting = state == A.TM_STATE_YES_NO_WAIT)
            } else {
                val labels = ptr(tm + A.TOUCH_MENU_FIELD_MENU)?.let { items(it, A.FMENU_ITEMS_TOUCH) } ?: emptyList()
                // 5..8 options: 2 columns, row by row; with an odd count the last one sits alone at the bottom right
                // (ov27_0225D3C4, verified on screen), so a "-" placeholder keeps row-major indices right.
                val grid = labels.size > 4
                val padded = grid && labels.size % 2 == 1
                val options = if (padded) labels.dropLast(1) + "-" + labels.last() else labels
                val index = cursor.takeIf { it in labels.indices }?.let { if (padded && it == labels.size - 1) it + 1 else it }
                MenuInfo("multichoice", options, index, columns = if (grid) 2 else 1, waiting = state == A.TM_STATE_MENU_WAIT)
            }
        }
        if (v.fnScrYesNo in natives) {
            val cursor = ptr(env + A.SE_LIST_MENU_2D)?.let { u8(it + A.LM2D_SELECTED) }
            return MenuInfo("yes_no", listOf("YES", "NO"), cursor?.takeIf { it in 0..1 }, screen = "top")
        }
        val fieldMenu = ptr(env + A.SE_FIELD_MENU) ?: return MenuInfo(kind, emptyList(), null, screen = "top")
        val options = items(fieldMenu, A.FMENU_ITEMS_TOP)
        val cursor = ptr(fieldMenu + A.FMENU_LIST_MENU)?.let { u8(it + A.LM2D_SELECTED) }
        return MenuInfo("multichoice", options, cursor?.takeIf { it in options.indices }, screen = "top")
    }

    /** What we know about a full-screen app: at least its name; entries/cursor/prompt for the ones we decode. */
    private fun readApp(om: Long, name: String, saveData: Long?): AppInfo {
        val running = s32(om + A.OM_EXEC_STATE) == 2
        val data = ptr(om + A.OM_DATA)
        return when (name) {
            "choose_starter" -> {
                val work = data ?: return AppInfo(name, "Starter selection")
                // D-pad order: RIGHT turns the machine 0 -> 2 -> 1 (Chikorita -> Totodile -> Cyndaquil).
                val order = listOf(0, 2, 1)
                val labels = listOf("CHIKORITA (Grass)", "CYNDAQUIL (Fire)", "TOTODILE (Water)")
                val sel = u32(work + A.CS_CUR_SELECTION).toInt().takeIf { it in 0..2 }
                val front = sel?.let { labels[it].substringBefore(' ') }
                val prompt = when (u32(work + A.CS_SELECT_STATE).toInt()) {
                    0 -> "Prof. Elm: pick a Poké Ball. A looks at the ball in front, LEFT/RIGHT turn the machine to the next ball."
                    1 -> "The ball in front holds $front. A chooses it (Elm then asks to confirm), LEFT/RIGHT turn to another ball."
                    2 -> "Prof. Elm asks: do you want $front? A = yes, take it; B = no, look again."
                    else -> null
                }
                AppInfo(
                    name, "Starter selection (Prof. Elm's machine)", order.map { labels[it] }, sel?.let { order.indexOf(it) },
                    layout = "horizontal", prompt = prompt, waiting = running && s32(om + A.OM_PROC_STATE) == A.CS_PROC_HANDLE_INPUT,
                )
            }
            "mailbox" -> {
                val slots = (0 until 10).map { i ->
                    val mail = saveData?.let { saveArray(it, A.SAVE_MAILBOX) }?.let { it + i * A.MAIL_SIZE }
                    if (mail == null || u8(mail + A.MAIL_TYPE) == 0xFF) "-"
                    else "mail from " + HgssText.decode(chars(mail + A.MAIL_AUTHOR, 8))
                }
                // CANCEL is the bottom-right button: a "-" placeholder keeps the 2-column row-major layout.
                val entries = slots + "-" + "CANCEL"
                val next = data?.let { ptr(it + A.MAILBOX_INNER) }?.let { ptr(it + A.MAILBOX_GRID_INPUT) }?.let { u8(it + A.GRID_INPUT_NEXT) }
                val cursor = when (next) {
                    in 0..9 -> next
                    10 -> 11
                    else -> null
                }
                AppInfo(
                    name, "PC Mailbox", entries, cursor, layout = "grid2",
                    prompt = "stored mail in 2 columns (\"-\" = empty slot): D-pad moves, A opens the selected mail's menu, B or CANCEL closes the Mailbox",
                    waiting = running && !isFading(),
                )
            }
            else -> AppInfo(name, name.replace('_', ' '), waiting = running)
        }
    }

    private fun readStartMenu(fs: Long, env: Long): StartMenuInfo? {
        val n = u32(env + A.SM_NUM_BUTTONS).toInt()
        if (n !in 1..10) return null
        // The last two entries are the fixed Pokégear touch buttons (actions 9 and 10), not grid icons.
        val actions = (0 until n).map { i -> u8(env + A.SM_SELECTION_TO_ACTION + i) }
        val grid = arrayOfNulls<String>(8)
        val gridIds = arrayOfNulls<String>(8)
        val slotOfIndex = mutableMapOf<Int, Int>()
        actions.forEachIndexed { i, action ->
            if (i >= n - 2) return@forEachIndexed
            val icon = A.START_MENU_ICON_OF_ACTION[action] ?: return@forEachIndexed
            val label = if (action == A.START_MENU_ACTION_TRAINER_CARD) "TRAINER CARD" else A.START_MENU_LABELS.getOrElse(action) { "?" }
            grid[icon] = label
            gridIds[icon] = A.START_MENU_IDS.getOrElse(action) { "action$action" }
            slotOfIndex[i] = icon
        }
        // Grid slot (icon index) -> row = icon % 4, column = icon / 4; list row by row, dropping empty rows.
        val rows = (0 until 4).filter { r -> grid[r] != null || grid[r + 4] != null }
        val items = rows.flatMap { r -> listOf(grid[r] ?: "-", grid[r + 4] ?: "-") }
        val ids = rows.flatMap { r -> listOf(gridIds[r], gridIds[r + 4]) }
        val cursorIndex = u8(fs + A.FS_START_MENU_CURSOR)
        val cursor = slotOfIndex[cursorIndex]?.let { icon -> rows.indexOf(icon % 4).takeIf { it >= 0 }?.let { it * 2 + icon / 4 } }
        return StartMenuInfo(
            items, cursor, waiting = u16(env + A.SM_STATE) == A.SM_STATE_HANDLE_INPUT, ids = ids,
            leftColumn = (0 until 4).mapNotNull { grid[it] }, rightColumn = (4 until 8).mapNotNull { grid[it] },
        )
    }

    // ================================================================================================
    // Save data: player, party, bag
    // ================================================================================================

    /** Address of save array [id] (SaveArray_Get, src/save.c:128), or null. */
    private fun saveArray(saveData: Long, id: Int): Long? {
        val hdr = saveData + A.SAVE_ARRAY_HEADERS + id * A.SAH_SIZE
        if (s32(hdr + A.SAH_ID) != id) return null
        val off = u32(hdr + A.SAH_OFFSET)
        val len = u32(hdr + A.SAH_LENGTH)
        val addr = saveData + A.SAVE_DYNAMIC_REGION + off
        return if (inRam(addr, len.coerceAtLeast(1))) addr else null
    }

    private val badgeNames = listOf(
        "Zephyr", "Hive", "Plain", "Fog", "Storm", "Mineral", "Glacier", "Rising",
        "Boulder", "Cascade", "Thunder", "Rainbow", "Soul", "Marsh", "Volcano", "Earth",
    )

    private fun readPlayer(saveData: Long): PlayerInfo? {
        val pd = saveArray(saveData, A.SAVE_PLAYERDATA) ?: return null
        val prof = pd + A.PD_PROFILE
        val johto = u8(prof + A.PP_JOHTO_BADGES)
        val kanto = u8(prof + A.PP_KANTO_BADGES)
        val badges = (0 until 8).filter { johto shr it and 1 == 1 }.map { badgeNames[it] } +
            (0 until 8).filter { kanto shr it and 1 == 1 }.map { badgeNames[8 + it] }
        return PlayerInfo(
            name = HgssText.decode(chars(prof + A.PP_NAME, 8)),
            gender = if (u8(prof + A.PP_GENDER) == 0) "male" else "female",
            trainerId = u32(prof + A.PP_ID) and 0xFFFF,
            money = u32(prof + A.PP_MONEY),
            coins = u16(pd + A.PD_COINS),
            badges = badges,
            badgeCount = badges.size,
        )
    }

    private fun moveInfo(id: Int, pp: Int, maxPp: Int): MoveInfo {
        val d = HgssData.moveData[id]
        return MoveInfo(id, HgssData.moveName(id), pp, maxPp, d?.type, d?.category, d?.power, d?.accuracy)
    }

    private fun readParty(ctx: Ctx, saveData: Long): List<PartyMon> {
        val party = saveArray(saveData, A.SAVE_PARTY) ?: return emptyList()
        return readPartyAt(ctx, party)
    }

    /** Reads a `Party` struct (the save party, or a battle party copy). */
    private fun readPartyAt(ctx: Ctx, party: Long): List<PartyMon> {
        val count = s32(party + A.PARTY_CUR_COUNT)
        if (count !in 0..6) {
            ctx.warnings += "party count $count out of range"
            return emptyList()
        }
        return (0 until count).mapNotNull { i ->
            val raw = bytes(party + A.PARTY_MONS + i * A.POKEMON_SIZE, A.POKEMON_SIZE.toInt()) ?: return@mapNotNull null
            val mon = HgssPokemon.decode(raw) ?: return@mapNotNull null
            val mon2 = toPartyMon(i, mon)
            if (!mon.checksumOk) ctx.warnings += "party slot $i: checksum mismatch"
            if (!mon2.plausible) ctx.warnings += "party slot $i: implausible values (species ${mon2.species}, level ${mon2.level}, hp ${mon2.hp}/${mon2.maxHp})"
            mon2
        }
    }

    private fun toPartyMon(slot: Int, mon: HgssPokemon.Decoded): PartyMon {
        val species = mon.species
        val moves = (0 until 4).mapNotNull { m ->
            val id = mon.move(m)
            if (id == 0) null else moveInfo(id, mon.movePp(m), HgssPokemon.maxPp(id, mon.movePpUps(m)))
        }
        val nickname = HgssText.decode(mon.nicknameChars)
        return PartyMon(
            slot = slot,
            personality = mon.personality,
            otId = mon.otId,
            heldItemId = mon.heldItem,
            statusRaw = mon.status,
            species = species,
            speciesName = HgssData.speciesName(species),
            nickname = nickname.takeIf { it.isNotEmpty() },
            level = mon.level,
            hp = mon.hp,
            maxHp = mon.maxHp,
            status = if (mon.isEgg) "EGG" else HgssPokemon.statusName(mon.status, mon.hp),
            types = HgssData.speciesTypes(species),
            heldItem = mon.heldItem.takeIf { it != 0 }?.let { HgssData.itemName(it) },
            ability = mon.ability.takeIf { it != 0 }?.let { HgssData.abilityName(it) },
            isEgg = mon.isEgg,
            moves = moves,
            stats = mapOf("atk" to mon.atk, "def" to mon.def, "speed" to mon.speed, "spAtk" to mon.spAtk, "spDef" to mon.spDef),
            exp = mon.exp,
            friendship = mon.friendship,
            checksumOk = mon.checksumOk,
        )
    }

    private fun readBag(saveData: Long): List<BagPocket>? {
        val bag = saveArray(saveData, A.SAVE_BAG) ?: return null
        return A.BAG_POCKETS.map { (name, off, n) ->
            val items = (0 until n).mapNotNull { i ->
                val id = u16(bag + off + 4L * i)
                val qty = u16(bag + off + 4L * i + 2)
                if (id == 0 || qty == 0) null else BagItem(id, HgssData.itemName(id), qty)
            }
            BagPocket(name, items)
        }
    }

    /**
     * Story flags/vars listed in [HgssProgress] and needed by [HgssStoryTable] (its steps and curated blockers)
     * (SaveVarsFlags, src/save_vars_flags.c), with the badges (PlayerProfile).
     */
    private fun readStory(saveData: Long): StoryInfo? {
        val vf = saveArray(saveData, A.SAVE_FLAGS) ?: return null
        val flags = STORY_FLAGS.filter { id -> u8(vf + A.FLAGS_OFFSET + id / 8) shr (id % 8) and 1 == 1 }.toSet()
        val vars = STORY_VARS.associateWith { id -> u16(vf + 2L * (id - A.VAR_BASE)) }
        val shoes = saveArray(saveData, A.SAVE_LOCAL_FIELD_DATA)?.let { u16(it + A.LFD_RUNNING_SHOES) != 0 } ?: false
        val dex = saveArray(saveData, A.SAVE_POKEDEX)?.let { u8(it + A.POKEDEX_ENABLED) != 0 } ?: false
        val badges = saveArray(saveData, A.SAVE_PLAYERDATA)?.let { pd ->
            val prof = pd + A.PD_PROFILE
            val johto = u8(prof + A.PP_JOHTO_BADGES)
            val kanto = u8(prof + A.PP_KANTO_BADGES)
            (0 until 8).filter { johto shr it and 1 == 1 }.toSet() + (0 until 8).filter { kanto shr it and 1 == 1 }.map { it + 8 }
        } ?: emptySet()
        return StoryInfo(flags, vars, shoes, dex, badges)
    }

    /** The value of script variable [varId] right now (save vars; ids below 0x4000 are literal values), or null. */
    fun variable(varId: Int): Int? = try {
        resolveVersion().first?.let { resolved ->
            v = resolved
            varGet(ptr(v.saveDataPtr), varId)
        }
    } catch (_: Exception) {
        null
    }

    /** Event flag [flagId] right now (save flags, SaveVarsFlags.flags, src/save_vars_flags.c), or null. */
    fun flag(flagId: Int): Boolean? = try {
        resolveVersion().first?.let { resolved ->
            v = resolved
            if (flagId !in 0 until A.NUM_SAVE_FLAGS) return@let null
            ptr(v.saveDataPtr)?.let { sd -> saveArray(sd, A.SAVE_FLAGS) }?.let { vf -> u8(vf + A.FLAGS_OFFSET + flagId / 8) shr (flagId % 8) and 1 == 1 }
        }
    } catch (_: Exception) {
        null
    }

    /**
     * The gym puzzle slot of the save (`Gymmick`, include/gymmick.h, in SAVE_MISC): u32 type then 0x20 bytes of
     * data, set up when entering a puzzle gym and cleared on map change (src/field_warp_tasks.c:185). Null when
     * unreadable.
     */
    fun gymmick(): ByteArray? = try {
        resolveVersion().first?.let { resolved ->
            v = resolved
            ptr(v.saveDataPtr)?.let { sd -> saveArray(sd, A.SAVE_MISC) }?.let { misc -> bytes(misc + A.MISC_GYMMICK, A.GYMMICK_SIZE) }
        }
    } catch (_: Exception) {
        null
    }

    /** Script var value (FieldSystem_VarGet, src/fieldmap.c:365) — only save vars are supported. */
    private fun varGet(saveData: Long?, varId: Int): Int? = when {
        varId < A.VAR_BASE -> varId
        varId < A.VAR_BASE + A.NUM_VARS -> saveData?.let { sd -> saveArray(sd, A.SAVE_FLAGS)?.let { u16(it + 2L * (varId - A.VAR_BASE)) } }
        else -> null
    }

    // ================================================================================================
    // Location / player avatar
    // ================================================================================================

    private fun playerMapObject(fs: Long): Long? = ptr(fs + A.FS_PLAYER_AVATAR)?.let { ptr(it + A.PA_MAP_OBJECT) }

    private fun readLocation(ctx: Ctx, fs: Long, fieldAlive: Boolean): LocationInfo? {
        val loc = ptr(fs + A.FS_LOCATION) ?: return null
        val mapId = s32(loc + A.LOC_MAP_ID)
        if (mapId !in 0 until 1000) {
            ctx.warnings += "map id $mapId out of range"
            return null
        }
        val avatar = ptr(fs + A.FS_PLAYER_AVATAR)
        val mo = avatar?.let { ptr(it + A.PA_MAP_OBJECT) }
        var x = s32(loc + A.LOC_X)
        var z = s32(loc + A.LOC_Z)
        var height = 0
        var facing = s32(loc + A.LOC_DIRECTION)
        var moving = false
        if (mo != null && fieldAlive) {
            x = s32(mo + A.MO_X)
            z = s32(mo + A.MO_Z)
            height = s32(mo + A.MO_Y)
            facing = s32(mo + A.MO_FACING)
            // At rest the position vector is exactly the tile center (src/map_object.c:525).
            val px = s32(mo + A.MO_POSITION_VECTOR)
            val pz = s32(mo + A.MO_POSITION_VECTOR + 8)
            // (MapObject flags 0x10/0x20 are useless here: the standing "movement" restarts every frame when idle.)
            moving = px != x * 16 * 4096 + 8 * 4096 || pz != z * 16 * 4096 + 8 * 4096 ||
                s32(mo + A.MO_PREVIOUS_X) != x || s32(mo + A.MO_PREVIOUS_Z) != z
        }
        val state = avatar?.let { s32(it + A.PA_STATE) }
        val shoes = avatar?.let { ptr(it + A.PA_PLAYER_SAVE_DATA) }?.let { u16(it) != 0 }

        var standing: String? = null
        var facingTile: String? = null
        var facingBlocked: Boolean? = null
        if (fieldAlive && u32(fs + A.FS_MAP_READY) != 0L) {
            val tiles = TileReader(fs)
            tiles.attr(x, z)?.let { standing = HgssData.tileBehaviorNames.getOrNull(it and 0xFF) }
            val (fx, fz) = when (facing) {
                0 -> x to z - 1
                1 -> x to z + 1
                2 -> x - 1 to z
                else -> x + 1 to z
            }
            tiles.attr(fx, fz)?.let {
                facingTile = HgssData.tileBehaviorNames.getOrNull(it and 0xFF)
                facingBlocked = it and A.TILE_COLLISION_BIT != 0
            }
        }
        return LocationInfo(
            mapId = mapId,
            mapName = HgssData.mapName(mapId),
            locationName = HgssData.mapLocation(mapId),
            x = x,
            z = z,
            height = height,
            facing = A.DIRECTIONS.getOrElse(facing) { "dir$facing" },
            moving = moving,
            avatarState = state?.let { A.PLAYER_STATES.getOrElse(it) { "STATE_$it" } } ?: "UNKNOWN",
            hasRunningShoes = shoes,
            standingOn = standing,
            facingTile = facingTile,
            facingTileBlocked = facingBlocked,
        )
    }

    // ================================================================================================
    // Tiles (collision + behavior)
    // ================================================================================================

    /**
     * Resolves the u16 terrain attribute of a global tile, the same way the game does
     * (GetMetatileBehavior -> FieldSystem.unk60 accessor, asm/unk_02054648.s).
     */
    private inner class TileReader(fs: Long) {
        private val accessor = u32(fs + A.FS_TERRAIN_ACCESSOR)
        private val loader = ptr(fs + A.FS_MAP_LOADER)
        private val terrain = ptr(fs + A.FS_TERRAIN_ATTRIBUTES, 2)
        private val matrix = ptr(fs + A.FS_MAP_MATRIX, 2)

        val width: Int
        val height: Int
        private val slots = LongArray(A.ML_SLOT_COUNT)
        private val slotBlocks = IntArray(A.ML_SLOT_COUNT) { -1 }

        init {
            var w = 0
            var h = 0
            if (accessor == v.terrainAccessorLoader && loader != null) {
                w = s32(loader + A.ML_MATRIX_WIDTH)
                h = s32(loader + A.ML_MATRIX_HEIGHT)
                for (i in 0 until A.ML_SLOT_COUNT) {
                    val buf = ptr(loader + A.ML_BLOCK_BUFFERS + 4L * i) ?: continue
                    if (!inRam(buf, A.ML_BUFFER_BLOCK_INDEX + 4)) continue
                    slots[i] = buf
                    slotBlocks[i] = s32(buf + A.ML_BUFFER_BLOCK_INDEX)
                }
            } else if (accessor == v.terrainAccessorTerrainAttributes && matrix != null) {
                w = u8(matrix + A.MM_WIDTH)
                h = u8(matrix + A.MM_HEIGHT)
            }
            if (w !in 1..255 || h !in 1..255) {
                w = 0; h = 0
            }
            width = w
            height = h
        }

        /** Returns the attribute (bit15 collision, low byte behavior) or null if unknown / not loaded. */
        fun attr(x: Int, z: Int): Int? {
            if (x < 0 || z < 0 || width == 0) return null
            val bx = x / A.BLOCK_TILES
            val bz = z / A.BLOCK_TILES
            if (bx >= width || bz >= height) return null
            val block = bz * width + bx
            val tile = (z % A.BLOCK_TILES) * A.BLOCK_TILES + (x % A.BLOCK_TILES)
            if (accessor == v.terrainAccessorLoader) {
                for (i in 0 until A.ML_SLOT_COUNT) {
                    if (slots[i] != 0L && slotBlocks[i] == block) return u16(slots[i] + 2L * tile)
                }
                return null
            }
            val ta = terrain ?: return null
            if (block >= A.TA_MAX_MATRIX) return null
            val idx = u8(ta + block)
            if (idx >= A.TA_MAX_BLOCKS) return null
            return u16(ta + A.TA_ATTRS + 2L * (idx * 1024 + tile))
        }
    }

    // ================================================================================================
    // Surroundings: map objects, events, grid
    // ================================================================================================

    private fun readSurroundings(ctx: Ctx, fs: Long, saveData: Long?): Surroundings? {
        val playerMo = playerMapObject(fs) ?: return null
        val px = s32(playerMo + A.MO_X)
        val pz = s32(playerMo + A.MO_Z)
        val mapId = ptr(fs + A.FS_LOCATION)?.let { s32(it + A.LOC_MAP_ID) } ?: -1
        val mapType = HgssData.mapType(mapId)
        val interior = mapType == "INTERIOR"
        val tiles = TileReader(fs)
        val objects = readMapObjects(fs, playerMo, px, pz)
        val (warps, bgs, triggers) = readEvents(ctx, fs, saveData, px, pz, mapId, tiles, interior)
        val grid = buildArea(tiles, px, pz, interior)
        // Furniture the player can examine (PC, TV, bookshelves...) that has no BG event of its own.
        val examinables = grid?.let { g ->
            (0 until g.height).flatMap { r ->
                (0 until g.width).mapNotNull { c ->
                    val x = g.originX + c
                    val z = g.originZ + r
                    if (g.rows[r][c] == '-' || bgs.any { it.x == x && it.z == z }) return@mapNotNull null
                    val attr = tiles.attr(x, z) ?: return@mapNotNull null
                    val label = HgssLabels.examinableBehavior(HgssData.tileBehaviorNames.getOrNull(attr and 0xFF)) ?: return@mapNotNull null
                    // Only furniture the player can stand next to.
                    if (listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1).none { (dx, dz) -> g.at(x + dx, z + dz) in "._\"" }) return@mapNotNull null
                    BgEventInfo(x, z, x - px, z - pz, "tile", 0, label, attr and A.TILE_COLLISION_BIT != 0)
                }
            }
        } ?: emptyList()
        return Surroundings(
            matrixWidth = tiles.width.takeIf { it > 0 },
            matrixHeight = tiles.height.takeIf { it > 0 },
            mapType = mapType,
            grid = grid,
            objects = objects,
            warps = warps,
            bgEvents = bgs + examinables,
            triggers = triggers,
            neighbors = if (interior) emptyList() else readNeighbors(fs, mapId, px, pz),
        )
    }

    private fun readMapObjects(fs: Long, playerMo: Long, px: Int, pz: Int): List<MapObjectInfo> {
        val mom = ptr(fs + A.FS_MAP_OBJECT_MANAGER) ?: return emptyList()
        val count = u32(mom + A.MOM_OBJECT_COUNT).toInt()
        val objs = ptr(mom + A.MOM_OBJECTS) ?: return emptyList()
        if (count !in 1..128 || !inRam(objs, count * A.MO_SIZE)) return emptyList()
        val follower = ptr(fs + A.FS_FOLLOW_MON + A.FOLLOW_MON_MAP_OBJECT)
        val out = mutableListOf<MapObjectInfo>()
        for (i in 0 until count) {
            val o = objs + i * A.MO_SIZE
            if (o == playerMo) continue
            val flags = u32(o + A.MO_FLAGS)
            if (flags and A.MO_FLAG_ACTIVE == 0L) continue
            val sprite = s32(o + A.MO_SPRITE_ID)
            if (sprite == A.SPRITE_CAMERA_FOCUS) continue
            val spriteName = HgssData.spriteName(sprite)
            val x = s32(o + A.MO_X)
            val z = s32(o + A.MO_Z)
            val kind = when {
                o == follower -> "follower"
                spriteName == "MONSTARBALL" -> "item_ball"
                spriteName in OBSTACLE_SPRITES -> "obstacle"
                else -> "npc"
            }
            out += MapObjectInfo(
                id = s32(o + A.MO_ID),
                sprite = spriteName,
                x = x,
                z = z,
                dx = x - px,
                dz = z - pz,
                height = s32(o + A.MO_Y),
                facing = A.DIRECTIONS.getOrElse(s32(o + A.MO_FACING)) { "?" },
                movement = s32(o + A.MO_MOVEMENT),
                type = s32(o + A.MO_TYPE),
                scriptId = s32(o + A.MO_SCRIPT_ID),
                hidden = flags and A.MO_FLAG_HIDDEN != 0L,
                kind = kind,
                label = if (kind == "follower") "your Pokémon (following you)" else HgssLabels.person(spriteName),
                mapId = s32(o + A.MO_MAP_ID),
                eventFlag = s32(o + A.MO_EVENT_FLAG),
            )
        }
        return out.sortedBy { Math.abs(it.dx) + Math.abs(it.dz) }
    }

    private fun readEvents(
        ctx: Ctx, fs: Long, saveData: Long?, px: Int, pz: Int, mapId: Int, tiles: TileReader, interior: Boolean,
    ): Triple<List<WarpInfo>, List<BgEventInfo>, List<TriggerInfo>> {
        val me = ptr(fs + A.FS_MAP_EVENTS) ?: return Triple(emptyList(), emptyList(), emptyList())
        fun count(off: Long) = u32(me + off).toInt().takeIf { it in 0..256 } ?: 0.also { ctx.warnings += "bad event count" }
        fun behavior(x: Int, z: Int) = tiles.attr(x, z)?.let { HgssData.tileBehaviorNames.getOrNull(it and 0xFF) }

        val warps = ptr(me + A.ME_WARP, 2)?.let { base ->
            (0 until count(A.ME_NUM_WARP)).map { i ->
                val w = base + i * A.WARP_SIZE
                val x = u16(w + A.WARP_X)
                val z = u16(w + A.WARP_Z)
                val dest = u16(w + A.WARP_DEST_MAP)
                val kind = HgssLabels.exitKind(behavior(x, z), interior)
                WarpInfo(
                    i, x, z, x - px, z - pz, dest, HgssData.mapName(dest), HgssData.mapLocation(dest), u16(w + A.WARP_DEST_WARP),
                    kind = kind.name, pressDirection = kind.pressDirection,
                )
            }
        } ?: emptyList()

        val bgs = ptr(me + A.ME_BG, 2)?.let { base ->
            (0 until count(A.ME_NUM_BG)).map { i ->
                val b = base + i * A.BG_SIZE
                val x = s32(b + A.BG_X)
                val z = s32(b + A.BG_Z)
                val type = when (u16(b + A.BG_TYPE)) {
                    0 -> "normal"
                    1 -> "sign"
                    2 -> "hidden_item"
                    else -> "type${u16(b + A.BG_TYPE)}"
                }
                val attr = tiles.attr(x, z)
                val label = when (type) {
                    "sign" -> "sign"
                    "hidden_item" -> "hidden item"
                    else -> HgssLabels.bgLabel(mapId, x, z) ?: HgssLabels.examinableBehavior(behavior(x, z)) ?: "something to examine"
                }
                BgEventInfo(x, z, x - px, z - pz, type, u16(b + A.BG_SCRIPT), label, blocked = attr != null && attr and A.TILE_COLLISION_BIT != 0)
            }
        } ?: emptyList()

        val triggers = ptr(me + A.ME_COORD, 2)?.let { base ->
            (0 until count(A.ME_NUM_COORD)).map { i ->
                val c = base + i * A.COORD_SIZE
                val variable = u16(c + A.COORD_VAR)
                val value = u16(c + A.COORD_VAL)
                val varValue = varGet(saveData, variable)
                TriggerInfo(
                    index = i,
                    x = s16(c + A.COORD_X), z = s16(c + A.COORD_Z),
                    width = u16(c + A.COORD_W), height = u16(c + A.COORD_H),
                    scriptId = u16(c + A.COORD_SCRIPT),
                    active = varValue?.let { it == value },
                    variable = variable,
                    value = value,
                )
            }
        } ?: emptyList()
        return Triple(warps, bgs, triggers)
    }

    /**
     * Other maps of the same matrix next to the player's (e.g. Route 29 west of New Bark Town): for each direction,
     * the first block within 2 blocks whose map id differs (MAPMATRIX.headers, include/map_matrix.h).
     */
    private fun readNeighbors(fs: Long, mapId: Int, px: Int, pz: Int): List<NeighborArea> {
        val matrix = ptr(fs + A.FS_MAP_MATRIX, 2) ?: return emptyList()
        val w = u8(matrix + A.MM_WIDTH)
        val h = u8(matrix + A.MM_HEIGHT)
        if (w !in 1..255 || h !in 1..255 || w * h <= 1) return emptyList()
        fun header(bx: Int, bz: Int): Int? =
            if (bx in 0 until w && bz in 0 until h) u16(matrix + A.MM_HEADERS + 2L * (bz * w + bx)) else null
        val bx = px / A.BLOCK_TILES
        val bz = pz / A.BLOCK_TILES
        val out = mutableListOf<NeighborArea>()
        for ((name, d) in listOf("north" to (0 to -1), "south" to (0 to 1), "west" to (-1 to 0), "east" to (1 to 0))) {
            for (step in 1..2) {
                val nx = bx + d.first * step
                val nz = bz + d.second * step
                val id = header(nx, nz) ?: break
                if (id == mapId) continue
                if (id !in 1 until 1000 || HgssData.mapName(id).startsWith("Everywhere")) break
                val boundary = when (name) {
                    "north" -> (nz + 1) * A.BLOCK_TILES - 1
                    "south" -> nz * A.BLOCK_TILES
                    "west" -> (nx + 1) * A.BLOCK_TILES - 1
                    else -> nx * A.BLOCK_TILES
                }
                out += NeighborArea(name, id, HgssData.mapLocation(id) ?: HgssData.mapName(id), boundary)
                break
            }
        }
        return out
    }

    private companion object {
        /** Half size of the area read around the player (the loaded blocks never reach further). */
        const val AREA_HALF_SIZE = 32

        /** Map objects that are things, not people: Cut trees, Rock Smash rocks, boulders. */
        val OBSTACLE_SPRITES = setOf("TREE", "ROCK", "BREAKROCK")

        /** Every story flag read into [StoryInfo]: the early-game ones and those of the story table. */
        val STORY_FLAGS: List<Int> by lazy { (HgssProgress.FLAGS + HgssStoryTable.flagIds).distinct().sorted() }

        /** Every story var read into [StoryInfo]. */
        val STORY_VARS: List<Int> by lazy { (HgssProgress.VARS + HgssStoryTable.varIds).distinct().sorted() }
    }

    private val bJumpEast by lazy { HgssData.behaviorId("JUMP_EAST") }
    private val bJumpWest by lazy { HgssData.behaviorId("JUMP_WEST") }
    private val bJumpNorth by lazy { HgssData.behaviorId("JUMP_NORTH") }
    private val bJumpSouth by lazy { HgssData.behaviorId("JUMP_SOUTH") }
    private val grassBehaviors by lazy {
        HgssData.tileBehaviorNames.withIndex().filter { (i, name) ->
            HgssData.tileBehaviorFlags[i] and 2 != 0 && "CAVE" !in name
        }.map { it.index }.toSet()
    }

    private fun terrainChar(attr: Int): Char {
        if (attr < 0) return '-'
        val b = attr and 0xFF
        return when {
            b == bJumpSouth -> '_'
            b == bJumpNorth -> '='
            b == bJumpWest -> '{'
            b == bJumpEast -> '}'
            HgssData.tileBehaviorFlags[b] and 1 != 0 -> '~'
            attr and A.TILE_COLLISION_BIT != 0 -> '#'
            b in grassBehaviors -> '"'
            else -> '.'
        }
    }

    /**
     * The terrain around the player, cropped to what is real.
     *
     * Rooms are 32x32 blocks where only the room itself is used: the walls have the collision bit and everything
     * beyond them is plain 0x0000, like a walkable floor. So the area reachable from the player (flood fill through
     * tiles without collision, ignoring people) is computed over the loaded blocks; indoors, tiles that are neither
     * reachable nor next to a reachable tile are outside the room ('-'). Tiles outside the matrix or not loaded are
     * '-' too. The result is the window of [halfWidth]/[halfHeight] tiles around the player (by default the whole area
     * the game keeps loaded: the 2x2 blocks of 32x32 tiles around the player), cropped to the bounding box of the real
     * tiles.
     */
    private fun buildArea(tiles: TileReader, px: Int, pz: Int, interior: Boolean, halfWidth: Int = AREA_HALF_SIZE, halfHeight: Int = AREA_HALF_SIZE): LocalGrid? {
        if (tiles.width == 0) return null
        val mw = tiles.width * A.BLOCK_TILES
        val mh = tiles.height * A.BLOCK_TILES
        val reach = A.BLOCK_TILES
        val x0 = maxOf(0, px - reach)
        val x1 = minOf(mw - 1, px + reach)
        val z0 = maxOf(0, pz - reach)
        val z1 = minOf(mh - 1, pz + reach)
        if (x1 < x0 || z1 < z0 || px !in x0..x1 || pz !in z0..z1) return null
        val w = x1 - x0 + 1
        val h = z1 - z0 + 1
        val attrs = IntArray(w * h) { i -> tiles.attr(x0 + i % w, z0 + i / w) ?: -1 }
        fun passable(i: Int) = attrs[i] >= 0 && attrs[i] and A.TILE_COLLISION_BIT == 0

        val reached = BooleanArray(w * h)
        val queue = ArrayDeque<Int>()
        fun seed(i: Int, force: Boolean = false) {
            if (!reached[i] && (force || passable(i))) {
                reached[i] = true
                queue.addLast(i)
            }
        }
        seed((pz - z0) * w + (px - x0), force = true)
        // Areas that continue beyond what we read (matrix continues past our window, or blocks not loaded).
        if (!interior) for (i in 0 until w * h) {
            val x = x0 + i % w
            val z = z0 + i / w
            val open = (x == x0 && x > 0) || (x == x1 && x < mw - 1) || (z == z0 && z > 0) || (z == z1 && z < mh - 1) ||
                listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1).any { (dx, dz) ->
                    val nx = x + dx - x0
                    val nz = z + dz - z0
                    nx in 0 until w && nz in 0 until h && attrs[nz * w + nx] < 0
                }
            if (open) seed(i)
        }
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            val x = i % w
            val z = i / w
            if (x > 0) seed(i - 1)
            if (x < w - 1) seed(i + 1)
            if (z > 0) seed(i - w)
            if (z < h - 1) seed(i + w)
        }
        fun real(x: Int, z: Int): Boolean {
            val i = z * w + x
            if (attrs[i] < 0) return false
            if (!interior) return true
            for (dz in -1..1) for (dx in -1..1) {
                val nx = x + dx
                val nz = z + dz
                if (nx in 0 until w && nz in 0 until h && reached[nz * w + nx]) return true
            }
            return false
        }

        // Window around the player, cropped to the real tiles.
        val wx0 = maxOf(x0, px - halfWidth) - x0
        val wx1 = minOf(x1, px + halfWidth) - x0
        val wz0 = maxOf(z0, pz - halfHeight) - z0
        val wz1 = minOf(z1, pz + halfHeight) - z0
        var cx0 = Int.MAX_VALUE
        var cx1 = Int.MIN_VALUE
        var cz0 = Int.MAX_VALUE
        var cz1 = Int.MIN_VALUE
        val isReal = Array(wz1 - wz0 + 1) { r -> BooleanArray(wx1 - wx0 + 1) { c -> real(wx0 + c, wz0 + r) } }
        for (r in isReal.indices) for (c in isReal[r].indices) if (isReal[r][c]) {
            cx0 = minOf(cx0, c); cx1 = maxOf(cx1, c); cz0 = minOf(cz0, r); cz1 = maxOf(cz1, r)
        }
        if (cx0 > cx1) return null
        val rows = (cz0..cz1).map { r ->
            buildString {
                for (c in cx0..cx1) append(if (isReal[r][c]) terrainChar(attrs[(wz0 + r) * w + wx0 + c]) else '-')
            }
        }
        return LocalGrid(x0 + wx0 + cx0, z0 + wz0 + cz0, cx1 - cx0 + 1, cz1 - cz0 + 1, rows)
    }

    // ================================================================================================
    // Battle
    // ================================================================================================

    private fun readBattle(ctx: Ctx, bs: Long): BattleInfo? {
        val battleCtx = ptr(bs + A.BS_CTX) ?: return null
        val type = u32(bs + A.BS_BATTLE_TYPE)
        val maxBattlers = s32(bs + A.BS_MAX_BATTLERS)
        if (maxBattlers !in 2..4) {
            ctx.warnings += "battle: maxBattlers=$maxBattlers"
            return null
        }
        if (!inRam(battleCtx, A.BC_BATTLE_MONS + 4 * A.BM_SIZE)) return null
        val flags = A.BATTLE_TYPE_FLAGS.filter { (bit, _) -> type shr bit and 1L == 1L }.map { it.second }
        val battlers = (0 until maxBattlers).map { id -> readBattler(battleCtx, id) }
        val trainers = if (type and 1L != 0L) {
            (0 until maxBattlers).filter { it % 2 == 1 }.mapNotNull { id ->
                val tid = u16(bs + A.BS_TRAINER_ID + 2L * id)
                if (tid == 0) return@mapNotNull null
                val t = bs + A.BS_TRAINERS + id * A.TRAINER_SIZE
                TrainerInfo(id, tid, HgssData.trainerClassName(u8(t + A.TRAINER_CLASS)), HgssText.decode(chars(t + A.TRAINER_NAME, 8)))
            }.distinctBy { it.trainerId }
        } else emptyList()
        val input = ptr(bs + A.BS_BATTLE_INPUT)
        val menuId = input?.let { s8(it + A.BI_CUR_MENU_ID) }
        val menu = menuId?.let { A.BATTLE_MENUS[it] }
        val cursor = input?.let {
            if (u8(it + A.BI_MENU_CURSOR) != 0) listOf(s8(it + A.BI_MENU_CURSOR + 1), s8(it + A.BI_MENU_CURSOR + 2)) else null
        }
        // battle_input.c / the player's battle controller: the menu is up, not sliding in, no button animation,
        // and (for the command/move/target menus) the engine is in the selection phase waiting for battler 0.
        // The battler's selection state must match the menu shown (1 command, 4 move, 6 target): right after FIGHT is
        // chosen the battler is already choosing a move while the main menu is still displayed.
        val battlerState = u8(battleCtx + A.BC_BATTLER_STATE)
        val expectedState = when (menuId) {
            in 1..10 -> 1
            11 -> 4
            12 -> 6
            else -> null
        }
        // Bag (8) / party screen (10) opened from the battle menu: their own screens handle the input.
        val subScreen = if (s32(battleCtx + A.BC_COMMAND) == A.BC_COMMAND_SELECTION) when (battlerState) {
            8 -> "BAG_SCREEN"
            10 -> "PARTY_SCREEN"
            else -> null
        } else null
        val awaiting = subScreen != null || input != null && menuId != null && menuId in 1..17 &&
            u8(input + A.BI_TOUCH_DISABLED) == 0 && u32(input + A.BI_FEEDBACK_TASK) == 0L && u32(input + A.BI_UNK10_TASK) == 0L &&
            (menuId >= 13 || (s32(battleCtx + A.BC_COMMAND) == A.BC_COMMAND_SELECTION && battlerState == expectedState))
        return BattleInfo(
            isWild = type and 1L == 0L,
            battleTypeFlags = flags,
            isDoubles = type and 2L != 0L,
            turn = s32(battleCtx + A.BC_TOTAL_TURNS).takeIf { it in 0..10000 },
            partyOrder = (0 until 6).map { u8(battleCtx + A.BC_PARTY_ORDER + it) }.takeIf { order -> order.sorted() == (0 until 6).toList() } ?: emptyList(),
            player = battlers.filter { it.side == "player" && it.species != 0 },
            opponents = battlers.filter { it.side == "opponent" && it.species != 0 },
            trainers = trainers,
            menu = subScreen ?: menu,
            menuCursor = if (subScreen != null) null else cursor,
            awaitingInput = awaiting,
            message = readGameString(ptr(bs + A.BS_MSG_BUFFER)),
            safariBalls = if (type and (1L shl 5) != 0L) s32(bs + A.BS_SAFARI_BALLS) else null,
        )
    }

    private fun readBattler(battleCtx: Long, id: Int): Battler {
        val m = battleCtx + A.BC_BATTLE_MONS + id * A.BM_SIZE
        val species = u16(m + A.BM_SPECIES)
        val moves = (0 until 4).mapNotNull { i ->
            val mv = u16(m + A.BM_MOVES + 2L * i)
            // BattleMon.movePP holds the PP Ups (src/battle/overlay_12_0224E4FC.c), not the max PP.
            if (mv == 0) null else moveInfo(mv, u8(m + A.BM_PP_CUR + i), HgssPokemon.maxPp(mv, u8(m + A.BM_PP_MAX + i)))
        }
        val t1 = u8(m + A.BM_TYPE1)
        val t2 = u8(m + A.BM_TYPE2)
        val stageNames = listOf("hp", "atk", "def", "speed", "spAtk", "spDef", "accuracy", "evasion")
        val stages = (1 until 8).associate { i -> stageNames[i] to s8(m + A.BM_STAT_CHANGES + i) - 6 }
        val hp = s32(m + A.BM_HP)
        val item = u16(m + A.BM_ITEM)
        return Battler(
            battlerId = id,
            personality = u32(m + A.BM_PERSONALITY),
            otId = u32(m + A.BM_OTID),
            statusRaw = u32(m + A.BM_STATUS),
            status2 = u32(m + A.BM_STATUS2),
            moveEffects = u32(m + A.BM_MOVE_EFFECT_FLAGS),
            counters = u32(m + A.BM_SUB),
            side = if (id % 2 == 0) "player" else "opponent",
            partySlot = u8(battleCtx + A.BC_SELECTED_MON_INDEX + id).takeIf { it < 6 },
            species = species,
            speciesName = HgssData.speciesName(species),
            nickname = HgssText.decode(chars(m + A.BM_NICKNAME, 11)).takeIf { it.isNotEmpty() },
            level = u8(m + A.BM_LEVEL),
            hp = hp,
            maxHp = s32(m + A.BM_MAX_HP),
            status = HgssPokemon.statusName(u32(m + A.BM_STATUS), hp),
            types = listOf(t1, t2).distinct().map { HgssData.typeName(it) },
            ability = u8(m + A.BM_ABILITY).takeIf { it != 0 }?.let { HgssData.abilityName(it) },
            heldItem = item.takeIf { it != 0 }?.let { HgssData.itemName(it) },
            moves = moves,
            statStages = stages,
        )
    }
}
