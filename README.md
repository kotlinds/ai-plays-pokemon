# ai-plays-pokemon

Put an AI in front of Pokémon on a Nintendo DS emulator and watch it play, live (and take back the controls whenever
you want). The same game, the same "eyes" and the same controller for every AI, so they can be compared:

- **[Jev](https://docs.typesafe.ai)**, TypeSafe's "System One" decision model: it doesn't generate text, it picks one
  option from a closed list and returns a calibrated **probability for every option**, in ~150 ms;
- **LLMs** through [Koog](https://github.com/JetBrains/koog): OpenAI, Anthropic, OpenRouter (Gemini, Mistral,
  DeepSeek, Llama...) or a local model with Ollama. They reason before each choice and can keep notes;
- **Claude with your Claude Code login** (e.g. a Claude subscription, no API key), through `claude -p`;
- **any external agent through MCP**: the app runs an MCP server and the agent (e.g. Claude Code) plays with tools.

What every AI gets:

- **eyes**: we read the game's RAM (map around the player as text, position, dialogue, menus, party, battle...) and
  describe it as JSON: no AI sees pixels;
- **memory**: what each of its actions changed, where it has been, what it read, the map it explored, its own notes;
- **hands**, depending on the mode you pick:
  - **Pure**: the controller, nothing more: "which button do I press?" among the 12 DS buttons (+ wait);
  - **Assisted**: the buttons plus actions carried out by code ("walk to the stairs and take them", "talk to Mom",
    "choose YES", "go to Route 30", "surf across"): the AI still decides what to do, code does the walking (across
    floors and maps, with Surf, Cut, Strength, Waterfall... used on the way);
  - **Hybrid**: assisted actions picked by a fast decision model (Jev), with an LLM planner called when it hesitates,
    loops, or periodically, whose goal the fast model then follows.

Every variant is an option in the app (mode, knowledge level, timing, sequences, reasoning, notes, explored map...),
and each run records milestones (new places, story progress) with decisions, presses, time, tokens and cost in
`~/.ai-plays-pokemon/runs/`, so configurations can be compared.

## Running it

Requirements: JDK 21 and a Pokémon HeartGold (USA) ROM that you dumped yourself. Then an API key for the AI you want
to try (or [Ollama](https://ollama.com) for a local model, e.g. `ollama pull gemma4:26b`: a mixture-of-experts model, 4B parameters active, so fast enough on a laptop; small local models play poorly, cloud models play much better).

```bash
./gradlew run --args="/path/to/Pokemon - HeartGold Version (USA).nds"
```

Without an argument, the app asks for the ROM with a file picker and remembers it. On first launch it downloads the
DeSmuME libretro core for your platform from the libretro buildbot (melonDS is available too; both are pinned and
checked by SHA-256). In-game saves are converted between the cores' formats automatically.

**On a new computer** (macOS, Linux or Windows, x86-64 or Apple Silicon), either:

- **from source**: JDK 21, `git clone`, then `./gradlew run`. Gradle downloads the libraries from Maven Central
  (libretro-kmp included);
- **from a prebuilt jar**: Java 21 only, then `java -jar "AI Plays Pokemon-….jar"`. To build one jar that runs on
  every platform: `./gradlew packageUberJarForCurrentOS -Puniversal`, written to `build/compose/jars/` (the name
  carries the platform it was built on, but `-Puniversal` adds the other platforms' natives).

Bring the ROM yourself. Its file name names the save: `heartgold-us.nds` → `heartgold-us.sav`.

The emulator core is the only other file. It is downloaded on first launch from the libretro buildbot. Or copy it
yourself, built for the target platform, into the data directory's `cores/` folder. The data directory is
`~/.ai-plays-pokemon`, i.e. `%USERPROFILE%\.ai-plays-pokemon` on Windows.

| Platform | Core file (DeSmuME) | Buildbot folder |
|---|---|---|
| macOS (Apple Silicon / Intel) | `desmume_libretro.dylib` | `apple/osx/arm64` / `apple/osx/x86_64` |
| Linux x86-64 | `desmume_libretro.so` | `linux/x86_64` |
| Windows x86-64 | `desmume_libretro.dll` | `windows/x86_64` |

The URL is `https://buildbot.libretro.com/nightly/<folder>/latest/desmume_libretro.<ext>.zip`. No BIOS or firmware
is needed.

The core is checked against a pinned SHA-256 where one is pinned. Today that is only macOS Apple Silicon, the build
we tested; other platforms take whatever build is "latest". If the buildbot has moved on, the app stops with
"Unexpected … build". Then either:
- copy the core from a computer where it works;
- or test the new build and update the hash in `LibretroCoreSpec`.

To carry a game over, copy `saves/<rom name>.sav` from the data directory. It is converted for the core
automatically.

In the window: the game on the left; on the right, choose the AI (Jev, or an LLM provider + model id), paste its API key
if needed, pick the mode and options, and **▶ Let … play** starts the autonomous loop. You can see exactly what the AI
receives ("What the AI sees" → Show JSON), and for each decision Jev's probability for every option, or the LLM's
reasoning and note.

**Claude without an API key**: choose LLM → *Claude (Claude Code login)* with a model like `sonnet` or `opus`. Each
decision runs `claude -p` (a few seconds of overhead) and counts towards your Claude plan's usage.

**An external agent through MCP**: select the *MCP* tab (it starts the server; the port can be changed there), then
connect the agent, e.g. `claude mcp add --transport http pokemon http://localhost:3333/mcp`, and ask it to play. It
gets four tools, always the same:

- `get_state`: the screen (with the ids of its entries), messages and events since the last call, the team, the
  battle (with the estimated effectiveness of each move at the Pokédex knowledge level), a text map of the
  surroundings with the ids of exits, people and signs, and the actions possible now with their valid values;
- `act`: one typed action (`{"type": "buy", "items": [{"item": "Poke Ball", "quantity": 5}]}`, `{"type": "go_to",
  "map": "Route 30"}`, `{"type": "attack", "move": "move:85"}`, `pc`, `step`, `set_options`, `soft_reset`...),
  optionally followed by up to 8 more (`then`), a note to itself and a short reasoning; a refused action always says
  why (typed error). The answer is compact by default (only what changed: messages, screen, position, team or "team
  unchanged"); `detail: "full"` returns the whole state like `get_state`;
- `lookup`: game knowledge from the ROM (species, moves, items, types, learnsets, TMs), within the knowledge level;
- `screenshot`: both screens, for anything the state doesn't describe.

The agent keeps its own context between calls, and the game is frozen while it thinks.

| Keys        | Action                     | Keys   | Action                         |
|-------------|----------------------------|--------|--------------------------------|
| Arrows      | D-pad                      | Space  | AI play / pause                |
| X / Z       | A / B                      | P      | Pause / resume the emulator    |
| S / A       | X / Y                      | F      | Fast forward                   |
| Q / W       | L / R                      | M      | Mute                           |
| Enter / ⌫   | Start / Select             | F1–F4  | Load state (Shift+F1–F4: save) |
| Mouse       | Touch screen               | F12    | Save a RAM snapshot (debug)    |

You can play at the same time as the AI: the emulator merges both inputs.

### Configuration

Everything can be set in the app; settings are saved in `~/.ai-plays-pokemon/config.properties` (next to the downloaded
core, in-game saves and save states). Environment variables take precedence:

| Environment variable                                        | Config key              | Default                                |
|-------------------------------------------------------------|-------------------------|----------------------------------------|
| `POKEMON_ROM` (or first argument)                           | `rom`                   | asked with a file picker               |
| `DECISION_BACKEND` (`mcp` / `llm` / `jev`)                  | `backend`               | `mcp`                                  |
| `TYPESAFE_API_KEY`                                          | `typesafe.apiKey`       | entered in the app                     |
| `JEV_MODEL`                                                 | `typesafe.model`        | `jev-latest`                           |
| `JEV_ENDPOINT`                                              | `typesafe.endpoint`     | `https://api.typesafe.ai/v1/systemone` |
| `LLM_PROVIDER` (`openai`, `anthropic`, `openrouter`, `ollama`, `claude_code`) | `llm.provider` | `ollama`                  |
| `LLM_MODEL`                                                 | `llm.<provider>.model`  | a default per provider                 |
| `LLM_THINKING` (Ollama reasoning models think first)        | `llm.thinking`          | `false`                                |
| `MCP_PORT`                                                  | `mcp.port`              | `3333`                                 |
| `EMULATOR_CORE` (`desmume` / `melonds`)                     | `emulator.core`         | `desmume`                              |
| `START_MUTED`                                               | `start.muted`           | `false`                                |
| `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `OPENROUTER_API_KEY` | `llm.<provider>.apiKey` | entered in the app                     |
| `AI_PLAYS_POKEMON_DATA_DIR`                                 | —                       | `~/.ai-plays-pokemon`                  |

**Jev without the cloud.** Jev itself is only served by TypeSafe (no public weights). `JEV_ENDPOINT` can point to any
server implementing the same `POST /v1/systemone` protocol, e.g. an open-weight reproduction of Jev served locally.

## How it works

```
 ┌──────────────── app (me.nathanfallet.aiplayspokemon) ─────────────────┐
 │ ui: EmulatorScreen · ControlPanel        mcp: GameMcpServer (4 tools)  │
 │ agent: GameSession · PokemonPlayer       decision: Jev · LLMs · claude │
 │ emulator: ConsoleHost (one console thread, drivers: idle/human/agent)  │
 │   └ LibretroConsole ── libretro-kmp ── DeSmuME / melonDS core          │
 └───────────────┬───────────────────────────────────────────────────────┘
                 │ ConsolePort (step, read RAM, frames, save states)
 ┌───────────────▼──── pokemon-client (dev.kotlinds.pokemonclient) ──────┐
 │ state: GameState, sealed Screen (entries, cursor, topology), events   │
 │ runtime: ActionScope (self-checking taps), Recorder                    │
 │ actions: Navigator (verified cursor moves), typed GameActions, plans, │
 │          ActionRegistry (schema, availability, typed errors)           │
 │ world: Area, Pathfinder (levels, ledges, surf, triggers)  view: MapView│
 │ data: GameData, Lookup, KnowledgeLevel                                 │
 │ hgss: HeartGold/SoulSilver — RAM decoders per screen family, ROM maps │
 │       and data (kotlinds), story table                                 │
 └────────────────────────────────────────────────────────────────────────┘
```

Three modules:

- **`pokemon-client`** (Kotlin Multiplatform, as much as possible in `commonMain`) is the library: it knows Pokémon,
  not emulators. A game reads the RAM into one common, typed model: `GameState` with a sealed `Screen` (every menu
  has its entries with **stable, language-independent ids** like `option:yes`, `mon:8dd175d1.76f3a6fb`, `move:85`,
  its cursor and the exact D-pad topology). Actions are typed (`GameAction`) and carried out by **plans** that never
  press blindly: the `Navigator` reads the cursor, moves it one verified tap at a time and confirms only on the
  target (3 corrections at most, then an explicit error). Movement uses the maps read from the **ROM** (tiles,
  heights, warps, events) with the live people and the game's script variables on top. Nothing is ever written to
  the game's RAM: everything goes through buttons and the touch screen, like a player.
- **the app** runs the emulator through [libretro-kmp](https://github.com/kotlinds/libretro-kmp) (a libretro core loaded in process, on Maven Central) on
  a single console thread that drives time (agents get a lease; the human can always take over), and connects the
  deciders: our loop (`PokemonPlayer` with Jev, LLMs through Koog, or `claude -p`) or an external agent through MCP.

- **`pokemon-client-libretro`** (JVM) plugs the library into a libretro core: `LibretroConsole` (the `ConsolePort`
  over libretro-kmp), save formats, and a headless **bench** (`dev.kotlinds.pokemonclient.libretro.bench.BenchKt`,
  see its KDoc) that runs commands and actions without the app (`BENCH_WINDOW=1` shows it live) and writes sparse RAM
  fixtures for the unit tests. A new game can be brought up with the library and this module only:

```bash
POKEMON_ROM=/path/to/rom.nds EMULATOR_CORE=desmume ./gradlew -q :pokemon-client-libretro:bench \
  "-PbenchArgs=<data dir>|<out dir>|load:my.state|step:1|act:{\"type\":\"heal\"}|shot:after"
```

Tests: `./gradlew :pokemon-client:jvmTest :pokemon-client-libretro:test test` (with `POKEMON_ROM` set, the ROM tests run too); coverage with
`./gradlew koverHtmlReport`.

### Pure, assisted, hybrid: why several modes?

The point of the project is to see what an AI does when it faces the game. Pure mode gives it exactly what a human
has: the screen (as text) and a controller, no pathfinding, no macros. But every project that finished a Pokémon
game with an LLM (Claude/Gemini/GPT Plays Pokémon, the PokéAgent challenge) gave it navigation tools, and a decision
model like Jev needs options that already carry meaning. Assisted mode is that: code does the walking and the cursor
moves, the AI still decides where to go and what to do. Hybrid mode adds a slower planner for when the fast model is
unsure. Having the three side by side, with milestones recorded for each run, is what lets us measure the difference.

## Extending

- **Another game** (Platinum, Black/White, a GBA game later...), see [docs/adding-a-game.md](docs/adding-a-game.md): implement `PokemonGame` (RAM → `GameState`, screen
  decoders, optionally `world` and `data` from the ROM) and register its ROM code in `PokemonGames`; the plans, the
  navigator, the registry, the MCP server and every decider work unchanged.
- **Another emulator**: implement `ConsolePort` (or add a `LibretroCoreSpec` entry for another libretro core).
- **Another AI**: any model Koog supports is a provider + model id away (`LlmProvider`); anything else implements
  `DecisionModel`.
- **Better eyes**: the more faithfully the game reader describes the screen (menu options, text in apps...), the better
  the model can play, without us deciding for it.
