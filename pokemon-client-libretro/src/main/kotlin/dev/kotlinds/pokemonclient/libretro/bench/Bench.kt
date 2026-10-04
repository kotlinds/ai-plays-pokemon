package dev.kotlinds.pokemonclient.libretro.bench

import dev.kotlinds.pokemonclient.view.MapView
import dev.kotlinds.pokemonclient.hgss.HgssWorldSource
import dev.kotlinds.pokemonclient.hgss.HgssLoadedMap
import dev.kotlinds.pokemonclient.hgss.HgssData
import dev.kotlinds.NdsRom
import dev.kotlinds.pokemonclient.hgss.HgssVersion
import dev.kotlinds.pokemonclient.hgss.HgssReader
import dev.kotlinds.pokemonclient.hgss.HgssMemory
import dev.kotlinds.pokemonclient.hgss.HgssGame
import dev.kotlinds.pokemonclient.hgss.HgssFishing
import dev.kotlinds.pokemonclient.hgss.FishingState
import dev.kotlinds.pokemonclient.console.InputFrame
import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.RamMemory
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.Frame
import dev.kotlinds.pokemonclient.console.MemoryRegion
import dev.kotlinds.pokemonclient.console.TouchPoint
import dev.kotlinds.pokemonclient.actions.ActionException
import dev.kotlinds.pokemonclient.actions.ActionMode
import dev.kotlinds.pokemonclient.actions.ActionRegistry
import dev.kotlinds.pokemonclient.actions.Navigator
import dev.kotlinds.pokemonclient.runtime.ActionScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import dev.kotlinds.pokemonclient.runtime.kind
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.libretro.LibretroConsole
import dev.kotlinds.pokemonclient.libretro.LibretroCoreSpec
import dev.kotlinds.pokemonclient.PokemonGames
import java.awt.image.BufferedImage
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import javax.imageio.ImageIO
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/**
 * Development bench: drives the game headless (no window, no sound) from a script, to check decoders and actions
 * on real screens, take screenshots and capture RAM fixtures for tests.
 *
 * ```
 * ./gradlew :pokemon-client-libretro:bench -PbenchArgs="<data dir>|<out dir>|<command>|..."
 * ```
 * Environment: `POKEMON_ROM` (ROM path), `EMULATOR_CORE` (desmume / melonds), `BENCH_WINDOW=1` (watch it live). The data directory holds `cores/`,
 * `saves/` (the in-game save is loaded at boot) and `system/`, like the app's.
 *
 * Commands (one per argument):
 * - `boot[:frames]`: from power-on, presses A until the overworld (continues the saved game);
 * - `load:<file>` / `save:<file>`: loads / saves a save state (relative to the out dir);
 * - `step:<n>`: emulates n frames; `tap:<BUTTON>[x<n>]`: self-checking taps; `hold:<B1+B2...>:<n>`: holds buttons n frames; `touch:<x>,<y>`: touches the bottom screen;
 * - `until:<kind>:<frames>`: steps until the screen kind starts with `kind` (e.g. `overworld`, `dialogue`);
 * - `shot:<name>`: PNG of both screens; `state`: prints the decoded GameState; `screen`: prints the screen only;
 *   `puzzle`: prints the position and the map puzzle (switches, shutters, teleports);
 * - `act:<json>`: executes a typed action through the action registry (e.g. `act:{"type":"choose","entry":"option:6"}`);
 *   `actions`: lists the actions available now;
 * - `log`: prints the events recorded since the previous `log` (texts shown, screen changes, level ups...);
 * - `ram:<name>`: writes the full main RAM; `fixture:<name>`: writes a sparse RAM fixture (only the bytes the
 *   decoders read) for unit tests.
 * - `watch:on|off`: after every frame, prints the raw party reading and what the state shows when they change
 *   (`BENCH_WATCH_HEX=1` adds the raw bytes; `BENCH_WATCH_FIXTURE=<prefix>` [+ `BENCH_WATCH_FIXTURE_SLOT=n`] saves
 *   fixtures of frames where a party slot is mid-rewrite); `rawmon`: raw party bytes; `box:<n>`: PC box n;
 * - `pace:<n>`: walks one tile left then right, n times, until the phone rings (or the overworld is left);
 * - `record:on|off`: runs the app's Recorder on every frame; `events`: prints its events (screen changes left out).
 * - `msgtrace:<n>` / `mashtrace:<n>`: steps n frames (mashing A one frame in four) printing every change of the battle
 *   message and ball shakes; `truth:on` records the battle message of every frame, `truth:check` compares it with
 *   the recorder's battle messages (what was shown but not recorded), `truth:dump:<name>` writes it to `<name>.trace`
 *   (run-length encoded in tests); `autobattle:<n>[,move:<id>]` plays n decisions of a battle like an agent and checks.
 */
fun main(args: Array<String>) {
    require(args.size >= 3) { "usage: <data dir>|<out dir>|<command>|..." }
    val data = Path.of(args[0])
    val out = Files.createDirectories(Path.of(args[1]))
    val rom = Path.of(System.getenv("POKEMON_ROM") ?: error("POKEMON_ROM is not set"))
    val game = PokemonGames.detect(rom.readBytes()) ?: error("Unsupported ROM $rom")
    // BENCH_WINDOW=1 opens a live, muted window to watch the commands run at the console's speed.
    val viewer = if (System.getenv("BENCH_WINDOW") == "1") BenchViewer("Bench — ${rom.fileName}") else null
    val console = LibretroConsole(
        LibretroCoreSpec.forRom(rom, System.getenv("EMULATOR_CORE")), rom, data,
        onVideo = { viewer?.show(it) }, onAudio = { _, _ -> },
    )
    val bench = Bench(console, game, out, rom)
    try {
        args.drop(2).forEach { command ->
            println("> $command")
            bench.run(command)
        }
    } finally {
        console.close()
        viewer?.close()
    }
}

private class Bench(private val console: LibretroConsole, private val game: PokemonGame, private val out: Path, private val romPath: Path) {

    /** Records every event (text shown, screen changes...) like the app does, printed by the `log` command. */
    private val recorder = dev.kotlinds.pokemonclient.runtime.Recorder(game)
    private var logCursor = 0L

    /** `watch:on` prints, after every emulated frame, the raw party reading and what the state exposes when they change. */
    private var watching = false
    private var lastWatch: String? = null
    /** `autobattle`: every battle message read on every frame (the ground truth the recorder is checked against). */
    private var truth: MutableList<String>? = null

    private val scope: ActionScope = ActionScope(console, game.inputProbe, onFrame = {
        if (watching) watchParty()
        truth?.let { seen ->
            val message = runCatching { game.state(scope.memory()).battle?.message }.getOrNull()
            truthFrames += message
            if (message != null && message != seen.lastOrNull()) {
                // A half-written read is a prefix of the next one: keep the complete text only.
                if (seen.isNotEmpty() && message.startsWith(seen.last())) seen[seen.size - 1] = message else seen += message
            }
        }
        recorder.onFrame(console.frame) { scope.memory() }
    })
    private val registry = ActionRegistry.of()

    /** Walks back and forth (one tile left, one right) up to [times] times, until the phone rings or the overworld is left. */
    private fun pace(times: Int) {
        repeat(times) { i ->
            for (button in listOf(Button.LEFT, Button.RIGHT)) {
                scope.step(16, InputFrame(setOf(button)))
                scope.step(4)
                val screen = game.state(scope.memory()).screen
                if (screen !is Screen.Overworld || screen.incomingCall != null) {
                    println("  after ${i + 1} paces: $screen")
                    return
                }
            }
        }
        println("  no call after $times paces")
    }

    fun run(command: String) {
        val name = command.substringBefore(':')
        val arg = command.substringAfter(':', "")
        when (name) {
            "boot" -> boot(arg.toIntOrNull() ?: 6000)
            "load" -> check(console.loadState(out.resolve(arg).readBytes())) { "state rejected: $arg" }
            "save" -> out.resolve(arg).writeBytes(console.saveState())
            "step" -> scope.step(arg.toInt())
            "tap" -> {
                val button = Button.valueOf(arg.substringBefore('x').uppercase())
                repeat(arg.substringAfter('x', "1").toInt()) { println("  ${scope.tap(button)}") }
            }
            "hold" -> arg.split(':').let { (b, n) -> scope.step(n.toInt(), InputFrame(b.split('+').map { Button.valueOf(it.uppercase()) }.toSet())); scope.step(2) }
            "raw" -> dev.kotlinds.pokemonclient.hgss.HgssReader(scope.memory()).read()?.let { st ->
                println("  mode=${st.mode} detail=${st.modeDetail} awaiting=${st.awaitingInput} fading=${st.fading}")
                println("  loc=${st.location?.let { "${it.mapName} ${it.x},${it.z} ${it.facing}" }}")
                println("  menu=${st.menu} app=${st.app} dialogue=${st.dialogue?.text?.take(120)}")
                st.surroundings?.bgEvents?.forEach { println("  bg $it") }
                st.surroundings?.warps?.forEach { println("  warp ${it.x},${it.z} -> ${it.destMapName} ${it.kind} ${it.pressDirection}") }
                st.surroundings?.grid?.let { g -> println("  origin ${g.originX},${g.originZ}"); g.rows.forEachIndexed { i, row -> println("  | ${g.originZ + i} $row") } }
                st.surroundings?.objects?.forEach { println("  obj ${it.label} ${it.x},${it.z}") }
            }
            "touch" -> arg.split(',').let { (x, y) -> scope.touch(TouchPoint(x.toInt(), y.toInt())) }
            "until" -> {
                val (kind, frames) = arg.split(':')
                val met = scope.stepUntil(frames.toInt()) { game.state(it).screen.kind.startsWith(kind) }
                println("  ${if (met) "reached" else "NOT reached"} $kind at frame ${console.frame}")
            }
            "shot" -> console.framebuffer()?.let { save(it, out.resolve("$arg.png")) }
            "state" -> println(game.state(scope.memory()).toString().replace(", ", ",\n  "))
            "screen" -> println("  " + game.state(scope.memory()).screen)
            "puzzle" -> game.state(scope.memory()).field.let { f ->
                println("  ${f?.mapName} (${f?.mapId}) at ${f?.x},${f?.y}")
                f?.puzzle?.let { p ->
                    println("  puzzle ${p.kind}: ${p.rule}")
                    p.switches.forEach { println("    $it") }
                    p.barriers.forEach { println("    $it") }
                    p.teleports.forEach { println("    $it") }
                    p.indicators.forEach { println("    $it") }
                    p.surfaces.forEach { println("    $it") }
                    if (p.unmodeled != null) println("    unmodeled: ${p.unmodeled}")
                    p.herds.forEach { println("    $it") }
                    p.boulderHoles.forEach { println("    $it") }
                    p.platforms.forEach { pl -> println("    ${pl.id} pivot ${pl.pivot.x},${pl.pivot.y} r${pl.rotation} " + pl.triggers.joinToString { "${it.tile.x},${it.tile.y}=${it.effect}${if (it.possible) "" else "(blocked)"}" }) }
                } ?: println("  no puzzle")
            }
            "ram" -> out.resolve("$arg.ram").writeBytes(ram())
            "fixture" -> fixture(arg)
            "world" -> world(arg)
            "mapview" -> game.state(scope.memory()).field?.let { f ->
                game.world?.areaOf(f.mapId)?.let { area -> MapView.render(area, f, game::zoneName, world = game.world) }
            }?.forEach { (k, v) -> println("  $k: " + (v as? kotlinx.serialization.json.JsonArray)?.joinToString("\n    ", "\n    ") { it.toString().trim('"') }.orEmpty().ifEmpty { v.toString() }) }
            "area" -> area(arg.split(',').map { it.trim().toInt() })
            "tiles" -> arg.split(',').map { it.trim().toInt() }.let { (x0, x1, y) ->
                val f = game.state(scope.memory()).field
                val ar = f?.let { game.world?.areaOf(it.mapId) }
                (x0..x1).forEach { x -> println("  $x,$y ${ar?.tile(x, y)} zone=${ar?.zoneAt(x, y)}") }
            }
            "trace" -> trace(arg.toInt())
            "msgtrace" -> msgTrace(arg.toInt())
            "mashtrace" -> msgTrace(arg.toInt(), mash = true)
            "truth" -> when {
                arg == "on" -> truthOn()
                // One line per frame: the battle message read that frame (escaped), empty when none. For replay tests.
                arg.startsWith("dump:") -> out.resolve(arg.removePrefix("dump:") + ".trace").toFile().writeText(
                    truthFrames.joinToString("\n") { m -> m?.replace("\\", "\\\\")?.replace("\n", "\\n") ?: "" } + "\n",
                )
                else -> truthCheck()
            }
            "autobattle" -> autoBattle(arg.substringBefore(',').toInt(), arg.substringAfter(',', "").takeIf { it.isNotEmpty() })
            "rawmon" -> HgssReader(scope.memory(), HgssVersion.HEARTGOLD_US).partyRaw().forEachIndexed { i, b ->
                println("  slot $i: " + b.joinToString("") { "%02x".format(it) })
            }
            "box" -> HgssReader(scope.memory(), HgssVersion.HEARTGOLD_US).boxRaw(arg.toInt()).forEachIndexed { i, b ->
                dev.kotlinds.pokemonclient.hgss.HgssPokemon.decode(b)?.let { m ->
                    println("  $i: mon:%08x.%08x species ${m.species} ${dev.kotlinds.pokemonclient.hgss.HgssData.speciesName(m.species)} exp ${m.exp}".format(m.personality, m.otId))
                }
            }
            "events" -> recorder.log.since(0).filterNot { it is dev.kotlinds.pokemonclient.state.GameEvent.ScreenChanged }.forEach { println("  $it") }
            "watch" -> { watching = arg != "off"; lastWatch = null }
            "steps" -> steps(Button.valueOf(arg.substringBefore('x').uppercase()), arg.substringAfter('x', "1").toInt())
            "where" -> HgssReader(scope.memory(), HgssVersion.HEARTGOLD_US).read()?.let { st ->
                println("  ${st.mode} ${st.modeDetail} at ${st.location?.x},${st.location?.z} facing ${st.location?.facing} map ${st.location?.mapName}")
                st.surroundings?.bgEvents?.forEach { println("    bg $it") }
                st.surroundings?.grid?.let { g -> g.rows.forEachIndexed { i, r -> println("    ${g.originZ + i}\t$r") }; println("    x0=${g.originX}") }
            }
            "fish" -> fish(arg.toInt())
            "pace" -> pace(arg.toInt())
            "scr" -> println(describe(game.state(scope.memory()).screen))
            "walk" -> walk(arg.split(',').map { Button.valueOf(it.trim().uppercase()) })
            "cur" -> println(describe(game.state(scope.memory()).screen).lineSequence().first())
            "act" -> {
                val action = registry.parse(Json.parseToJsonElement(arg).jsonObject, ActionMode.ASSISTED).getOrElse { error ->
                    // Refused like the agent would see it, and the script goes on.
                    println("  refused: " + ((error as? ActionException)?.error?.let { "${it.code} ${it.message}" } ?: error.toString()))
                    return
                }
                val startFrame = console.frame
                val outcome = registry.execute(action, scope, game)
                val actFrames = console.frame - startFrame
                // Like the app (GameSession.SETTLE_FRAMES): the game settles after every action.
                Navigator(scope, game).settle(maxFrames = 1800)
                println("  $outcome ($actFrames frames)")
                println("  " + game.state(scope.memory()).screen)
            }
            "log" -> {
                recorder.log.since(logCursor).forEach { println("  $it") }
                logCursor = recorder.log.lastSeq
            }
            "actions" -> registry.available(game.state(scope.memory()), ActionMode.ASSISTED).forEach { println("  $it") }
            else -> error("unknown command $command")
        }
    }

    /** `area:x0,y0,x1,y1`: the ROM tiles of the current area as ASCII, with the live objects (B boulder, I ice, T tree, R rock, P person, @ you). */
    private fun area(box: List<Int>) {
        val state = game.state(scope.memory())
        val field = state.field ?: return println("  not in the field")
        val area = game.world?.areaOf(field.mapId) ?: return println("  no area")
        val (x0, y0, x1, y1) = box
        println("  x ${x0}..${x1} (tens: ${(x0..x1).joinToString("") { ((it / 10) % 10).toString() }})")
        println("  units      ${(x0..x1).joinToString("") { (it % 10).toString() }}")
        for (y in y0..y1) {
            val row = (x0..x1).joinToString("") { x ->
                val o = field.objects.firstOrNull { it.x == x && it.y == y && it.kind != dev.kotlinds.pokemonclient.state.FieldObjectKind.FOLLOWER }
                when {
                    field.x == x && field.y == y -> "@"
                    o?.obstacle != null -> when (o.obstacle!!) {
                        dev.kotlinds.pokemonclient.state.ObstacleKind.BOULDER -> "B"
                        dev.kotlinds.pokemonclient.state.ObstacleKind.ICE_BLOCK -> if (o.facing == dev.kotlinds.pokemonclient.Direction.SOUTH) "I" else "i"
                        dev.kotlinds.pokemonclient.state.ObstacleKind.CUT_TREE -> "T"
                        dev.kotlinds.pokemonclient.state.ObstacleKind.SMASH_ROCK -> "R"
                    }
                    o != null -> "P"
                    area.warps.any { it.x == x && it.y == y } -> "W"
                    else -> when (val t = area.tile(x, y)) {
                        null -> " "
                        else -> when (val k = t.kind) {
                            is dev.kotlinds.pokemonclient.world.TileKind.Water -> if (k.surfable) "~" else "#"
                            dev.kotlinds.pokemonclient.world.TileKind.Whirlpool -> "%"
                            dev.kotlinds.pokemonclient.world.TileKind.Waterfall -> "|"
                            dev.kotlinds.pokemonclient.world.TileKind.Ice -> "*"
                            dev.kotlinds.pokemonclient.world.TileKind.TallGrass -> "\""
                            is dev.kotlinds.pokemonclient.world.TileKind.Ledge -> when (k.jump) {
                                dev.kotlinds.pokemonclient.Direction.SOUTH -> "v"; dev.kotlinds.pokemonclient.Direction.NORTH -> "^"
                                dev.kotlinds.pokemonclient.Direction.WEST -> "<"; dev.kotlinds.pokemonclient.Direction.EAST -> ">"
                            }
                            else -> if (t.blocked) "#" else "."
                        }
                    }
                }
            }
            println("  ${y.toString().padStart(5)}      $row")
        }
    }

    private fun watchParty() {
        val memory = scope.memory()
        val raw = HgssReader(memory, HgssVersion.HEARTGOLD_US).read() ?: return
        val state = game.state(memory)
        val rawLine = raw.party.joinToString(" | ") { "${it.slot}:${it.speciesName} L${it.level} ${it.hp}/${it.maxHp} x${it.exp} ${it.status}${if (it.checksumOk) "" else " CS!"}${if (it.plausible) "" else " IMPL"}" }
        val shown = state.party.joinToString(" | ") { "${it.slot}:${it.species.name} L${it.level} ${it.hp}/${it.maxHp}" }
        val line = "${brief(state.screen)}\n    raw  $rawLine\n    show $shown" + state.warnings.joinToString("") { "\n    warn ${it.detail}" }
        if (line != lastWatch) println("  [${console.frame}] $line")
        lastWatch = line
        saveTornFixture(memory)
        if (System.getenv("BENCH_WATCH_HEX") == "1") {
            val bytes = HgssReader(memory, HgssVersion.HEARTGOLD_US).partyRaw().map { b -> b.joinToString("") { "%02x".format(it) } }
            bytes.forEachIndexed { i, h -> if (lastHex.getOrNull(i) != h) println("    hex $i: $h") }
            lastHex = bytes
        }
    }

    private var lastHex: List<String> = emptyList()

    /** `BENCH_WATCH_FIXTURE=<prefix>`: while watching, frames where a party slot is mid-rewrite are saved as fixtures. */
    private var tornFixtures = 0

    /**
     * True when the plain reading the flags announce is wrong: the game is rewriting the Pokémon (blocks or party data
     * caught encrypted / decrypted / torn, see HgssPokemon.decode).
     */
    private fun midRewrite(raw: ByteArray): Boolean {
        val mon = dev.kotlinds.pokemonclient.hgss.HgssPokemon
        val flags = mon.u16(raw, 4)
        val checksum = mon.u16(raw, 6)
        val box = raw.copyOfRange(8, 0x88).also { if (flags and 2 == 0) mon.crypt(it, 0, it.size, checksum.toLong()) }
        val boxOk = (0 until 0x40).sumOf { mon.u16(box, 2 * it) } and 0xFFFF == checksum
        val naive = mon.decode(raw) { true } ?: return false
        return !boxOk || !dev.kotlinds.pokemonclient.hgss.HgssMonCheck.isPlausible(naive)
    }

    private fun saveTornFixture(memory: Memory) {
        val prefix = System.getenv("BENCH_WATCH_FIXTURE") ?: return
        if (tornFixtures >= 6) return
        val torn = HgssReader(memory, HgssVersion.HEARTGOLD_US).partyRaw().withIndex().filter { midRewrite(it.value) }.map { it.index }
            .filter { slot -> System.getenv("BENCH_WATCH_FIXTURE_SLOT")?.let { it.toInt() == slot } ?: true }
        if (torn.isEmpty()) return
        val name = "${prefix}_${tornFixtures++}_f${console.frame}_slot${torn.joinToString("-")}"
        fixture(name)
    }

    private fun trace(frames: Int) {
        var last: String? = null
        repeat(frames) {
            scope.step(1)
            val line = describe()
            if (line != last) println("  [${console.frame}] $line")
            last = line
        }
    }

    /** Steps [frames] frames printing every change of the battle message (state) and the screen kind. */
    private fun msgTrace(frames: Int, mash: Boolean = false) {
        var last: String? = null
        repeat(frames) { i ->
            // mash: A pressed one frame out of four, like an impatient player (or a plan pressing through texts).
            scope.step(1, if (mash && i % 4 == 0) InputFrame.of(Button.A) else InputFrame.NONE)
            val state = game.state(scope.memory())
            val line = "${state.screen.kind} | ${state.battle?.message?.replace("\n", "/")} | shakes=${state.battle?.ballShakes}"
            if (line != last) println("  [${console.frame}] $line")
            last = line
        }
    }

    /**
     * Plays up to [turns] decisions of the current battle like an agent would (attack with [move] or the first move
     * with PP, keep battling, send the first Pokémon able to fight, keep the old moves, read on), with the app's
     * settle after each action; then compares the battle messages read on every frame with the recorder's.
     */
    private var truthSince = 0L

    /** The battle message of every frame since `truth:on` (null: no battle message), for `truth:dump`. */
    private val truthFrames = mutableListOf<String?>()

    private fun truthOn() {
        truth = mutableListOf()
        truthFrames.clear()
        truthSince = recorder.log.lastSeq
    }

    private fun truthCheck() {
        val seen = truth ?: return
        truth = null
        val recorded = recorder.log.since(truthSince).filterIsInstance<dev.kotlinds.pokemonclient.state.GameEvent.TextShown>()
            .filter { it.source == dev.kotlinds.pokemonclient.state.TextSource.BATTLE }.map { it.text }
        val missing = seen.filter { it !in recorded }
        println("  battle messages shown: ${seen.size}, recorded: ${recorded.size}, missing: ${missing.size}")
        missing.forEach { println("    MISSING: ${it.replace("\n", "/")}") }
    }

    private fun autoBattle(turns: Int, move: String?) {
        if (truth == null) truthOn()
        repeat(turns) {
            val state = game.state(scope.memory())
            val screen = state.screen
            val json = when {
                state.battle == null && screen is Screen.Overworld -> return@repeat
                screen is Screen.BattleCommand -> {
                    val actor = state.battle?.battlers?.firstOrNull { it.ref == screen.actor }
                    val chosen = move?.takeIf { m -> actor?.moves?.any { "move:${it.move.id.value}" == m && it.pp > 0 } == true }
                        ?: actor?.moves?.firstOrNull { it.pp > 0 }?.let { "move:${it.move.id.value}" } ?: return@repeat
                    """{"type":"attack","move":"$chosen"}"""
                }
                screen is Screen.ListMenu && screen.kind == dev.kotlinds.pokemonclient.state.MenuKind.BATTLE_SWITCH_OR_KEEP -> """{"type":"keep_battling"}"""
                screen is Screen.PartyGrid -> screen.entries.firstOrNull { it.selectable && it.id.startsWith("mon:") }?.let { """{"type":"switch","pokemon":"${it.id}"}""" } ?: return@repeat
                screen is Screen.YesNo && screen.learning != null -> """{"type":"learn_move"}"""
                screen is Screen.YesNo && screen.entries.any { it.id == "option:next" } -> """{"type":"choose","entry":"option:next"}"""
                else -> """{"type":"advance_dialogue"}"""
            }
            run("act:$json")
        }
        truthCheck()
    }

    private fun describe(): String {
        val memory = scope.memory()
        val fishing = (game as? HgssGame)?.let { HgssFishing.state(HgssMemory(memory, HgssVersion.HEARTGOLD_US)) }
        val state = game.state(memory)
        val position = state.field?.let { " at ${it.x},${it.y}${if (it.moving) " moving" else ""} ${it.movement.name.lowercase()}" } ?: ""
        return brief(state.screen) + position + (fishing?.let { " fishing=$it" } ?: "")
    }

    /**
     * Fishing "watch": steps up to [frames] frames, presses A for one frame on the first frame of a bite, and
     * prints every change of the fishing probe.
     */
    private fun fish(frames: Int) {
        var last: String? = null
        repeat(frames) {
            val memory = scope.memory()
            val state = HgssFishing.state(HgssMemory(memory, HgssVersion.HEARTGOLD_US))
            val line = "$state"
            if (line != last) println("  [${console.frame}] fishing=$line screen=${brief(game.state(memory).screen)}")
            last = line
            scope.step(1, if (state is FishingState.Bite) InputFrame.of(Button.A) else InputFrame.NONE)
        }
    }

    /** Walks [steps] tiles towards [button]: holds it until the player's tile changes, then waits until it stands still. */
    private fun steps(button: Button, steps: Int) {
        fun position() = HgssReader(scope.memory(), HgssVersion.HEARTGOLD_US).read()?.location?.let { Triple(it.x, it.z, it.moving) }
        repeat(steps) {
            val start = position()
            scope.stepUntil(40, InputFrame.of(button)) { position()?.let { (x, z, _) -> x != start?.first || z != start.second } == true }
            scope.stepUntil(40) { position()?.third == false }
        }
        println("  at ${position()}")
    }

    private fun brief(screen: Screen): String = when (screen) {
        is Screen.Selectable -> "${screen::class.simpleName}(${(screen as? Screen.ListMenu)?.kind ?: ""} cursor=${screen.cursor} " +
            "cancel=${screen.cancel} entries=${screen.entries.joinToString { e -> "${e.id}|${e.label.replace('\n', ' ')}" + (if (!e.selectable) "|x" else "") + (e.touch?.let { "@${it.x},${it.y}" } ?: "") }})"
        else -> screen.toString().replace('\n', ' ')
    }

    /**
     * Taps each button and checks the decoded topology: the cursor must land where `topology.next` predicted
     * (staying put when it predicted null). Prints OK / MISMATCH per tap.
     */
    private fun walk(buttons: List<Button>) {
        var ok = 0
        for (button in buttons) {
            val before = game.state(scope.memory()).screen as? Screen.Selectable ?: error("not a selectable screen")
            val from = (before.cursor as? dev.kotlinds.pokemonclient.state.Cursor.At)?.index ?: error("cursor hidden")
            val predicted = before.topology.next(from, button) ?: from
            scope.tap(button)
            scope.step(6)
            val after = game.state(scope.memory()).screen as? Screen.Selectable
            val got = (after?.cursor as? dev.kotlinds.pokemonclient.state.Cursor.At)?.index
            val same = after != null && after::class == before::class
            if (same && got == predicted) ok++
            println("  ${if (same && got == predicted) "OK" else "MISMATCH"} $button: $from -> $got (predicted $predicted)${if (!same) " screen ${after?.let { it::class.simpleName }}" else ""}")
        }
        println("  walk: $ok/${buttons.size} OK")
    }

    /** Compact view of a screen: kind, cursor, entries and, for selectable screens, the D-pad moves of each entry. */
    private fun describe(screen: Screen): String = buildString {
        append("  ${screen::class.simpleName}")
        if (screen !is Screen.Selectable) {
            append(" $screen")
            return@buildString
        }
        when (screen) {
            is Screen.Bag -> append(" pocket=${screen.pocket} page=${screen.page}/${screen.pages}")
            is Screen.PartyGrid -> append(" purpose=${screen.purpose}")
            is Screen.ContextMenu -> append(" owner=${screen.owner}")
            is Screen.YesNo -> append(" question=${screen.question}")
            else -> {}
        }
        append(" cursor=${screen.cursor} cancel=${screen.cancel}")
        val buttons = listOf(Button.UP, Button.DOWN, Button.LEFT, Button.RIGHT, Button.L, Button.R)
        screen.entries.forEachIndexed { i, e ->
            val moves = buttons.mapNotNull { b -> screen.topology.next(i, b)?.let { "${b.name[0]}${if (b == Button.L || b == Button.R) "b" else ""}$it" } }
            append("\n    $i ${e.id} \"${e.label}\"${if (!e.selectable) " (x)" else ""}${e.touch?.let { " touch=${it.x},${it.y}" } ?: ""} ${moves.joinToString(" ")}")
        }
    }
    /** Presses A from power-on until the player can walk (title screen, main menu CONTINUE, recap). */
    private fun boot(maxFrames: Int) {
        scope.step(120)
        while (scope.framesUsed < maxFrames) {
            val screen = game.state(scope.memory()).screen
            if (screen is Screen.Overworld && screen.awaiting == Awaiting.INPUT) {
                println("  overworld at frame ${console.frame}")
                return
            }
            scope.tap(Button.A)
            scope.step(20)
        }
        error("never reached the overworld")
    }

    private fun ram(): ByteArray =
        ByteArray(console.memorySize(MemoryRegion.MAIN_RAM)).also { console.read(MemoryRegion.MAIN_RAM, 0, it.size, it) }

    /** Decodes the current frame through a recording memory and keeps only the bytes read. */
    private fun fixture(name: String) {
        val recording = RecordingMemory(RamMemory(ram()))
        game.state(recording)
        game.observe(recording)
        writeFixture(name, recording)
    }

    private fun writeFixture(name: String, recording: RecordingMemory) {
        val file = out.resolve("$name.ram.sparse.gz")
        DataOutputStream(GZIPOutputStream(Files.newOutputStream(file))).use { output ->
            output.write("SPRM".toByteArray())
            recording.ranges().forEach { (start, bytes) ->
                output.writeInt(start.toInt())
                output.writeInt(bytes.size)
                output.write(bytes)
            }
        }
        println("  fixture $file (${recording.ranges().sumOf { it.second.size }} bytes)")
    }

    private val romImage: NdsRom by lazy { NdsRom.parse(romPath.readBytes()) }
    private val romVersion: HgssVersion by lazy { HgssVersion.forGameCode(romImage.gameCode) ?: error("unsupported ROM ${romImage.gameCode}") }
    private val world: HgssWorldSource by lazy { HgssWorldSource(romImage, romVersion) }

    /** Checks the ROM world decoder against the map loaded in RAM (every tile of every loaded block, zone events). */
    private fun world(name: String) {
        val recording = RecordingMemory(RamMemory(ram()))
        val terrain = HgssLoadedMap(recording, romVersion)
        val zone = terrain.zoneId ?: error("not in the field")
        val header = world.header(zone)!!
        val matrix = world.matrix(header.matrixId)!!
        val area = world.areaOf(zone)!!
        var compared = 0
        var mismatches = 0
        for (block in terrain.loadedBlocks) {
            val bx = block % terrain.matrixWidth
            val bz = block / terrain.matrixWidth
            val land = world.landData(matrix.landAt(bx, bz)) ?: continue
            for (lz in 0 until 32) for (lx in 0 until 32) {
                val x = bx * 32 + lx
                val z = bz * 32 + lz
                val live = terrain.attribute(x, z) ?: continue
                compared++
                val decoded = land.attribute(lx, lz)
                val tile = area.tile(x, z)
                if (live != decoded || tile?.blocked != (live and 0x8000 != 0)) {
                    if (mismatches++ < 10) println("  MISMATCH ($x,$z) ram=0x${live.toString(16)} rom=0x${decoded.toString(16)} tile=$tile")
                }
            }
        }
        val px = terrain.playerX!!
        val pz = terrain.playerZ!!
        println("  zone $zone ${HgssData.mapName(zone)} matrix ${matrix.id} blocks ${terrain.loadedBlocks} compared $compared tiles, $mismatches mismatches")
        println("  player ($px,$pz) height ${terrain.playerHeight} tile ${area.tile(px, pz)} zoneAt ${area.zoneAt(px, pz)} altitude ${matrix.altitudeAt(px / 32, pz / 32)}")
        val live = terrain.events()
        val rom = world.events(zone)
        println("  events: ${if (live == rom) "RAM = ROM" else "DIFFER\n    ram=$live\n    rom=$rom"} (${rom?.bgs?.size} bgs, ${rom?.objects?.size} objects, ${rom?.warps?.size} warps, ${rom?.coords?.size} coords)")
        if (name.isNotEmpty()) writeFixture(name, recording)
    }

    private fun save(frame: Frame, file: Path) {
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, frame.width, frame.height, frame.pixels, 0, frame.width)
        ImageIO.write(image, "png", file.toFile())
    }
}

/** [Memory] that remembers which addresses were read, to store only those in a sparse fixture. */
private class RecordingMemory(private val memory: Memory) : Memory {
    private val touched = sortedSetOf<Long>()

    private fun touch(addr: Long, size: Int) {
        for (i in 0 until size) touched += addr + i
    }

    override fun read8(addr: Long) = memory.read8(addr).also { touch(addr, 1) }
    override fun read16(addr: Long) = memory.read16(addr).also { touch(addr, 2) }
    override fun read32(addr: Long) = memory.read32(addr).also { touch(addr, 4) }
    override fun readBytes(addr: Long, size: Int) = memory.readBytes(addr, size).also { touch(addr, size) }

    /** Contiguous runs of touched bytes, with their values. */
    fun ranges(): List<Pair<Long, ByteArray>> {
        val result = mutableListOf<Pair<Long, ByteArray>>()
        var start = -1L
        var previous = -2L
        fun flush() {
            if (start >= 0) result += start to memory.readBytes(start, (previous - start + 1).toInt())
        }
        for (addr in touched) {
            if (addr != previous + 1) {
                flush()
                start = addr
            }
            previous = addr
        }
        flush()
        return result.filter { (addr, _) -> addr in 0x02000000L..0x023FFFFFL }
    }
}
