package dev.kotlinds.pokemonclient.hgss

/**
 * Structure offsets for Pokémon HeartGold / SoulSilver, taken from the pret/pokeheartgold
 * decompilation (matching build, `build/heartgold.us/main.elf.xMAP`).
 *
 * Offsets were computed by compiling the decomp headers with arm-none-eabi-gcc
 * (`-mabi=apcs-gnu -mstructure-size-boundary=8 -fno-short-enums`, which reproduces mwcc's layout: 4-byte
 * alignment for 64-bit fields, int-sized enums) and `offsetof()`, then cross-checked against the
 * disassembly of the built objects / hand-written asm where noted ("asm✓").
 *
 * This object only holds what is shared by every HG/SS build (structure offsets, enum values, overlay ids):
 * HeartGold and SoulSilver are the same code built with a flag and no struct depends on it. Absolute addresses
 * (globals and function addresses in the ARM9 main binary) are version-specific and live in [HgssVersion].
 * Thumb function pointers stored in RAM have bit 0 set: always compare with `and THUMB_MASK`.
 */
object HgssAddresses {

    const val MAIN_RAM_START = 0x02000000L
    const val MAIN_RAM_END = 0x02400000L // exclusive (4 MB)
    const val THUMB_MASK = 0xFFFFFFFEL

    /** Offsets inside `_02111868` (struct UnkStruct_02111868, src/main.c:31), see [HgssVersion.mainAppState]. */
    const val MAIN_APP_OVERLAY_ID = 0x0L         // FSOverlayID mainOverlayId (overlay of the running top-level app, -1 = none)
    const val MAIN_APP_OVERLAY_MANAGER = 0x4L    // OverlayManager *overlayManager
    const val MAIN_APP_QUEUED_OVERLAY_ID = 0x8L
    const val MAIN_APP_QUEUED_TEMPLATE = 0xCL
    const val MAIN_APP_SAVE_DATA = 0x18L         // unk_10.saveData (same pointer as sSaveDataPtr) include/main.h:7

    /** Offsets inside `gSystem` (struct System, include/system.h:21), see [HgssVersion.gSystem]. */
    const val SYS_VBLANK_COUNTER = 0x2CL
    /** `int heldKeysRaw`: the buttons physically held, as read by the game this frame (PAD_* bits). */
    const val SYS_HELD_KEYS_RAW = 0x38L
    const val SYS_HELD_KEYS = 0x44L
    const val SYS_NEW_KEYS = 0x48L

    /** `sOverlayRegions[3][8]` entries {FSOverlayID id; BOOL active;} (src/poke_overlay.c:19), see [HgssVersion.loadedOverlays]. */
    const val LOADED_OVERLAY_ENTRY_SIZE = 8
    const val LOADED_OVERLAYS_PER_REGION = 8

    /** Copy of the cartridge header kept in main RAM by the BIOS/firmware at 0x027FFE00 (mirror of 0x023FFE00). */
    const val ROM_HEADER_COPY = 0x023FFE00L
    const val ROM_HEADER_GAME_CODE = 0x0CL       // 4 ASCII chars, e.g. "IPKE"

    // Overlay ids (order of `Overlay` entries in main.lsf)
    const val OVY_FIELD = 1
    const val OVY_BATTLE = 12
    const val OVY_INTRO_TITLE = 60
    const val OVY_MAIN_MENU = 74
    const val OVY_OAK_SPEECH = 53
    const val OVY_36 = 36 // "continue"/"new game" transitional apps

    /** Sub-application overlays (FieldSystem.unk0->unk4 template.ovy_id), from src/launch_application.c. */
    val APP_BY_OVERLAY: Map<Int, String> = mapOf(
        12 to "battle", 14 to "pc_box", 15 to "bag", 16 to "berry_pots", 18 to "pokedex", 43 to "pal_pad",
        50 to "trainer_card", 54 to "options", 58 to "apricorn_box", 61 to "choose_starter", 63 to "hall_of_fame_register",
        64 to "hall_of_fame", 68 to "move_relearner", 69 to "geonet_globe", 71 to "trade", 76 to "credits",
        78 to "certificates", 87 to "scratch_off_cards", 95 to "hatch_egg", 96 to "pokeathlon", 100 to "pokegear",
        101 to "town_map", 102 to "easy_chat", 106 to "legendary_cinematic", 109 to "photo_album", 110 to "alph_puzzle",
        103 to "mailbox", 111 to "bug_contest_swap", 113 to "unown_report", 122 to "voltorb_flip",
    )

    // ------------------------------------------------------------------------------------------------
    // OverlayManager (include/overlay_manager.h) — size 0x28
    // ------------------------------------------------------------------------------------------------
    const val OM_INIT = 0x00L        // template.init (thumb ptr)
    const val OM_EXEC = 0x04L
    const val OM_OVY_ID = 0x0CL      // template.ovy_id
    const val OM_EXEC_STATE = 0x10L  // 0 load, 1 init, 2 main, 3 exit (src/overlay_manager.c OverlayManager_Run)
    const val OM_PROC_STATE = 0x14L  // app-private state machine (*state)
    const val OM_ARGS = 0x18L
    const val OM_DATA = 0x1CL

    // ------------------------------------------------------------------------------------------------
    // FieldSystem (include/field_system.h) — size 0x128 (asm✓ 0x2C/0x30/0x5C/0x60 in asm/unk_02054648.s)
    // ------------------------------------------------------------------------------------------------
    const val FS_SUB0 = 0x00L                // struct FieldSystemUnkSub0 *unk0
    const val FS_SAVE_DATA = 0x0CL
    const val FS_TASKMAN = 0x10L             // TaskManager * (top of the running field task stack), NULL when idle
    const val FS_MAP_EVENTS = 0x14L          // MapEvents *
    const val FS_LOCATION = 0x20L            // Location * (points into save: LocalFieldData current position)
    const val FS_MAP_LOADER = 0x2CL          // FieldSystemUnkSub2C * (field overlay map loader, "unk2C")
    const val FS_MAP_MATRIX = 0x30L          // MAPMATRIX *
    const val FS_MAP_OBJECT_MANAGER = 0x3CL  // MapObjectManager *
    const val FS_PLAYER_AVATAR = 0x40L       // PlayerAvatar *
    const val FS_TERRAIN_ATTRIBUTES = 0x5CL  // TerrainAttributes * (only for special maps)
    const val FS_TERRAIN_ACCESSOR = 0x60L    // const accessor table (see HgssVersion.terrainAccessor*), 0 while no map is loaded
    const val FS_MAP_READY = 0x6CL           // BOOL unk6C: field map overlay running (FieldSystem_IsPlayerMovementAllowed)
    const val FS_MAP_LOAD_TYPE = 0x70L       // int unk70 (0 = normal, 1 safari gate, 2 union room, 3 colosseum, 4 battle tower)
    const val FS_START_MENU_CURSOR = 0xD3L   // u8 unkD3: start menu cursor position (src/start_menu.c:590) [medium confidence]
    const val FS_LAST_START_MENU_ACTION = 0xE0L
    const val FS_FOLLOW_INTERACT = 0x120L    // work of Task_FollowMonInteract: +0x10 String* message, +0x869 u8 state
    const val FOLLOW_INTERACT_STRING = 0x10L
    const val FOLLOW_INTERACT_STATE = 0x869L
    const val FOLLOW_INTERACT_MESSAGE_SHOWN = 6 // verified live: 6 while the message box is up (printing or waiting for A)
    const val FS_FOLLOW_MON = 0xE4L          // FollowMon struct (inline)
    const val FOLLOW_MON_MAP_OBJECT = 0x00L  // LocalMapObject *
    const val FOLLOW_MON_SPECIES = 0x10L
    const val FOLLOW_MON_ACTIVE = 0x16L      // u8

    // struct FieldSystemUnkSub0 (include/field_system.h)
    const val FSS0_FIELD_MAP_APP = 0x00L     // OverlayManager *unk0: the field map (overlay 1) itself
    const val FSS0_SUB_APP = 0x04L           // OverlayManager *unk4: launched application (battle, bag, party...)
    const val FSS0_IS_PAUSED = 0x08L

    // ------------------------------------------------------------------------------------------------
    // Location (include/field_types_def.h)
    // ------------------------------------------------------------------------------------------------
    const val LOC_MAP_ID = 0x00L
    const val LOC_WARP_ID = 0x04L
    const val LOC_X = 0x08L
    const val LOC_Z = 0x0CL
    const val LOC_DIRECTION = 0x10L

    // ------------------------------------------------------------------------------------------------
    // PlayerAvatar (include/player_avatar.h) — size 0x40
    // ------------------------------------------------------------------------------------------------
    const val PA_FLAGS = 0x00L
    const val PA_TRANSITION_FLAGS = 0x04L
    const val PA_MOVE_STATE = 0x10L          // enum AvatarMoveState? (0 none, 1 moving, 2 turning) [medium]
    const val PA_PLAYER_MOVE_STATE = 0x14L   // enum PlayerMoveState (0 none, 1 start, 2 moving, 3 end) [medium]
    const val PA_STATE = 0x18L               // PLAYER_STATE_* (0 walking, 1 cycling, 2 surfing, ...) include/constants/global_fieldmap.h
    const val PA_GENDER = 0x1CL
    const val PA_MAP_OBJECT = 0x30L          // LocalMapObject *
    const val PA_PLAYER_SAVE_DATA = 0x38L    // PlayerSaveData * {u16 hasRunningShoes; u16 lock; s32 state}

    // ------------------------------------------------------------------------------------------------
    // LocalMapObject (include/map_object.h, offsets in comments there) — size 0x12C (asm✓ 75*4 in map_object.o)
    // ------------------------------------------------------------------------------------------------
    const val MO_SIZE = 0x12CL
    const val MO_FLAGS = 0x00L               // bit0 ACTIVE, bit9 "VISIBLE" (actually set = hidden, see ScrCmd_374)
    const val MO_ID = 0x08L
    const val MO_MAP_ID = 0x0CL
    const val MO_SPRITE_ID = 0x10L
    const val MO_MOVEMENT = 0x14L
    const val MO_TYPE = 0x18L
    const val MO_EVENT_FLAG = 0x1CL
    const val MO_SCRIPT_ID = 0x20L
    const val MO_FACING = 0x28L              // DIR_NORTH 0, SOUTH 1, WEST 2, EAST 3
    const val MO_PARAM0 = 0x38L              // param[0]: for a trainer (type 1), its sight range in tiles
    const val MO_PREVIOUS_X = 0x58L
    const val MO_PREVIOUS_Z = 0x60L
    const val MO_X = 0x64L                   // global tile X (matrix-wide)
    const val MO_Y = 0x68L                   // height (s32)
    const val MO_Z = 0x6CL                   // global tile Z (north/south)
    const val MO_POSITION_VECTOR = 0x70L     // VecFx32 {x,y,z}; at rest x = X*16*4096 + 8*4096 (src/map_object.c:525)
    const val MO_FLAG_ACTIVE = 1L shl 0
    const val MO_FLAG_HIDDEN = 1L shl 9

    // MapObjectManager (include/map_object.h) — asm✓ 0x124/0x128 in map_object.o
    const val MOM_OBJECT_COUNT = 0x04L       // capacity of the objects array
    const val MOM_OBJECTS = 0x124L
    const val MOM_FIELD_SYSTEM = 0x128L

    // ------------------------------------------------------------------------------------------------
    // MapEvents (include/map_events_internal.h). Event coordinates are GLOBAL (same space as MO_X/MO_Z),
    // e.g. Field_GetWarpEventAtXYPos compares directly with the player position (src/map_events.c:63).
    // ------------------------------------------------------------------------------------------------
    const val ME_NUM_BG = 0x00L
    const val ME_NUM_OBJ = 0x04L
    const val ME_NUM_WARP = 0x08L
    const val ME_NUM_COORD = 0x0CL
    const val ME_BG = 0x10L
    const val ME_OBJ = 0x14L
    const val ME_WARP = 0x18L
    const val ME_COORD = 0x1CL
    const val ME_SCRIPT_HEADER = 0x820L      // u8 script_header[0x100] (after u8 event_data[0x800])
    const val FS_SCRIPTS_DISABLED = 0xACL    // u32 unkAC: map scene scripts are not checked when non-zero
    // BG_EVENT size 0x14: u16 scriptId; u16 type; int x; int z; int y; u16 dir
    const val BG_SIZE = 0x14L
    const val BG_SCRIPT = 0x00L
    const val BG_TYPE = 0x02L
    const val BG_X = 0x04L
    const val BG_Z = 0x08L
    const val BG_DIR = 0x10L
    // WARP_EVENT size 0xC: u16 x; u16 z; u16 header (destination map id); u16 anchor (destination warp id); u32 y
    const val WARP_SIZE = 0x0CL
    const val WARP_X = 0x00L
    const val WARP_Z = 0x02L
    const val WARP_DEST_MAP = 0x04L
    const val WARP_DEST_WARP = 0x06L
    // COORD_EVENT size 0x10: u16 scriptId; s16 x; s16 z; u16 w; u16 h; u16 y; u16 val; u16 var
    // active when x<=px<x+w && z<=pz<z+h && VarGet(var)==val (asm/unk_0203DB6C.s sub_0203DE04, asm✓)
    const val COORD_SIZE = 0x10L
    const val COORD_SCRIPT = 0x00L
    const val COORD_X = 0x02L
    const val COORD_Z = 0x04L
    const val COORD_W = 0x06L
    const val COORD_H = 0x08L
    const val COORD_VAL = 0x0CL
    const val COORD_VAR = 0x0EL

    // ------------------------------------------------------------------------------------------------
    // MAPMATRIX (include/map_matrix.h) — size 0xFB2
    // ------------------------------------------------------------------------------------------------
    const val MM_WIDTH = 0x00L               // u8, in 32x32 blocks
    const val MM_HEIGHT = 0x01L
    const val MM_MATRIX_ID = 0x02L
    const val MM_HEADERS = 0x06L             // u16[w*h] map id of each block
    const val MM_MODELS = 0x964L             // u16[w*h] land data id of each block

    // ------------------------------------------------------------------------------------------------
    // Terrain (collision) attributes. One u16 per tile, 32x32 tiles per block, row-major (index = z%32*32 + x%32).
    //   bit 15      : collision (sub_020548C0 in asm/unk_02054648.s)
    //   bits 0..7   : tile behavior (GetMetatileBehavior) -> include/constants/metatile_behavior.h
    //   bits 8..14  : unknown extra attribute (sub_020548EC)
    // Verified against the ROM land data (a/0/6/5 at offset 0x14) of the player's room.
    // ------------------------------------------------------------------------------------------------
    const val TILE_COLLISION_BIT = 0x8000
    const val BLOCK_TILES = 32

    // ChooseStarterAppWork (src/choose_starter_app.c; "u8 frame; // 3A4" anchors the offsets), verified live
    const val CS_CUR_SELECTION = 0x394L      // u32: 0 Chikorita, 1 Cyndaquil, 2 Totodile (sSpecies); RIGHT: 0 -> 2 -> 1
    const val CS_SELECT_STATE = 0x3A8L       // int: 0 nothing inspected, 1 inspecting the front ball, 2 confirming
    const val CS_PROC_HANDLE_INPUT = 5       // CHOOSE_STARTER_STATE_HANDLE_INPUT (OverlayManager proc state)

    // Mailbox app (overlay 103): data +0x0C inner work, inner +0x278 GridInputHandler* (+0x0D nextInput: slot 0..9
    // row by row in 2 columns, 10 = CANCEL), verified live. Mail save (SAVE_MAILBOX): Mail[20] of 0x38 bytes:
    // +7 mail_type (0xFF = empty), +8 author_name u16[8].
    const val MAILBOX_INNER = 0x0CL
    const val MAILBOX_GRID_INPUT = 0x278L
    const val GRID_INPUT_NEXT = 0x0DL
    const val SAVE_MAILBOX = 13
    const val MAIL_SIZE = 0x38L
    const val MAIL_TYPE = 0x07L
    const val MAIL_AUTHOR = 0x08L

    // OakSpeechData (include/oaks_speech_internal.h) = OverlayManager.data of the Oak speech app (overlay 53)
    const val OAK_STATE = 0x0CL
    const val OAK_STRING = 0x110L            // String *string: last dialog message (freed after printing, usually still readable)

    // Field map loader (FieldSystem.unk2C, field overlay struct, only asm): asm/overlay_01_021F4704.s
    const val ML_BLOCK_BUFFERS = 0x90L       // void *slots[4]; each buffer: +0x000 u16 attrs[1024]
    const val ML_MATRIX_WIDTH = 0xC4L        // u32, blocks (ov01_021F654C)
    const val ML_MATRIX_HEIGHT = 0xC8L       // u32, blocks
    const val ML_SLOT_COUNT = 4
    const val ML_BUFFER_BLOCK_INDEX = 0x860L // s32 matrix block index held by that buffer, -1 if none (ov01_021F5038 / ov01_021F652C)

    // TerrainAttributes (include/terrain_attributes.h): u8 mapMatrixIndexToBlockIndex[225]; u16 attrs[16][1024]
    const val TA_ATTRS = 0xE2L
    const val TA_MAX_MATRIX = 225
    const val TA_MAX_BLOCKS = 16

    // ------------------------------------------------------------------------------------------------
    // TaskManager (include/task.h), ScriptEnvironment / ScriptContext (include/script.h)
    // ------------------------------------------------------------------------------------------------
    const val TM_PREV = 0x00L
    const val TM_FUNC = 0x04L
    const val TM_STATE = 0x08L
    const val TM_ENV = 0x0CL

    const val SE_CHECK = 0x00L               // == SCRIPT_ENV_MAGIC while alive
    const val SCRIPT_ENV_MAGIC = 222271L     // Unk80_10_C_MAGIC include/script.h:34
    const val SE_TEXT_PRINTER = 0x05L
    const val SE_MSGBOX_OPEN = 0x08L         // u8 unk_8 (SCRIPTENV_FIELD_08): 1 while the field message window is open (ScrCmd_OpenMsg/CloseMsg)
    const val SE_ACTIVE_CONTEXTS = 0x09L
    const val SE_ACTIVE_SCRIPT = 0x0AL       // u16 activeScriptNumber
    const val SE_LIST_MENU_2D = 0x24L        // yes/no menu while shown
    const val SE_LAST_INTERACTED = 0x2CL
    const val SE_SCRIPT_CONTEXTS = 0x38L     // ScriptContext *[3]
    const val SE_STRING_BUFFER_0 = 0x48L     // String *: fully expanded message currently printed (ovFieldMain_ReadAndExpandMsgDataViaBuffer)
    const val SE_ENGAGED_TRAINER_0_ID = 0x60L // int engagedTrainers[0].trainerId (EngagedTrainer[2] at 0x54, 0x1C each): set when a trainer sees the player
    const val SE_SPECIAL_VARS = 0x8CL        // u16[] VAR_SPECIAL 0x8000..

    const val SC_MODE = 0x01L                // 0 stopped, 1 bytecode, 2 native
    const val SC_NATIVE = 0x04L              // ScrCmdFunc native_ptr

    // String (include/pm_string.h)
    // TextPrinter (include/font_types_def.h): template.currentChar.raw (+0) points into the String being printed
    const val TP_CURRENT_CHAR = 0x00L
    const val TP_STATE = 0x28L               // 0/1/4/5/6 printing/scrolling, 2/3/7/8 waiting at a page break for A
    val TEXT_PRINTER_WAIT_STATES = setOf(2, 3, 7, 8)

    const val STR_MAXSIZE = 0x00L
    const val STR_SIZE = 0x02L
    const val STR_MAGIC = 0x04L
    const val STR_DATA = 0x08L
    const val STRING_MAGIC = 0xB6F8D2ECL     // src/pm_string.c:9
    const val STRING_INVAL = 0xB6F8D2EDL     // src/pm_string.c:10: set by String_Delete

    // StartMenuTaskData (include/start_menu.h) — TaskManager.env of Task_StartMenu
    const val SE_FIELD_MENU = 0x10L          // FieldMenu * of top-screen multichoice menus (ScrCmd_064..067)

    // Bottom-screen manager: FieldSystem.unkD8 -> SysTask (data at +0x10) -> {u8 appId; u8 state; SysTask *app}
    const val FS_BOTTOM_SCREEN_TASK = 0xD8L
    const val SYSTASK_DATA = 0x10L
    const val BSM_APP_ID = 0x00L             // 0 = start menu icons, 3 = script menu (yes/no, multichoice)
    const val BSM_APP_TASK = 0x04L
    const val BOTTOM_APP_SCRIPT_MENU = 3
    // Overlay 27 touch menu ("TM", ov27_0225C434): +0 state, +0xC FieldMenu*, +0x394 cursor
    const val TOUCH_MENU_STATE = 0x00L       // 4 = yes/no waiting, 8 = multichoice waiting
    const val TOUCH_MENU_FIELD_MENU = 0x0CL
    const val TOUCH_MENU_CURSOR = 0x394L
    const val TM_STATE_YES_NO_WAIT = 4
    const val TM_STATE_MENU_WAIT = 8
    // FieldMenu (ov01_021EDAFC, 0x2E0 bytes)
    const val FMENU_COUNT = 0x9BL            // u8
    const val FMENU_LIST_MENU = 0xB8L        // ListMenu2D * (top-screen menus)
    const val FMENU_ITEMS_TOP = 0xBCL        // ListMenuItem[28] {String *text; s32 value}, top-screen menus
    const val FMENU_ITEMS_TOUCH = 0x1C4L     // ListMenuItem[28], touch-screen menus
    const val LIST_MENU_ITEM_SIZE = 8L
    const val LM2D_SELECTED = 0x15L          // ListMenu2D.selectedIndex (Get2dMenuSelection)

    const val SM_CURSOR_ACTIVE = 0x20L
    const val SM_STATE = 0x26L               // u16: 3 = HANDLE_INPUT (waiting), 0..2 init, 4/5 fade/app, 0x10/0x11 closing
    const val SM_STATE_HANDLE_INPUT = 3
    const val SM_SELECTED_INDEX = 0x28L
    const val SM_NUM_BUTTONS = 0x2CL
    const val SM_SELECTION_TO_ACTION = 0x3AL // u8[10]; StartMenuAction enum in src/start_menu.c:47
    /** StartMenuAction (src/start_menu.c) -> label shown on the icon. */
    val START_MENU_LABELS = listOf(
        "POKéDEX", "POKéMON", "BAG", "TRAINER CARD", "SAVE", "OPTIONS", "EXIT", "ACTION_7", "RETIRE",
        "POKéGEAR", "POKéGEAR", "POKéGEAR", "POKéGEAR",
    )
    /** Language-independent ids of the start menu actions (same order as [START_MENU_LABELS]). */
    val START_MENU_IDS = listOf(
        "pokedex", "pokemon", "bag", "trainer_card", "save", "options", "exit", "action7", "retire",
        "pokegear", "pokegear", "pokegear", "pokegear",
    )
    const val START_MENU_ACTION_TRAINER_CARD = 3
    /** sActionToIconIndex: grid slot of each action's icon (slot = column * 4 + row); others are not grid icons. */
    val START_MENU_ICON_OF_ACTION = mapOf(0 to 0, 1 to 1, 2 to 2, 3 to 4, 4 to 5, 5 to 6, 11 to 3, 12 to 3)

    // ------------------------------------------------------------------------------------------------
    // SaveData (include/save.h) and save arrays (include/constants/save_arrays.h)
    // ------------------------------------------------------------------------------------------------
    const val SAVE_DYNAMIC_REGION = 0x10L
    const val SAVE_ARRAY_HEADERS = 0x23014L  // struct SaveArrayHeader[42] (comment in save.h agrees)
    const val SAH_SIZE = 0x10L
    const val SAH_ID = 0x00L
    const val SAH_LENGTH = 0x04L
    const val SAH_OFFSET = 0x08L             // offset inside dynamic_region (SaveArray_Get, src/save.c:128)
    const val SAVE_BLOCK_NUM = 42
    const val SAVE_PLAYERDATA = 1
    const val SAVE_PARTY = 2
    const val SAVE_PC_STORAGE = 41           // PCStorage (include/constants/save_arrays.h)
    const val SAVE_BAG = 3
    const val SAVE_FLAGS = 4                 // SaveVarsFlags {u16 vars[0x170]; u8 flags[...]}
    const val SAVE_LOCAL_FIELD_DATA = 5
    const val SAVE_POKEDEX = 6
    const val FLAGS_OFFSET = 0x2E0L          // SaveVarsFlags.flags: flag id -> byte id/8, bit id%8
    const val NUM_SAVE_FLAGS = 0xB60         // NUM_FLAGS (include/save_vars_flags.h): u8 flags[0x16C]
    const val SAVE_MISC = 9                  // SAVE_MISC_DATA (include/save_misc_data.h)
    // SAVE_MISC_DATA: apricorn_trees[128] (4 bytes), berry_pots[4] (12), GF_RTC_DateTime (0x1C), then Gymmick
    // (rivalName follows at 0x270, unk_0280 at 0x280: consistent with the 0x24-byte Gymmick at 0x24C).
    const val MISC_GYMMICK = 0x24CL
    const val GYMMICK_SIZE = 0x24
    const val LFD_RUNNING_SHOES = 0x6CL      // LocalFieldData.playerSaveData.hasRunningShoes (u16)
    const val POKEDEX_ENABLED = 0x336L       // Pokedex.dexEnabled (u8)

    // PLAYERDATA (include/player_data.h): Options options; PlayerProfile profile; u16 coins; IGT igt
    const val PD_PROFILE = 0x04L
    const val PD_COINS = 0x24L
    const val PD_PLAY_TIME = 0x26L           // IGT {u16 hours; u8 minutes; u8 seconds} (include/player_data.h)
    const val PP_NAME = 0x00L                // u16[8]
    const val PP_ID = 0x10L
    const val PP_MONEY = 0x14L
    const val PP_GENDER = 0x18L
    const val PP_JOHTO_BADGES = 0x1AL
    const val PP_KANTO_BADGES = 0x1FL

    // SaveVarsFlags: var id 0x4000.. -> vars[id-0x4000] (src/save_vars_flags.c:57)
    const val VAR_BASE = 0x4000
    const val SPECIAL_VAR_BASE = 0x8000
    const val NUM_VARS = 0x170

    // Party (include/pokemon_types_def.h)
    const val PARTY_MAX_COUNT = 0x00L
    const val PARTY_CUR_COUNT = 0x04L
    const val PARTY_MONS = 0x08L
    const val POKEMON_SIZE = 0xECL
    // BoxPokemon
    const val BOX_PERSONALITY = 0x00L
    const val BOX_FLAGS = 0x04L              // bit0 partyDecrypted, bit1 boxDecrypted, bit2 checksumFailed
    const val BOX_CHECKSUM = 0x06L
    const val BOX_BLOCKS = 0x08L             // 4 x 0x20 bytes, encrypted with LCRNG seeded by checksum
    const val BOX_BLOCKS_SIZE = 0x80
    const val PARTY_DATA = 0x88L             // PartyPokemon 0x64 bytes, encrypted with LCRNG seeded by personality
    const val PARTY_DATA_SIZE = 0x64

    // Bag (include/bag_types_def.h): ItemSlot {u16 id; u16 quantity}
    val BAG_POCKETS: List<Triple<String, Long, Int>> = listOf(
        Triple("items", 0x000L, 165), Triple("key_items", 0x294L, 50), Triple("tms_hms", 0x35CL, 101),
        Triple("mail", 0x4F0L, 12), Triple("medicine", 0x520L, 40), Triple("berries", 0x5C0L, 64),
        Triple("balls", 0x6C0L, 24), Triple("battle_items", 0x720L, 30),
    )

    /** Bag.registeredItems: u16[2], the key items bound to Y (first) and to the second touch button. */
    const val BAG_REGISTERED_ITEMS = 0x798L

    // ------------------------------------------------------------------------------------------------
    // Battle (include/battle/battle.h). BattleSystem = OverlayManager.data of the battle app (size 0x2490,
    // allocated by ov12_0223A0D4); valid while the app's proc_state == BSTATE_BATTLE_MAIN (9).
    // asm✓: 0x2C/0x30/0x44/0x19C/0x2414 in battle_system.o; battleMons 0x2D40 + hp 0x4C = 0x2D8C etc.
    // ------------------------------------------------------------------------------------------------
    const val BATTLE_STATE_MAIN = 9          // BSTATE_BATTLE_MAIN src/battle/battle_022378C0.c:25
    const val BS_MSG_BUFFER = 0x18L          // String *msgBuffer (last battle message)
    const val BS_BATTLE_TYPE = 0x2CL
    const val BS_CTX = 0x30L
    const val BS_MAX_BATTLERS = 0x44L
    const val BS_TRAINER_PARTY = 0x68L       // Party *[4]
    const val BS_BAG = 0x58L                 // Bag *bag: balls thrown and items used are taken out of it (battle_controller_player.c:1756)

    // BattleSetup (include/battle/battle_setup.h) = OverlayManager.args of the battle app, alive for the whole app
    // (intro, battle, end, evolutions). It holds copies of the player's party and bag, used by the battle and written
    // back to the save only when the app ends (sub_0205239C, src/battle/battle_setup.c:418).
    const val SETUP_BATTLE_TYPE = 0x00L      // u32 battleType (include/battle/battle_setup.h:29)
    const val BATTLE_TYPE_TUTORIAL = 1L shl 10 // the catching demo: Lyra's party and bag (include/constants/battle.h:144)
    const val SETUP_PARTY = 0x04L            // Party *party[4]; [0] = the player's
    const val SETUP_BAG = 0x108L             // Bag *bag (same layout as the save's bag)
    const val BS_TRAINER_ID = 0xA0L          // u16[4]
    const val BS_TRAINERS = 0xACL            // Trainer[4], size 0x34: +1 trainerClass, +0x14 name u16[8]
    const val TRAINER_SIZE = 0x34L
    const val TRAINER_CLASS = 0x01L
    const val TRAINER_NAME = 0x14L
    const val BS_BATTLE_INPUT = 0x19CL
    const val BS_SAFARI_BALLS = 0x2414L

    const val BC_SELECTED_MON_INDEX = 0x219CL // u8[4] party slot of each battler
    const val BC_BATTLE_MONS = 0x2D40L
    const val BC_TOTAL_TURNS = 0x150L
    const val BC_BATTLERS_ON_FIELD = 0x3150L
    /** u8[4][6]: party order as the battle sees it (unk_312C, include/battle/battle.h:430; partyOrder in Platinum). */
    const val BC_PARTY_ORDER = 0x312CL

    const val BM_SIZE = 0xC0L
    const val BM_SPECIES = 0x00L
    const val BM_STATS = 0x02L               // atk, def, speed, spAtk, spDef (u16 each)
    const val BM_MOVES = 0x0CL
    const val BM_STAT_CHANGES = 0x18L        // s8[8], 6 = neutral
    const val BM_TYPE1 = 0x24L
    const val BM_TYPE2 = 0x25L
    const val BM_ABILITY = 0x27L
    const val BM_ANNOUNCE_FLAGS = 0x28L      // u32: bit0 sendOut, 1 intimidate, 2 trace, 3 download, 4 anticipation, 5 forewarn, 6 slowStart, 8 frisk, 9 moldBreaker, 10 pressure
    const val BM_PP_CUR = 0x2CL
    const val BM_PP_MAX = 0x30L           // u8 movePP[4]: actually the PP Ups (max PP is computed)
    const val BM_LEVEL = 0x34L
    const val BM_NICKNAME = 0x36L            // u16[11]
    const val BM_HP = 0x4CL
    const val BM_MAX_HP = 0x50L
    const val BM_PERSONALITY = 0x68L
    const val BM_STATUS = 0x6CL
    const val BM_STATUS2 = 0x70L
    const val BM_OTID = 0x74L                // u32 original trainer id (+ secret id), in clear
    const val BM_ITEM = 0x78L
    const val BM_MOVE_EFFECT_FLAGS = 0x80L   // u32 MOVE_EFFECT_FLAG_* (leech seed, perish song, fly/dig/dive, ingrain, yawn...)
    const val BM_SUB = 0x88L                 // UnkBattlemonSub: +0 counters word (disable/encore/taunt/perish...), +4 ids word

    const val BI_FEEDBACK_TASK = 0x0CL       // SysTask * of the button-press animation (NULL when idle)
    const val BI_UNK10_TASK = 0x10L
    const val BI_TOUCH_DISABLED = 0x68EL     // u8: 1 while the menu slides in
    const val BI_KEY_PRESSED = 0x6E0L        // u8: last choice made with buttons (cursor shown automatically next time)
    const val BC_COMMAND = 0x08L             // int: 5 = CONTROLLER_COMMAND_SELECTION_SCREEN_INPUT
    const val BC_BATTLER_STATE = 0x00L       // u8[4]: 1 command, 4 move, 6 target (waiting for the player)
    const val BC_COMMAND_SELECTION = 5
    val BATTLER_WAITING_STATES = setOf(1, 4, 6)
    const val BI_CUR_MENU_ID = 0x68BL        // s8 curMenuId (enum BattleMenuID include/constants/battle_menu.h) [medium]
    const val BI_MENU_CURSOR = 0x6DCL        // {u8 enabled; s8 y; s8 x}
    val BATTLE_MENUS = mapOf(
        -1 to "NONE", 0 to "NONE", 1 to "MAIN", 2 to "MAIN", 3 to "MAIN", 4 to "MAIN", 5 to "MAIN_FIGHT_ONLY",
        6 to "MAIN_FIGHT_ONLY", 7 to "MAIN", 8 to "MAIN", 9 to "PAL_PARK_INITIAL", 10 to "PAL_PARK", 11 to "FIGHT",
        12 to "TARGET", 13 to "YES_NO", 14 to "KEEP_FORGET_MOVE", 15 to "GIVE_UP_ON_MOVE", 16 to "SWITCH_OR_FLEE",
        17 to "SWITCH_OR_KEEP", 18 to "VS_RECORDER_PLAYBACK", 19 to "MENU_19", 20 to "MENU_20",
    )

    // BATTLE_TYPE_* include/constants/battle.h:133
    val BATTLE_TYPE_FLAGS = listOf(
        0 to "TRAINER", 1 to "DOUBLES", 2 to "LINK", 3 to "MULTI", 4 to "TAG", 5 to "SAFARI", 6 to "AI",
        7 to "FRONTIER", 8 to "ROAMER", 9 to "PAL_PARK", 10 to "TUTORIAL", 12 to "BUG_CONTEST",
    )

    // PLAYER_STATE_* include/constants/global_fieldmap.h:27
    val PLAYER_STATES = listOf(
        "WALKING", "CYCLING", "SURFING", "ROCKET", "USE_HM", "WATERING", "POKEATHLON", "FISHING", "POKETCH",
        "SAVING", "HEAL", "LADDER", "ROCKET_HEAL", "APRICORN_SHAKE", "ROCKET_SAVING",
    )

    val DIRECTIONS = listOf("north", "south", "west", "east")

    /** Sprite id of the invisible camera-focus object created by scripts (include/constants/sprites.h). */
    const val SPRITE_CAMERA_FOCUS = 8192

    /** Block order table from GetSubstruct (src/pokemon.c:3951): OFFSETS[(pid >> 13) & 31][block] = byte offset of block A/B/C/D. */
    val POKEMON_BLOCK_OFFSETS: Array<IntArray> = arrayOf(
        intArrayOf(0x00, 0x20, 0x40, 0x60), intArrayOf(0x00, 0x20, 0x60, 0x40), intArrayOf(0x00, 0x40, 0x20, 0x60),
        intArrayOf(0x00, 0x60, 0x20, 0x40), intArrayOf(0x00, 0x40, 0x60, 0x20), intArrayOf(0x00, 0x60, 0x40, 0x20),
        intArrayOf(0x20, 0x00, 0x40, 0x60), intArrayOf(0x20, 0x00, 0x60, 0x40), intArrayOf(0x40, 0x00, 0x20, 0x60),
        intArrayOf(0x60, 0x00, 0x20, 0x40), intArrayOf(0x40, 0x00, 0x60, 0x20), intArrayOf(0x60, 0x00, 0x40, 0x20),
        intArrayOf(0x20, 0x40, 0x00, 0x60), intArrayOf(0x20, 0x60, 0x00, 0x40), intArrayOf(0x40, 0x20, 0x00, 0x60),
        intArrayOf(0x60, 0x20, 0x00, 0x40), intArrayOf(0x40, 0x60, 0x00, 0x20), intArrayOf(0x60, 0x40, 0x00, 0x20),
        intArrayOf(0x20, 0x40, 0x60, 0x00), intArrayOf(0x20, 0x60, 0x40, 0x00), intArrayOf(0x40, 0x20, 0x60, 0x00),
        intArrayOf(0x60, 0x20, 0x40, 0x00), intArrayOf(0x40, 0x60, 0x20, 0x00), intArrayOf(0x60, 0x40, 0x20, 0x00),
        intArrayOf(0x00, 0x20, 0x40, 0x60), intArrayOf(0x00, 0x20, 0x60, 0x40), intArrayOf(0x00, 0x40, 0x20, 0x60),
        intArrayOf(0x00, 0x60, 0x20, 0x40), intArrayOf(0x00, 0x40, 0x60, 0x20), intArrayOf(0x00, 0x60, 0x40, 0x20),
        intArrayOf(0x20, 0x00, 0x40, 0x60), intArrayOf(0x20, 0x00, 0x60, 0x40),
    )
}

/**
 * Version-specific absolute addresses (ARM9 main binary globals and functions), one instance per ROM game code.
 *
 * Only [HEARTGOLD_US] ("IPKE") is filled in: it comes from `build/heartgold.us/main.elf.xMAP` of the local
 * pret/pokeheartgold build, which is byte-identical to the user's ROM (SHA1 4fcded0e2713dc03929845de631d0932ea2b5a37).
 *
 * To add SoulSilver USA ("IPGE"): build the decomp with `make soulsilver` (buildname soulsilver.us in config.mk),
 * then look every symbol named in the comments below up in `build/soulsilver.us/main.elf.xMAP`
 * (`grep -E "^\s+[0-9A-F]{8} [0-9A-F]{8} \.\w+ +<symbol>\s" main.elf.xMAP`) and add an instance to [ALL].
 * `_020FC604`/`_020FC614` are asm labels named after their HG address: take the address of the literal pool
 * entries of `sub_0205489C` instead. Other regions (e.g. EUR "IPKP", JPN "IPKJ") are not supported by the decomp and
 * would need signature scanning. The struct offsets in [HgssAddresses] are shared by all versions.
 */
data class HgssVersion(
    val gameCode: String,
    val displayName: String,

    // --- Globals (xMAP .bss) ---
    /** `static FieldSystem *sFieldSysPtr;` src/field_system.c:43. Set by Field_*_AppInit, NOT cleared when the field
     *  app exits (dangling after returning to title): only trusted when the main app is the field app. */
    val fieldSystemPtr: Long,
    /** `static SaveData *sSaveDataPtr;` src/save.c:15. Allocated at boot by SaveData_New() (src/main.c:62), never freed. */
    val saveDataPtr: Long,
    /** `struct UnkStruct_02111868 _02111868;` src/main.c:38: the top-level application manager (size 0x1C). */
    val mainAppState: Long,
    /** `struct System gSystem;` include/system.h:21 (size 0x78). */
    val gSystem: Long,
    /** `static PMiLoadedOverlay sOverlayRegions[3][8];` src/poke_overlay.c:19 (size 0xC0). */
    val loadedOverlays: Long,

    // --- Top-level / sub-application init functions (OverlayManager.template.init) ---
    val fnFieldContinueAppInit: Long,  // Field_Continue_AppInit (field_system.o)
    val fnFieldNewGameAppInit: Long,   // Field_NewGame_AppInit (field_system.o)
    val fnBattleInit: Long,            // Battle_Init (launch_application.o), template gOverlayTemplate_Battle
    val fnPartyMenuInit: Long,         // PartyMenuApp_Init (party_menu.o)
    val fnNamingScreenInit: Long,      // NamingScreenApp_Init (naming_screen.o)
    val fnSummaryInit: Long,           // PokemonSummary_Init (unk_02088288.o)
    val fnCheckSaveInit: Long,         // CheckSavedataApp_Init (check_savedata.o)
    val fnLegendaryCinematicInit: Long, // LegendaryCinematic_Init (unk_02097B78.o)
    val fnIntroMovieInit: Long,        // IntroMovie_Init (overlay intro_title = 60)
    val fnTitleScreenInit: Long,       // TitleScreen_Init (overlay intro_title = 60)
    val fnMainMenuInit: Long,          // MainMenuApp_Init (overlay 74, main_menu.o)

    // --- Field tasks (TaskManager.func) ---
    val fnTaskRunScripts: Long,        // Task_RunScripts (fieldmap.o) src/fieldmap.c:97
    val fnTaskStartMenu: Long,         // Task_StartMenu (start_menu.o)
    val fnTaskWildEncounter: Long,     // Task_WildEncounter (encounter.o)
    val fnTaskFollowMonInteract: Long, // Task_FollowMonInteract (overlay 2): talking to the following Pokémon

    // --- Native script waits (ScriptContext.native_ptr), src/scrcmd_c.c ---
    val fnScrWaitABPress: Long,        // sub_02041000 (ScrCmd_WaitABPress)
    val fnScrWaitButtonOrDelay: Long,  // sub_02041040 (ScrCmd_WaitButtonOrDelay)
    val fnScrWaitButton: Long,         // sub_02041074 (ScrCmd_WaitButton: A/B or d-pad turns)
    val fnScrWaitButtonOrDpad: Long,   // sub_020410F0 (ScrCmd_WaitButtonOrDpad)
    val fnScrYesNo: Long,              // sub_020416E4 (ScrCmd_YesNo)
    val fnScrMenuWait1: Long,          // sub_020418B4 (ScrCmd_067, multichoice)
    val fnScrMenuWait2: Long,          // sub_02041900 (ScrCmd_585, multichoice)
    val fnScrWaitMovement: Long,       // IsAllMovementFinished
    val fnScrWaitApp: Long,            // ScrNative_WaitApplication
    val fnScrWaitAppDestroy: Long,     // ScrNative_WaitApplication_DestroyTaskData
    val fnScrPauseTimer: Long,         // RunPauseTimer (ScrCmd_Wait)
    val fnScrWaitTextPrint: Long,      // ov01_021EF348 (field overlay 1, scrcmd_message.o): waits for the text printer
    /** `sTextPrinterTasks` (src/text.c): SysTask *[8], one per text printer id; NULL when the printer is done. */
    val textPrinterTasks: Long,
    /** u16 at `_021D1034+0xC` (unk_0200FA24.o): non-zero while a palette fade / screen wipe runs (IsPaletteFadeFinished). */
    val paletteFadeActive: Long,
    /** BrightnessData.transitionActive of the sub and main screens (master brightness fades). */
    val brightnessSubActive: Long,
    val brightnessMainActive: Long,
    val fnScrTouchYesNo: Long,         // sub_020477C0 (ScrCmd_GetMenuChoice): yes/no drawn on the touch screen (overlay 27)
    val fnScrTouchMenu: Long,          // sub_020478D0 (ScrCmd_MenuExec): multichoice drawn on the touch screen (overlay 27)

    // --- Terrain accessor tables assigned to FieldSystem.unk60 by sub_0205489C (asm/unk_02054648.s) ---
    /** {sub_02054774, sub_020547D8}: normal maps, attributes come from the field map loader (FieldSystem.unk2C). */
    val terrainAccessorLoader: Long,
    /** {sub_020547A4, sub_02054824}: TerrainAttributes (FieldSystem.terrainAttributes), Battle Tower-type maps. */
    val terrainAccessorTerrainAttributes: Long,
) {
    /** Sub-applications living in the main binary (ovy_id = -1), identified by their init function. */
    val appByInit: Map<Long, String> by lazy {
        mapOf(
            fnBattleInit to "battle",
            fnPartyMenuInit to "party_menu",
            fnNamingScreenInit to "naming_screen",
            fnSummaryInit to "pokemon_summary",
            fnLegendaryCinematicInit to "legendary_cinematic",
        )
    }

    companion object {
        /** Pokémon HeartGold Version (USA), game code IPKE, decomp build heartgold.us. */
        val HEARTGOLD_US = HgssVersion(
            gameCode = "IPKE",
            displayName = "Pokémon HeartGold (USA)",
            fieldSystemPtr = 0x021D4158L,
            saveDataPtr = 0x021D2228L,
            mainAppState = 0x02111868L,
            gSystem = 0x021D110CL,
            loadedOverlays = 0x021D0DF0L,
            fnFieldContinueAppInit = 0x0203DE74L,
            fnFieldNewGameAppInit = 0x0203DEA4L,
            fnBattleInit = 0x0203E3A8L,
            fnPartyMenuInit = 0x02078E30L,
            fnNamingScreenInit = 0x02082908L,
            fnSummaryInit = 0x02088298L,
            fnCheckSaveInit = 0x020921A4L,
            fnLegendaryCinematicInit = 0x02097B78L,
            fnIntroMovieInit = 0x021E6B68L,
            fnTitleScreenInit = 0x021E5900L,
            fnMainMenuInit = 0x02228920L,
            fnTaskRunScripts = 0x0203FF44L,
            fnTaskStartMenu = 0x0203BEF0L,
            fnTaskWildEncounter = 0x02050C18L,
            fnTaskFollowMonInteract = 0x02250110L,
            fnScrWaitABPress = 0x02041000L,
            fnScrWaitButtonOrDelay = 0x02041040L,
            fnScrWaitButton = 0x02041074L,
            fnScrWaitButtonOrDpad = 0x020410F0L,
            fnScrYesNo = 0x020416E4L,
            fnScrMenuWait1 = 0x020418B4L,
            fnScrMenuWait2 = 0x02041900L,
            fnScrWaitMovement = 0x02041CA8L,
            fnScrWaitApp = 0x020429F8L,
            fnScrWaitAppDestroy = 0x02042974L,
            fnScrPauseTimer = 0x020408D8L,
            fnScrWaitTextPrint = 0x021EF348L,
            textPrinterTasks = 0x021D1F74L,
            paletteFadeActive = 0x021D1040L,
            brightnessSubActive = 0x021D0ED0L,
            brightnessMainActive = 0x021D0EF0L,
            fnScrTouchYesNo = 0x020477C0L,
            fnScrTouchMenu = 0x020478D0L,
            terrainAccessorLoader = 0x020FC604L,
            terrainAccessorTerrainAttributes = 0x020FC614L,
        )

        /** Every supported ROM. Add SoulSilver USA ("IPGE") here once its xMAP addresses are filled in. */
        val ALL: List<HgssVersion> = listOf(HEARTGOLD_US)

        /** HG/SS game codes (4th char = region); used to tell "unsupported HGSS version" from "not HGSS". */
        val HGSS_GAME_CODE_PREFIXES = setOf("IPK", "IPG")

        fun forGameCode(code: String): HgssVersion? = ALL.firstOrNull { it.gameCode == code }

        /** Reads the game code from the cartridge header copy in main RAM (0x023FFE0C), e.g. "IPKE". */
        fun readGameCode(memory: dev.kotlinds.pokemonclient.Memory): String? {
            val a = HgssAddresses.ROM_HEADER_COPY + HgssAddresses.ROM_HEADER_GAME_CODE
            val chars = (0 until 4).map { memory.read8(a + it) and 0xFF }
            if (chars.any { it !in 0x20..0x7E }) return null
            return chars.map { it.toChar() }.joinToString("")
        }
    }
}
