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
    "choose YES", "explore north"): the AI still decides what to do, code does the walking;
  - **Hybrid**: assisted actions picked by a fast decision model (Jev), with an LLM planner called when it hesitates,
    loops, or periodically, whose goal the fast model then follows.

Every variant is an option in the app (mode, timing, sequences, reasoning, notes, explored map, story goal assist...),
and each run records milestones (new places, story progress) with decisions, presses, time, tokens and cost in
`~/.ai-plays-pokemon/runs/`, so configurations can be compared.

## Running it

Requirements: JDK 21 and a Pokémon HeartGold (USA) ROM that you dumped yourself. Then an API key for the AI you want
to try (or [Ollama](https://ollama.com) for a local model, e.g. `ollama pull gemma4:26b`: a mixture-of-experts model, 4B parameters active, so fast enough on a laptop; small local models play poorly, cloud models play much better).

```bash
./gradlew run --args="/path/to/Pokemon - HeartGold Version (USA).nds"
```

Without an argument, the app asks for the ROM with a file picker and remembers it. On first launch it downloads the
melonDS libretro core for your platform from the libretro buildbot.

In the window: the game on the left; on the right, choose the AI (Jev, or an LLM provider + model id), paste its API key
if needed, pick the mode and options, and **▶ Let … play** starts the autonomous loop. You can see exactly what the AI
receives ("What the AI sees" → Show JSON), and for each decision Jev's probability for every option, or the LLM's
reasoning and note.

**Claude without an API key**: choose LLM → *Claude (Claude Code login)* with a model like `sonnet` or `opus`. Each
decision runs `claude -p` (a few seconds of overhead) and counts towards your Claude plan's usage.

**An external agent through MCP**: select the *MCP* tab (it starts the server), then connect the agent, e.g.
`claude mcp add --transport http pokemon http://localhost:3333/mcp`, and ask it to play. It gets two tools: `get_state`
(screen, memory, options of the current mode) and `act` (carry out an option, optionally a short sequence). The agent
keeps its own context between calls, and the game is frozen while it thinks.

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
| `DECISION_BACKEND` (`jev` / `llm`)                          | `backend`               | `jev`                                  |
| `TYPESAFE_API_KEY`                                          | `typesafe.apiKey`       | entered in the app                     |
| `JEV_MODEL`                                                 | `typesafe.model`        | `jev-latest`                           |
| `JEV_ENDPOINT`                                              | `typesafe.endpoint`     | `https://api.typesafe.ai/v1/systemone` |
| `LLM_PROVIDER` (`openai`, `anthropic`, `openrouter`, `ollama`, `claude_code`) | `llm.provider` | `ollama`                  |
| `LLM_MODEL`                                                 | `llm.<provider>.model`  | a default per provider                 |
| `LLM_THINKING` (Ollama reasoning models think first)        | `llm.thinking`          | `false`                                |
| `MCP_PORT`                                                  | `mcp.port`              | `3333`                                 |
| `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `OPENROUTER_API_KEY` | `llm.<provider>.apiKey` | entered in the app                     |
| `AI_PLAYS_POKEMON_DATA_DIR`                                 | —                       | `~/.ai-plays-pokemon`                  |

**Jev without the cloud.** Jev itself is only served by TypeSafe (no public weights). `JEV_ENDPOINT` can point to any
server implementing the same `POST /v1/systemone` protocol, e.g. an open-weight reproduction of Jev served locally.

## How it works

```
                 ┌──────────────────────── ui ─────────────────────────┐
                 │  EmulatorScreen · ControlPanel · keyboard → buttons │
                 └───────┬──────────────────────────────────┬──────────┘
                         │ frames, status                   │ play / pause, state
  ┌──── emulator ────────▼─────┐                  ┌─────────▼──── agent ─────────────┐   ┌──── mcp ─────────┐
  │ Emulator (interface)       │   main RAM       │ AgentSession: prepare → act      │◄──│ GameMcpServer    │
  │  └ LibretroEmulator        │ ───────────────► │  GameController · AgentMemory    │   │ (external agent) │
  │     └ LibretroCore (JNA)   │                  │  actions: AgentAction (sealed)   │   └──────────────────┘
  │        └ melonDS core      │ ◄─────────────── │ PokemonPlayer: the decision loop │
  └────────────────────────────┘   buttons        └───┬───────────────────────┬──────┘
                                                      │ RAM → Observation     │ ChoiceRequest → ChoiceResult
                                             ┌────────▼─────── game ───┐  ┌───▼──────── decision ───────────┐
                                             │ PokemonGame (interface) │  │ DecisionModel (interface)       │
                                             │  └ hgss: HeartGold      │  │  ├ jev: Jev (TypeSafe API)      │
                                             │    RAM reader           │  │  ├ llm: LLMs through Koog       │
                                             │                         │  │  └ claudecode: `claude -p`      │
                                             └─────────────────────────┘  └─────────────────────────────────┘
```

Each package has one job and depends only on the interfaces of the others:

- **`emulator`** — runs a console. `Emulator` is the interface the rest of the app uses (frames, buttons, touch, RAM
  snapshots, save states). `LibretroEmulator` implements it by loading a [libretro](https://www.libretro.com) core in
  process: libretro is a small C API that emulators ("cores") implement, so we get melonDS (and could get DeSmuME,
  mGBA...) without writing an emulator. `LibretroCore` is the thin JNA layer over the C API; `LibretroCoreSpec` lists the
  cores we know (download URL, options).
- **`game`** — understands a specific Pokémon game. `PokemonGame.observe(memory)` turns a RAM snapshot into an
  `Observation`: a `GameMode` (overworld, dialogue, menu, battle...), the player's `Location`, and a JSON `state` for
  the AI. `PokemonGames` picks the reader from the ROM's game code (read with
  [kotlinds](https://github.com/kotlinds/kotlinds)). `hgss/` is the HeartGold/SoulSilver reader, built from the
  [pret/pokeheartgold](https://github.com/pret/pokeheartgold) decompilation (addresses per ROM version in
  `HgssVersion`, structures, Gen 4 party encryption, name tables in `resources/hgss`). This layer is meant to become a
  standalone library later (a common interface to read Pokémon games' RAM, one implementation per game).
- **`decision`** — the brain, behind `DecisionModel.choose(ChoiceRequest): ChoiceResult`. `jev/` is the TypeSafe API
  client (`JevClient`, request/response models) and its adapter; `llm/` plays with any LLM through Koog
  (`LlmProvider` lists the providers; `LlmAnswerFormat` is the shared answer format: reasoning, note, sequence and a
  strictly validated choice); `claudecode/` runs Claude through the Claude Code CLI.
- **`agent`** — plays. `AgentSession` is the game as seen by any AI: `prepare()` waits until the game expects input,
  observes it and lists the options of the current mode (`ActionCatalog`: buttons, plus in assisted modes typed
  actions like `TakeExit`, `Interact`, `Explore`, `ChooseOption`, carried out with `Pathfinder`/`Navigation`), and
  `act()` carries a choice (and sequence) out through the `GameController` (presses held until the game reacts) and
  records in `AgentMemory` exactly what changed. `PokemonPlayer` is our decision loop on top of it (with the hybrid
  planner and `RunStats` milestones); `PlayerSettings` holds every experiment option.
- **`mcp`** — `GameMcpServer` exposes the same `AgentSession` as MCP tools for external agents.
- **`ui`** — Compose Desktop window. `AppController` owns the player and translates keyboard/mouse into emulator input.
- **`config`** — `AppConfig`, settings resolution.

### Pure, assisted, hybrid: why several modes?

The point of the project is to see what an AI does when it faces the game. Pure mode gives it exactly what a human
has: the screen (as text) and a controller, no pathfinding, no macros. But every project that finished a Pokémon
game with an LLM (Claude/Gemini/GPT Plays Pokémon, the PokéAgent challenge) gave it navigation tools, and a decision
model like Jev needs options that already carry meaning. Assisted mode is that: code does the walking and the cursor
moves, the AI still decides where to go and what to do. Hybrid mode adds a slower planner for when the fast model is
unsure. Having the three side by side, with milestones recorded for each run, is what lets us measure the difference.

## Extending

- **Another game** (SoulSilver, Platinum...): implement `PokemonGame` and register its ROM code in `PokemonGames`.
- **Another emulator**: implement `Emulator` (or add a `LibretroCoreSpec` entry for another libretro core).
- **Another AI**: any model Koog supports is a provider + model id away (`LlmProvider`); anything else implements
  `DecisionModel`.
- **Better eyes**: the more faithfully the game reader describes the screen (menu options, text in apps...), the better
  the model can play, without us deciding for it.
