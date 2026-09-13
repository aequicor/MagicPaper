package io.aequicor.magicpaper.tools.editor

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.aequicor.magicpaper.tools.paper.PaperDesignPlugin
import io.aequicor.visualization.MissionEditorApp
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--agent") {
        require(args.size >= 3) { "--agent command response-file [source-file png-file]" }
        val response = Path.of(args[2])
        val result = try {
            runBlocking { paperAgentCommand(args[1], args.getOrNull(3)?.let(Path::of), args.getOrNull(4)?.let(Path::of)) }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            io.aequicor.magicpaper.logging.AppLog.error("paper-editor", "agent_command.failed", failure)
            buildJsonObject { put("valid", false); put("diagnostics", "Paper editor could not complete this operation. Check the document and dimensions.") }
        }
        Files.writeString(response, result.toString())
        exitProcess(0)
    }
    fun option(name: String): String? = args.indexOf(name).takeIf { it >= 0 }?.let { requireNotNull(args.getOrNull(it + 1)) }
    val project = option("--project")?.let { Path.of(it).toRealPath().toString() }
    val ready = option("--ready-file")?.let(Path::of)
    option("--data-dir")?.let { System.setProperty("mission.visualization.dataDir", it) }

    if (System.getProperty("mission.visualization.dataDir") == null) {
        System.setProperty("mission.visualization.dataDir", java.io.File(System.getProperty("user.home"), ".magicpaper/paper-editor").absolutePath)
    }
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Paper · Mission Visualization",
            state = rememberWindowState(width = 1440.dp, height = 960.dp),
        ) {
            val plugins = remember { listOf(PaperDesignPlugin()) }
            MissionEditorApp(plugins = plugins, initialProjectPath = project, onProjectChanged = { opened ->
                if (ready != null) withContext(Dispatchers.IO) { Files.writeString(ready, opened.orEmpty()) }
            })
        }
    }
}
