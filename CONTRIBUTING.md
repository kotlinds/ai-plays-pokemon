# Contributing to ai-plays-pokemon

The game logic (reading the game, the typed state, the actions, the world and pathfinding) lives in the library
[pokemon-client](https://github.com/kotlinds/pokemon-client). **Its
[CONTRIBUTING.md](https://github.com/kotlinds/pokemon-client/blob/main/CONTRIBUTING.md) applies here too**: same
contract for every game, explore before implementing, one source and no legacy, typed and explained code, safety by
construction first, ids never from displayed text, actions that never press blindly, the playing agent decides, knowledge levels, no RAM writes,
tests that lock both sides of a change. This file only adds what is specific to the app.

## 1. Where code goes

- Anything about a game (state, screens, actions, world, data) goes in the library, not in the app. The app is a
  client of the library: the MCP server, the agent loops, the settings and the UI.
- The app consumes the library through mavenLocal while developing: after a library change, run
  `./gradlew publishToMavenLocal` in the library repo, then build or test the app. No composite builds /
  `includeBuild`. The `pokemon-client` version in `gradle/libs.versions.toml` stays a `-SNAPSHOT`; only the
  maintainer changes versions.

## 2. Options rather than choices

The app is a bench to compare how AIs play Pokémon. When there are several ways to do something (decision backend,
mode, timing, memory, knowledge level, an action's behaviour...), implement them as **options** that can be selected
and compared, instead of picking one. New variants go behind the existing settings (`PlayerSettings`,
`AgentOptions`, ...), and runs keep their statistics so they can be compared.

## 3. The MCP server

- The MCP exposes the library's contract unchanged: the same actions and the same state for every game, typed
  errors, ids that don't depend on the game's language.
- Every agent-visible change (a JSON field, a message, a behaviour) is deliberate and described in the change.
- Actions stop and hand control back to the agent when the situation changes; automatic behaviours are opt-in values
  (see the library's rules).
- Long actions keep the client informed (progress notifications) so clients don't time out.

## 4. Playing agents' notes

Agents playing through the MCP (development runs, races, benchmarks) keep notes: status, progress log, strategy, and
every problem with the exact call sent and the exact answer received. When an agent relies on something the MCP does
not expose (the decompilation, a screenshot), it writes it down: that is a missing feature to add. These notes, and the
agents' sessions, are the source for the next fixes.

## 5. Tests

`./gradlew test` (against the library published to mavenLocal). The library's suites must pass in their three
configurations first (see its CONTRIBUTING.md).
