# Adding a game

How to make another Pokémon game playable by every AI of the project (Platinum, Diamond/Pearl, Black/White, later a
GBA game). The plans, the navigator, the action registry, the MCP server and the deciders are shared: a new game
only has to **read** itself into the common model, and (optionally) describe its maps and data from the ROM.

Validated with Pokémon Platinum (USA), `platinum/`: intro, main menu, naming keyboard, field messages, position and
the current map, played end to end with the generic actions only (`press`, `advance_dialogue`, `choose`,
`enter_text`, `go_to`, `soft_reset`), without one line of Platinum-specific action code.

## Layers

| Layer | Package | What lives there |
|---|---|---|
| Generic contract | `PokemonGame`, `state/`, `actions/`, `world/`, `view/`, `runtime/` | game- and generation-independent: the `Screen` model, actions, navigator, world graph, pathfinding |
| Generation layer | `gen4/` | what the Gen 4 engine shares (DP/Pt/HGSS): safe RAM reads (`Gen4Memory`), engine struct offsets (`Gen4Structs`: overlay manager, `gSystem`, `String`, `SysTask`, text printer, script manager, map objects, location), text and charmap (`Gen4Text`), message files (`Gen4MessageFile`), input probe, the naming keyboard (`Gen4NamingKeyboard`), map matrix / land data / BDHC / zone events, `Gen4Game` |
| Game | `hgss/`, `platinum/` | the address table per ROM, the screens that differ, the map header layout, tile behaviours, story |

A Gen 5 or GBA game gets its own generation layer (`gen5/`, `gen3/`): don't put Gen 4 things in the generic
contract, and don't put generic things in `gen4/`.

## What a game provides

`PokemonGame` (in `pokemon-client`):

| Member | What it does | Required |
|---|---|---|
| `state(memory)` | RAM → `GameState`: the `Screen` on display (with entries, cursor, topology), party, bag, battle, field, story | yes |
| `inputProbe` | which buttons the game has registered this frame (self-checking taps: hold until the game saw it) | yes (`Gen4Game` gives it) |
| `observe(memory)` | the older agent view; defaults to `Observation.of(state)` | no |
| `world` | the ROM's maps as `Area`s: tiles (`TileKind`, heights), warps, signs, people, triggers | for movement actions |
| `data` | `GameData` from the ROM: species, moves, items, type chart, machines, text | for `lookup` and effectiveness |
| `scriptVariable(memory, id)` | read a script variable (active triggers, puzzles) | for triggers |
| `zoneName(id)` | display name of a map | for the map view |

Register the ROM's game code in `PokemonGames` (`pokemon-client`, not the app): the app, the MCP server and the
bench all detect the game from the ROM (`POKEMON_ROM=roms/<game>.nds`).

## The rules

- **Never write the game's RAM.** Read only; everything happens through buttons and the touch screen.
- **Ids, never text.** Entries are identified by what they stand for (`option:yes` by position in a menu whose
  order is fixed, the game's own choice index or option enum, `mon:<pid>.<otid>`, `move:<id>`, `item:<id>`,
  `person:<event id>`), so a French or Japanese ROM works the same. Labels are for display only (read them from
  RAM or the ROM's text banks, with an English fallback). Reuse the ids another game uses for the same thing
  (`option:continue`, `option:new_game`, `option:0..n` for the professor's info menu and gender).
- **Every screen that waits for a key says so.** A decoder returns a `Screen.Selectable`, a `PressToContinue`, a
  `Dialogue` (with `awaiting`) or an `Animation`. When a screen isn't decoded, `Unknown` (the agent then falls back to
  raw buttons and screenshots): measure which `Unknown` screens are hit most and decode those next. Put what you know
  in the hint (Platinum: the application's `main` function, the field task function): it tells what to decode next.
  A screen with nothing to press (a fade, a transitional application, a map change) is an `Animation` or an
  `Intro(..., ANIMATION)`, never an `Unknown` waiting for input.
- **Topology is exact.** For each entry and D-pad button, the entry reached by ONE press (or null). Verify it live
  with the bench's `walk:` command: the navigator relies on it to move the cursor one verified tap at a time.
- **Hidden cursors and touch-only screens.** `Cursor.Hidden` means "the first D-pad press only shows the cursor"
  (Platinum's naming keyboard). A screen driven by touch only (Platinum's intro YES / NO and Poké Ball, where a key
  makes the game say "use the touch screen") has a hidden cursor, a topology where nothing moves, and entries with a
  `touch` point: the navigator then touches the entry instead of pressing a key.
- **Several end screens.** An action may end on different screens depending on how it was triggered (buttons or
  touch): recipes accept every valid end.

## Method: be sure of every field

For each screen or field, cross-check three sources:

1. **The decompilation** (pret projects: `pokeheartgold`, `pokeplatinum`...): structures, offsets, task functions,
   scripts, constants. Prefer reading the C; fall back to the asm. Keep `file:line` references in the KDoc.
   - **Check that the decomp matches the ROM first.** Compare the ARM9 binary and every overlay of the ROM with the
     decomp's build (the FAT entries of the overlays, the ARM9 bytes): a whole-file SHA1 can differ (Platinum: 5 data
     files) while the code is byte-identical, which is what makes the xMAP addresses valid.
   - **Absolute addresses** from the build's xMAP (`build/main.nef.xMAP` for Platinum,
     `build/heartgold.us/main.elf.xMAP` for HGSS). Overlay code shares addresses between overlays (Platinum:
     `TitleScreen_Init`, `RowanIntro_Init` and `GameStartRowanIntro_Init` are all 0x021D0D80): identify a running
     application by its template's `main` function, or by the overlay id plus the function.
   - **Struct offsets: compile them, don't count them.** Append `const int __offs[] = { offsetof(T, f), ... };` to a
     copy of the C file, compile it with the decomp's own compiler and flags (`compile_commands.json`), and read the
     array from the object (`readelf -x`). Hand counting gets bitfields, unions and enum sizes wrong (Platinum's
     opening field named `unk_2A8` is at 0x2AC).
2. **The live game**, on the headless bench, from save states placed exactly in the situation:
   ```bash
   POKEMON_ROM=/path/rom.nds EMULATOR_CORE=desmume ./gradlew -q :pokemon-client-libretro:bench \
     "-PbenchArgs=<data>|<out>|load:menu.state|step:1|scr|tap:DOWN|scr|shot:after|ram:after"
   ```
   Data and out directories must be absolute paths (the core is loaded from `<data>/cores`). For a cursor: capture
   the RAM and a screenshot at option 1, 2, 3…, diff the RAM between the captures to find the byte that follows the
   cursor, then confirm its meaning in the decomp. Always `step:1` after `load:`. `trace:<n>` prints every change of
   the decoded screen (find the frames where input is really read). `BENCH_WINDOW=1` shows the run live. Use a
   separate data directory: never the player's saves. A new game has no save state: make your own with `save:` at
   every interesting screen, and an in-game save (raw presses) to test what only exists with a save (CONTINUE).
3. **Tests**: freeze the situation as a sparse RAM fixture (`fixture:<name>` keeps only the bytes the decoders read)
   in `pokemon-client/src/jvmTest/resources/<game>/` and assert the decoded screen (ids, cursor, topology, touch
   points). **Capture fixtures with the final decoder**: a fixture holds only what the decoder read when it was taken,
   so a decoder reading one more field needs a new capture. Tests that need the ROM read an environment variable
   (`POKEMON_ROM` for HGSS, `PLATINUM_ROM` for Platinum), are skipped without it, and the variable is declared as a
   test input in `pokemon-client/build.gradle.kts` (else the build cache mixes runs with and without the ROM).

Never compare values that depend on the time of day (the RTC follows the host clock).

## Steps for a new game

1. **Detection**: a `<Game>Version` table per ROM code (main pointers: top-level application, `gSystem`, field
   system, save data, screen fade, text printers; the application `main` functions), registered in `PokemonGames`.
   A `<Game>Game` extending the generation's base (`Gen4Game`). From here the app and the bench start the game; every
   screen is `Unknown` until decoded.
2. **The intro**, the first thing every agent meets: opening movie (when can it be skipped?), title screen (when are
   keys read?), the transitional applications between them (loading), the main menu (`MenuKind.MAIN_MENU`,
   `option:continue` for `soft_reset`; Platinum skips it when there is no save), the professor's speech (`Dialogue`
   with `TextSource.INTRO`, awaiting input at the text printer's page breaks), its menus and yes / no, the naming
   keyboard (the common `Keyboard`, so `enter_text` works), until the overworld.
3. **Field basics**: position (zone, x, y, height, facing, moving), `Overworld` waiting for input (no field task,
   map running, not paused, not fading), map changes as animations (the transition and warp tasks), scripts'
   messages (`Dialogue` FIELD, and SIGN for signpost windows: they are not the message box).
4. **World from the ROM** with kotlinds (`nds-rom`, `nds-narc`, `nds-compression`): map headers (their layout differs
   per game), matrices, land data (collisions, behaviours, BDHC heights), zone events — the Gen 4 formats are in
   `gen4/`, only the header table, the NARC paths and the tile behaviour table are per game. Check the map view
   against a screenshot and `go_to` a warp.
5. **Reader**: the save arrays (party with Gen 4/5 encryption and checksums — handle torn reads —, bag, player,
   flags/vars), the battle structures.
6. **Screen decoders**, family by family (start menu, party and bag, battle menus, after-battle screens,
   PC/shop/save, fly map...): each is a small object tried in order; reuse the common `Screen` types and only add an
   optional field at the end of a type when the model really lacks something.
7. **Story table** (optional, walkthrough knowledge): ordered steps with typed conditions on flags / vars / badges.
8. **Verify the shared recipes** on the new game with the bench (`act:{...}`): heal, buy, PC, battle actions, fly,
   fish, teach, go_to... A recipe only needs a per-game override when the game's screens really differ.

## What the shared code still assumes (to know before a non-Gen 4 game)

- `FieldState.height` is in Gen 4 map-object units (`MovePlans.HEIGHT_UNITS = 8` converts it to BDHC heights).
- `PlayerInfo.badgeIds`, `StoryCondition`, radio / Pokégear models are documented with HGSS numbering (the types are
  generic, the meaning of the ids is per game).
- The bench's `raw`, `rawmon`, `box`, `where`, `watch`, `fish`, `world` commands and the music-during-pauses checks
  read HGSS structures: they refuse other games.
- Rod item ids (`CommonActions.RODS`) and `bicycleItem` are the Gen 4 ids.
