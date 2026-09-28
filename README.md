# ai-plays-pokemon

Put an AI in front of Pokémon on a Nintendo DS emulator and watch it play, live (and take back the controls whenever
you want). The same game, the same "eyes" and the same controller for every AI, so they can be compared:

- **[Jev](https://docs.typesafe.ai)**, TypeSafe's "System One" decision model: it doesn't generate text, it picks one
  option from a closed list and returns a calibrated **probability for every option**, in ~150 ms;
- **LLMs** through [Koog](https://github.com/JetBrains/koog): OpenAI, Anthropic, OpenRouter (Gemini, Mistral,
  DeepSeek, Llama...) or a local model with Ollama. They explain each choice in one short sentence.

What every AI gets:

- **eyes**: we read the game's RAM (map around the player as text, position, dialogue, party, battle...) and describe
  it as JSON: no AI sees pixels;
- **hands**: the controller, nothing more: every decision is "which button do I press?" among the 12 DS buttons,
  exactly like a human player.

## Running it

Requirements: JDK 21 and a Pokémon HeartGold (USA) ROM that you dumped yourself. Then an API key for the AI you want
to try (or [Ollama](https://ollama.com) for a local model, e.g. `ollama pull gemma3:4b`).

```bash
./gradlew run --args="/path/to/Pokemon - HeartGold Version (USA).nds"
```

Without an argument, the app asks for the ROM with a file picker and remembers it. On first launch it downloads the
melonDS libretro core for your platform from the libretro buildbot.

In the window: the game on the left; on the right, choose the AI (Jev, or an LLM provider + model id), paste its API key
if needed, and **▶ Let … play** starts the autonomous loop. You can see exactly what the AI receives ("What the AI sees"
→ Show JSON), and for each decision Jev's probability for every button, or the LLM's short thought.

| Keys        | Action                     | Keys   | Action                         |
|-------------|----------------------------|--------|--------------------------------|
| Arrows      | D-pad                      | Space  | AI play / pause                |
| X / Z       | A / B                      | P      | Pause / resume the emulator    |
| S / A       | X / Y                      | F      | Fast forward                   |
| Q / W       | L / R                      | M      | Mute                           |
| Enter / ⌫   | Start / Select             | F1–F4  | Load state (Shift+F1–F4: save) |
| Mouse       | Touch screen               |        |                                |

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
| `LLM_PROVIDER` (`openai`, `anthropic`, `openrouter`, `ollama`) | `llm.provider`       | `ollama`                               |
| `LLM_MODEL`                                                 | `llm.<provider>.model`  | a default per provider                 |
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
  ┌──── emulator ────────▼─────┐                  ┌─────────▼──── agent ─────────────┐
  │ Emulator (interface)       │   main RAM       │ PokemonPlayer: observe → decide  │
  │  └ LibretroEmulator        │ ───────────────► │   → act → remember               │
  │     └ LibretroCore (JNA)   │                  │ ButtonPress · AgentMemory        │
  │        └ melonDS core      │ ◄─────────────── │ DecisionPrompt                   │
  └────────────────────────────┘   buttons        └───┬───────────────────────┬──────┘
                                                      │ RAM → Observation     │ ChoiceRequest → ChoiceResult
                                             ┌────────▼─────── game ───┐  ┌───▼──────── decision ───────────┐
                                             │ PokemonGame (interface) │  │ DecisionModel (interface)       │
                                             │  └ hgss: HeartGold      │  │  ├ jev: Jev (TypeSafe API)      │
                                             │    RAM reader           │  │  └ llm: LLMs through Koog       │
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
  (`LlmProvider` lists the providers, `LlmDecisionModel` asks for `{"thought", "choice"}` and validates the choice).
- **`agent`** — plays. `PokemonPlayer` loops: snapshot RAM → `Observation` → `DecisionPrompt` (objective + observation
  + recent presses, with all 12 `ButtonPress` options) → the model's choice → hold that button for 16 frames → record in
  `AgentMemory` what changed (position, map, mode), which is sent back next time since models are stateless.
- **`ui`** — Compose Desktop window. `AppController` owns the player and translates keyboard/mouse into emulator input.
- **`config`** — `AppConfig`, settings resolution.

### Why raw buttons?

The point of the project is to see what the model does when it faces the game, not what our code does. So we give it
what a human has: the screen (as text, read from RAM: the map around the player, dialogue, menus, battle, team) and a
controller. No pathfinding, no "talk to this person" macros, no filtering of "useless" buttons: exploring, understanding
menus and finding the way is the model's job. The only help is a short, factual history of its own presses (models are
stateless between requests, while a human remembers what they just did).

## Extending

- **Another game** (SoulSilver, Platinum...): implement `PokemonGame` and register its ROM code in `PokemonGames`.
- **Another emulator**: implement `Emulator` (or add a `LibretroCoreSpec` entry for another libretro core).
- **Another AI**: any model Koog supports is a provider + model id away (`LlmProvider`); anything else implements
  `DecisionModel`.
- **Better eyes**: the more faithfully the game reader describes the screen (menu options, text in apps...), the better
  the model can play, without us deciding for it.
