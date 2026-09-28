package me.nathanfallet.aiplayspokemon.game.hgss

import me.nathanfallet.aiplayspokemon.game.Memory
import me.nathanfallet.aiplayspokemon.game.hgss.HgssAddresses as A

/**
 * Reads a structured snapshot of Pokémon HeartGold / SoulSilver from NDS main RAM.
 *
 * The code is shared by every HG/SS build; absolute addresses come from a [HgssVersion] chosen by the ROM game
 * code found in the cartridge header copy in RAM (0x023FFE0C), or forced with [version]. Only "IPKE"
 * (HeartGold USA) is filled in for now.
 *
 * Entry points:
 *  - [read]: full snapshot ([HgssState]), or null if the RAM does not look like a running HGSS at all.
 *  - [readGrid]: collision/behavior grid around the player (also embedded in [HgssState.surroundings]).
 *  - [renderGrid]: ASCII rendering (rows + legend) of a [LocalGrid], ready to paste in a prompt.
 *
 * Every pointer is validated to be inside main RAM before being followed; any unexpected value produces a
 * partial state (+ a warning) instead of an exception.
 */
class HgssReader(private val memory: Memory, private val version: HgssVersion? = null) {

    /** Addresses of the version being read, resolved at the start of each [read] / [readGrid]. */
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
        return HgssState(frame = frame, mode = mode, modeDetail = detail, dialogue = dialogue)
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
        var battle: BattleInfo? = null

        if (subApp != null) {
            val appInit = fn(subApp + A.OM_INIT)
            val appOvy = s32(subApp + A.OM_OVY_ID)
            val name = v.appByInit[appInit] ?: A.APP_BY_OVERLAY[appOvy] ?: "app(ovy=$appOvy,init=0x${appInit.toString(16)})"
            if (name == "battle") {
                mode = GameMode.BATTLE
                val procState = s32(subApp + A.OM_PROC_STATE)
                val appExec = s32(subApp + A.OM_EXEC_STATE)
                if (appExec == 2 && procState == A.BATTLE_STATE_MAIN) {
                    battle = ptr(subApp + A.OM_DATA)?.let { readBattle(ctx, it) }
                    detail = "battle"
                } else {
                    detail = "battle_transition(state=$procState)"
                }
            } else {
                mode = GameMode.APP
                detail = name
            }
        } else if (taskman != null) {
            val tasks = taskChain(taskman)
            val scriptEnv = tasks.firstNotNullOfOrNull { (_, env) -> env?.takeIf { u32(it + A.SE_CHECK) == A.SCRIPT_ENV_MAGIC } }
            val startMenuTask = tasks.firstOrNull { (func, _) -> func == v.fnTaskStartMenu }
            when {
                scriptEnv != null -> {
                    dialogue = readDialogue(scriptEnv)
                    mode = if (dialogue.messageBoxOpen) GameMode.DIALOGUE else GameMode.SCRIPT
                    detail = dialogue.waitingFor
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
        val party = saveData?.let { runCatching { readParty(ctx, it) }.getOrNull() } ?: emptyList()
        val bag = saveData?.let { runCatching { readBag(it) }.getOrNull() }

        // The overworld structures (map loader, map objects) are only alive while the field map app runs.
        val fieldAlive = subApp == null && fieldMapApp != null
        val location = runCatching { readLocation(ctx, fs, fieldAlive) }.getOrNull()
        val surroundings = if (fieldAlive && mapReady) runCatching { readSurroundings(ctx, fs, saveData) }.getOrNull() else null

        return HgssState(
            frame = frame,
            mode = mode,
            modeDetail = detail,
            playerControllable = playerControllable,
            player = player,
            location = location,
            dialogue = dialogue,
            startMenu = startMenu,
            party = party,
            battle = battle,
            surroundings = surroundings,
            bag = bag,
            warnings = ctx.warnings,
        )
    }

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
            v.fnScrYesNo in natives -> "yes_no"
            v.fnScrMenuWait1 in natives || v.fnScrMenuWait2 in natives -> "multichoice"
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
        val text = if (open) readGameString(ptr(env + A.SE_STRING_BUFFER_0)) else null
        return DialogueInfo(
            text = text,
            messageBoxOpen = open,
            waitingFor = waiting,
            scriptId = u16(env + A.SE_ACTIVE_SCRIPT),
        )
    }

    private fun readStartMenu(fs: Long, env: Long): StartMenuInfo? {
        val n = u32(env + A.SM_NUM_BUTTONS).toInt()
        if (n !in 1..10) return null
        val items = (0 until n).map { i ->
            val a = u8(env + A.SM_SELECTION_TO_ACTION + i)
            A.START_MENU_ACTIONS.getOrElse(a) { "ACTION_$a" }
        }
        val cursor = u8(fs + A.FS_START_MENU_CURSOR).takeIf { it < n }
        return StartMenuInfo(items, cursor)
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
            if (!mon.checksumOk) ctx.warnings += "party slot $i: checksum mismatch"
            toPartyMon(i, mon)
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

    private fun readSurroundings(ctx: Ctx, fs: Long, saveData: Long?, gridWidth: Int = 15, gridHeight: Int = 11): Surroundings? {
        val playerMo = playerMapObject(fs) ?: return null
        val px = s32(playerMo + A.MO_X)
        val pz = s32(playerMo + A.MO_Z)
        val objects = readMapObjects(fs, playerMo, px, pz)
        val (warps, bgs, triggers) = readEvents(ctx, fs, saveData, px, pz)
        val tiles = TileReader(fs)
        val grid = buildGrid(tiles, px, pz, s32(playerMo + A.MO_FACING), objects, warps, bgs, triggers, gridWidth, gridHeight)
        return Surroundings(
            matrixWidth = tiles.width.takeIf { it > 0 },
            matrixHeight = tiles.height.takeIf { it > 0 },
            grid = grid,
            objects = objects,
            warps = warps,
            bgEvents = bgs,
            triggers = triggers,
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
            )
        }
        return out.sortedBy { Math.abs(it.dx) + Math.abs(it.dz) }
    }

    private fun readEvents(
        ctx: Ctx, fs: Long, saveData: Long?, px: Int, pz: Int,
    ): Triple<List<WarpInfo>, List<BgEventInfo>, List<TriggerInfo>> {
        val me = ptr(fs + A.FS_MAP_EVENTS) ?: return Triple(emptyList(), emptyList(), emptyList())
        fun count(off: Long) = u32(me + off).toInt().takeIf { it in 0..256 } ?: 0.also { ctx.warnings += "bad event count" }

        val warps = ptr(me + A.ME_WARP, 2)?.let { base ->
            (0 until count(A.ME_NUM_WARP)).map { i ->
                val w = base + i * A.WARP_SIZE
                val x = u16(w + A.WARP_X)
                val z = u16(w + A.WARP_Z)
                val dest = u16(w + A.WARP_DEST_MAP)
                WarpInfo(i, x, z, x - px, z - pz, dest, HgssData.mapName(dest), HgssData.mapLocation(dest), u16(w + A.WARP_DEST_WARP))
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
                BgEventInfo(x, z, x - px, z - pz, type, u16(b + A.BG_SCRIPT))
            }
        } ?: emptyList()

        val triggers = ptr(me + A.ME_COORD, 2)?.let { base ->
            (0 until count(A.ME_NUM_COORD)).map { i ->
                val c = base + i * A.COORD_SIZE
                val varValue = varGet(saveData, u16(c + A.COORD_VAR))
                TriggerInfo(
                    x = s16(c + A.COORD_X), z = s16(c + A.COORD_Z),
                    width = u16(c + A.COORD_W), height = u16(c + A.COORD_H),
                    scriptId = u16(c + A.COORD_SCRIPT),
                    active = varValue?.let { it == u16(c + A.COORD_VAL) },
                )
            }
        } ?: emptyList()
        return Triple(warps, bgs, triggers)
    }

    private val bJumpEast by lazy { HgssData.behaviorId("JUMP_EAST") }
    private val bJumpWest by lazy { HgssData.behaviorId("JUMP_WEST") }
    private val bJumpNorth by lazy { HgssData.behaviorId("JUMP_NORTH") }
    private val bJumpSouth by lazy { HgssData.behaviorId("JUMP_SOUTH") }
    private val warpLikeBehaviors by lazy {
        listOf(
            "DOOR", "WARP_ENTRANCE_EAST", "WARP_ENTRANCE_WEST", "WARP_ENTRANCE_NORTH", "WARP_ENTRANCE_SOUTH",
            "WARP_EAST", "WARP_WEST", "WARP_NORTH", "WARP_SOUTH", "WARP_STAIRS_EAST", "WARP_STAIRS_WEST",
            "WARP_PANEL", "LADDER_NORTH", "LADDER_SOUTH", "LADDER_DOWN", "ESCALATOR", "ESCALATOR_FLIP_FACE",
        ).map { HgssData.behaviorId(it) }.filter { it >= 0 }.toSet()
    }

    val gridLegend: Map<String, String> = linkedMapOf(
        "@" to "you (player)",
        "N" to "NPC / object (blocks movement, talk with A while facing it)",
        "f" to "your following Pokémon",
        "o" to "item ball",
        "W" to "warp / door / stairs / ladder (walk into it to change map)",
        "S" to "sign or interactable spot (press A while facing it)",
        "T" to "event trigger (a scene starts when stepped on)",
        "#" to "blocked (wall, tree, furniture...)",
        "." to "walkable",
        "\"" to "tall grass (wild Pokémon)",
        "~" to "water (needs Surf)",
        "v" to "ledge: jump south only", "^" to "ledge: jump north only",
        "<" to "ledge: jump west only", ">" to "ledge: jump east only",
        "?" to "unknown / outside the loaded map",
    )

    private fun buildGrid(
        tiles: TileReader, px: Int, pz: Int, facing: Int,
        objects: List<MapObjectInfo>, warps: List<WarpInfo>, bgs: List<BgEventInfo>, triggers: List<TriggerInfo>,
        w: Int, h: Int,
    ): LocalGrid {
        val ox = px - w / 2
        val oz = pz - h / 2
        val flags = HgssData.tileBehaviorFlags
        val rows = (0 until h).map { r ->
            val sb = StringBuilder(w)
            for (c in 0 until w) {
                val x = ox + c
                val z = oz + r
                val obj = objects.firstOrNull { it.x == x && it.z == z && !it.hidden }
                val attr = tiles.attr(x, z)
                val ch = when {
                    x == px && z == pz -> '@'
                    obj != null -> when (obj.kind) {
                        "follower" -> 'f'
                        "item_ball" -> 'o'
                        else -> 'N'
                    }
                    warps.any { it.x == x && it.z == z } -> 'W'
                    bgs.any { it.x == x && it.z == z && it.type != "hidden_item" } -> 'S'
                    attr == null -> '?'
                    else -> {
                        val b = attr and 0xFF
                        val blocked = attr and A.TILE_COLLISION_BIT != 0
                        val trig = triggers.any { t -> t.active == true && x >= t.x && x < t.x + t.width && z >= t.z && z < t.z + t.height }
                        when {
                            b == bJumpSouth -> 'v'
                            b == bJumpNorth -> '^'
                            b == bJumpWest -> '<'
                            b == bJumpEast -> '>'
                            b in warpLikeBehaviors -> 'W'
                            flags[b] and 1 != 0 -> '~'
                            blocked -> '#'
                            trig -> 'T'
                            flags[b] and 2 != 0 -> '"'
                            else -> '.'
                        }
                    }
                }
                sb.append(ch)
            }
            sb.toString()
        }
        return LocalGrid(ox, oz, w, h, rows, gridLegend)
    }

    /**
     * Reads only the grid around the player (without the full snapshot). Returns null outside the overworld.
     */
    fun readGrid(width: Int = 15, height: Int = 11): LocalGrid? = try {
        v = resolveVersion().first ?: throw IllegalStateException("unsupported version")
        val fs = currentFieldSystem()
        fs?.let { f ->
            readSurroundings(Ctx(), f, ptr(v.saveDataPtr), width, height)?.grid
        }
    } catch (_: Exception) {
        null
    }

    private fun currentFieldSystem(): Long? {
        val om = ptr(v.mainAppState + A.MAIN_APP_OVERLAY_MANAGER) ?: return null
        val init = fn(om + A.OM_INIT)
        if (init != v.fnFieldContinueAppInit && init != v.fnFieldNewGameAppInit) return null
        if (s32(om + A.OM_EXEC_STATE) < 2) return null
        val fs = ptr(om + A.OM_DATA) ?: return null
        val sub0 = ptr(fs + A.FS_SUB0) ?: return null
        if (ptr(sub0 + A.FSS0_SUB_APP) != null || ptr(sub0 + A.FSS0_FIELD_MAP_APP) == null) return null
        if (u32(fs + A.FS_MAP_READY) == 0L) return null
        return fs
    }

    /** Renders a grid as text: header with coordinates, the rows, and the legend of the symbols present. */
    fun renderGrid(grid: LocalGrid, facing: String? = null): String {
        val sb = StringBuilder()
        sb.append("Map around you (north is up; top-left tile = x ${grid.originX}, z ${grid.originZ}")
        if (facing != null) sb.append("; you face $facing")
        sb.append("):\n")
        grid.rows.forEach { sb.append(it).append('\n') }
        val used = grid.rows.joinToString("").toSet().map { it.toString() }.toSet()
        sb.append("Legend:\n")
        grid.legend.filterKeys { it in used }.forEach { (k, v) -> sb.append("  ").append(k).append(" = ").append(v).append('\n') }
        return sb.toString()
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
        val menu = input?.let { A.BATTLE_MENUS[s8(it + A.BI_CUR_MENU_ID)] }
        val cursor = input?.let {
            if (u8(it + A.BI_MENU_CURSOR) != 0) listOf(s8(it + A.BI_MENU_CURSOR + 2), s8(it + A.BI_MENU_CURSOR + 1)) else null
        }
        return BattleInfo(
            isWild = type and 1L == 0L,
            battleTypeFlags = flags,
            isDoubles = type and 2L != 0L,
            turn = s32(battleCtx + A.BC_TOTAL_TURNS).takeIf { it in 0..10000 },
            player = battlers.filter { it.side == "player" && it.species != 0 },
            opponents = battlers.filter { it.side == "opponent" && it.species != 0 },
            trainers = trainers,
            menu = menu,
            menuCursor = cursor,
            message = readGameString(ptr(bs + A.BS_MSG_BUFFER)),
            safariBalls = if (type and (1L shl 5) != 0L) s32(bs + A.BS_SAFARI_BALLS) else null,
        )
    }

    private fun readBattler(battleCtx: Long, id: Int): Battler {
        val m = battleCtx + A.BC_BATTLE_MONS + id * A.BM_SIZE
        val species = u16(m + A.BM_SPECIES)
        val moves = (0 until 4).mapNotNull { i ->
            val mv = u16(m + A.BM_MOVES + 2L * i)
            if (mv == 0) null else moveInfo(mv, u8(m + A.BM_PP_CUR + i), u8(m + A.BM_PP_MAX + i))
        }
        val t1 = u8(m + A.BM_TYPE1)
        val t2 = u8(m + A.BM_TYPE2)
        val stageNames = listOf("hp", "atk", "def", "speed", "spAtk", "spDef", "accuracy", "evasion")
        val stages = (1 until 8).associate { i -> stageNames[i] to s8(m + A.BM_STAT_CHANGES + i) - 6 }
        val hp = s32(m + A.BM_HP)
        val item = u16(m + A.BM_ITEM)
        return Battler(
            battlerId = id,
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
