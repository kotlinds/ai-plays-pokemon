# Adding a game

How to make another Pokémon game playable by every AI of the project (Platinum, Diamond/Pearl, Black/White, later a
GBA game). The plans, the navigator, the action registry, the MCP server and the deciders are shared: a new game
only has to **read** itself into the common model, and (optionally) describe its maps and data from the ROM.

## What a game provides

`PokemonGame` (in `pokemon-client`):

| Member | What it does | Required |
|---|---|---|
| `state(memory)` | RAM → `GameState`: the `Screen` on display (with entries, cursor, topology), party, bag, battle, field, story | yes |
| `inputProbe` | which buttons the game has registered this frame (self-checking taps: hold until the game saw it) | yes |
| `world` | the ROM's maps as `Area`s: tiles (`TileKind`, heights), warps, signs, people, triggers | for movement actions |
| `data` | `GameData` from the ROM: species, moves, items, type chart, machines, text | for `lookup` and effectiveness |
| `scriptVariable(memory, id)` | read a script variable (active triggers, puzzles) | for triggers |
| `zoneName(id)` | display name of a map | for the map view |

Register the ROM's game code in the app's `PokemonGames`.

## The rules

- **Never write the game's RAM.** Read only; everything happens through buttons and the touch screen.
- **Ids, never text.** Entries are identified by what they stand for (`option:yes` by position in a menu whose
  order is fixed, `mon:<pid>.<otid>`, `move:<id>`, `item:<id>`, `person:<event id>`), so a French or Japanese ROM
  works the same. Labels are for display only.
- **Every screen that waits for a key says so.** A decoder returns a `Screen.Selectable`, a `PressToContinue`, a
  `Dialogue` (with `awaiting`) or an `Animation`. When a screen isn't decoded, `Unknown` (the agent then falls back to
  raw buttons and screenshots): measure which `Unknown` screens are hit most and decode those next.
- **Topology is exact.** For each entry and D-pad button, the entry reached by ONE press (or null). Verify it live:
  the navigator relies on it to move the cursor one verified tap at a time.
- **Several end screens.** An action may end on different screens depending on how it was triggered (buttons or
  touch): recipes accept every valid end.

## Method: be sure of every field

For each screen or field, cross-check three sources:

1. **The decompilation** (pret projects: `pokeheartgold`, `pokeplatinum`...): structures, offsets, task functions,
   scripts, constants. Prefer reading the C; fall back to the asm. Keep `file:line` references in the KDoc.
2. **The live game**, on the headless bench, from save states placed exactly in the situation:
   ```bash
   POKEMON_ROM=/path/rom.nds EMULATOR_CORE=desmume ./gradlew -q :pokemon-client-libretro:bench \
     "-PbenchArgs=<data>|<out>|load:menu.state|step:1|scr|tap:DOWN|scr|shot:after|ram:after"
   ```
   For a cursor: capture the RAM and a screenshot at option 1, 2, 3…, diff the RAM between the captures to find the
   byte that follows the cursor, then confirm its meaning in the decomp. Always `step:1` after `load:`.
   `BENCH_WINDOW=1` shows the run live. Use a separate data directory: never the player's saves.
3. **Tests**: freeze the situation as a sparse RAM fixture (`fixture:<name>` keeps only the bytes the decoders read)
   in `pokemon-client/src/jvmTest/resources/<game>/` and assert the decoded screen (ids, cursor, topology, touch
   points). Tests that need the ROM read `POKEMON_ROM` and are skipped without it.

Never compare values that depend on the time of day (the RTC follows the host clock).

## Steps for a new game

1. **Addresses**: a `<Game>Version` table per ROM code (main pointers: system, field, save data, battle), checked
   against the decomp's xMAP / symbol files. One address table per region.
2. **Reader**: the save arrays (party with Gen 4/5 encryption and checksums — handle torn reads —, bag, player,
   flags/vars), the field (position, height, facing, objects), the battle structures.
3. **Screen decoders**, family by family (text and yes/no, start menu, party and bag, battle menus, after-battle
   screens, keyboard/PC/shop/save, fly map...): each is a small object tried in order; reuse the common `Screen`
   types and only add an optional field at the end of a type when the model really lacks something.
4. **World and data from the ROM** with kotlinds (`nds-rom`, `nds-narc`, `nds-compression`): map headers, matrices,
   land data (collisions, behaviours, heights), zone events; personal / move / item NARCs, text banks.
5. **Story table** (optional, walkthrough knowledge): ordered steps with typed conditions on flags / vars / badges.
6. **Verify the shared recipes** on the new game with the bench (`act:{...}`): heal, buy, PC, battle actions, fly,
   fish, teach, go_to... A recipe only needs a per-game override when the game's screens really differ.
