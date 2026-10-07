package me.nathanfallet.aiplayspokemon.mcp

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Where the MCP server's delivery verdicts "lost" / "uncertain" go ([DeliveryVerdict]): one line in the console, and
 * one JSON line in `<data>/runs/delivery-<date>.jsonl` (next to the runs' statistics, [file] null: console only), one
 * file per MCP server started. After the next runs, these files tell whether an answer still gets lost now that every
 * long call sends heartbeats, i.e. whether the timing rules of [DeliveryTracker] can be removed.
 */
class DeliveryLog(private val file: Path?) {
    /** Logs [verdict]; a file that can't be written never disturbs the call. */
    @Synchronized
    fun record(verdict: DeliveryVerdict) {
        println(verdict.summary)
        val file = file ?: return
        runCatching {
            Files.createDirectories(file.parent)
            Files.writeString(file, json.encodeToString(DeliveryVerdict.serializer(), verdict) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }

    companion object {
        private val json = Json { encodeDefaults = true }

        /** The log of an MCP server started now, in [runsDirectory] (`<data>/runs`). */
        fun startingNow(runsDirectory: Path): DeliveryLog =
            DeliveryLog(runsDirectory.resolve("delivery-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".jsonl"))
    }
}
