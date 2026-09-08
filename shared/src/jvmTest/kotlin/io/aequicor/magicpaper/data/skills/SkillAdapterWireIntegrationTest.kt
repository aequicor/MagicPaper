package io.aequicor.magicpaper.data.skills

import io.aequicor.magicpaper.data.coding.DesktopCodingRuntime
import io.aequicor.magicpaper.data.coding.PiCodingRuntime
import io.aequicor.magicpaper.data.llm.CodexAppServerOpenAiSubscription
import io.aequicor.magicpaper.domain.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Hermetic transport contract: the Pi CLI and Codex JSONL server are child
 * processes, not mocks injected into the runtime.  The fixtures only record
 * their wire inputs; no model, credentials or network are involved.
 */
class SkillAdapterWireIntegrationTest {
    private val json = Json
    private val host = SkillPackageHost("1.0.0", "desktop")

    @Test fun approved_pin_reaches_pi_and_codex_as_exact_fresh_wire_input() = runBlocking { withRoot { root ->
        org.junit.Assume.assumeTrue("Pi wire fixture requires an installed Node >= 22.19", findNode() != null)
        val project = Files.createDirectories(root.resolve("project"))
        val piLog = root.resolve("pi-wire.jsonl")
        val codexLog = root.resolve("codex-wire.jsonl")
        installFakePi(root.resolve("pi"), piLog)
        val codexCommand = installCodexCommand(root, codexLog)
        LocalSkillRepository(root.resolve("repository"), host).use { repository ->
            val pinned = install(repository, "1.0.0", "PINNED-TEXT")
            install(repository, "2.0.0", "QUARANTINED-TEXT", reviewed = false)
            bind(repository, "project", pinned)
            val pins = repository.snapshot().projects.getValue("project")
            repository.bindProject("project", pins, SkillActivationConsent(repository.snapshot().generation, pins, true, emptySet(), true))
            val selection = repository.projectCodingSelection("project")
            assertTrue(selection.trustedText && selection.freshSession)

            val runtime = DesktopCodingRuntime(
                PiCodingRuntime(root.resolve("pi").toFile()),
                CodexAppServerOpenAiSubscription(json, root.resolve("codex-home"), codexCommand),
                skillSelection = repository::projectCodingSelection,
            )
            try {
                val codingProject = CodingProject("project", "Project", project.toString(), 0)
                val oldSession = "old-engine-history"
                val piEvents = runBlocking {
                    runtime.run(codingProject, CodingSession("pi", "project", "Pi", 0, piSessionId = oldSession, engine = CodingEngine.PI), "PI-TASK", piProfile()).toList()
                }
                assertTrue(piEvents.any { it is CodingEvent.SessionStarted })
                val piWire = piRunWire(piLog)
                val piArgs = piWire["args"]!!.jsonArray.map { it.jsonPrimitive.content }
                assertFalse("--session-id" in piArgs, "trusted input must not resume Pi history")
                assertExactSkillPayload(piWire["stdin"]!!.jsonPrimitive.content, pinned, "PI-TASK")

                val codexEvents = runBlocking {
                    runtime.run(codingProject, CodingSession("codex", "project", "Codex", 0, piSessionId = oldSession, engine = CodingEngine.CODEX), "CODEX-TASK", codexProfile()).toList()
                }
                assertTrue(codexEvents.any { it is CodingEvent.SessionStarted })
                val codexWire = Files.readAllLines(codexLog).map { json.parseToJsonElement(it).jsonObject }
                assertTrue(codexWire.any { it["method"]?.jsonPrimitive?.content == "thread/start" })
                assertFalse(codexWire.any { it["method"]?.jsonPrimitive?.content == "thread/resume" })
                val codexPrompt = codexWire.single { it["method"]?.jsonPrimitive?.content == "turn/start" }
                    .getValue("params").jsonObject.getValue("input").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
                assertExactSkillPayload(codexPrompt, pinned, "CODEX-TASK")
                val allWire = Files.readString(piLog) + Files.readString(codexLog)
                assertFalse(allWire.contains("QUARANTINED-TEXT"))
                assertFalse(allWire.contains("2.0.0"))
            } finally {
                runtime.abortAll()
            }
        }
    } }

    @Test fun empty_selection_resumes_without_any_skill_payload_on_both_transports() = runBlocking { withRoot { root ->
        org.junit.Assume.assumeTrue("Pi wire fixture requires an installed Node >= 22.19", findNode() != null)
        val project = Files.createDirectories(root.resolve("project"))
        val piLog = root.resolve("pi-wire.jsonl")
        val codexLog = root.resolve("codex-wire.jsonl")
        installFakePi(root.resolve("pi"), piLog)
        val codexCommand = installCodexCommand(root, codexLog)
        val empty = CodingSkillSelection(emptyList())
        val runtime = DesktopCodingRuntime(
            PiCodingRuntime(root.resolve("pi").toFile()),
            CodexAppServerOpenAiSubscription(json, root.resolve("codex-home"), codexCommand),
            skillSelection = { empty },
        )
        try {
            val codingProject = CodingProject("project", "Project", project.toString(), 0)
            runBlocking {
                runtime.run(codingProject, CodingSession("pi", "project", "Pi", 0, piSessionId = "pi-resume", engine = CodingEngine.PI), "PI-RESUME", piProfile()).toList()
            }
            val piWire = piRunWire(piLog)
            val piArgs = piWire["args"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals("pi-resume", piArgs[piArgs.indexOf("--session-id") + 1])
            assertEquals("PI-RESUME", piWire["stdin"]!!.jsonPrimitive.content)

            runBlocking {
                runtime.run(codingProject, CodingSession("codex", "project", "Codex", 0, piSessionId = "codex-resume", engine = CodingEngine.CODEX), "CODEX-RESUME", codexProfile()).toList()
            }
            val codexWire = Files.readAllLines(codexLog).map { json.parseToJsonElement(it).jsonObject }
            val resume = codexWire.single { it["method"]?.jsonPrimitive?.content == "thread/resume" }
            assertEquals("codex-resume", resume.getValue("params").jsonObject["threadId"]!!.jsonPrimitive.content)
            val prompt = codexWire.single { it["method"]?.jsonPrimitive?.content == "turn/start" }
                .getValue("params").jsonObject.getValue("input").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
            assertEquals("CODEX-RESUME", prompt)
        } finally {
            runtime.abortAll()
        }
    } }

    @Test fun checkpoint_recovery_reuses_audit_after_restart_and_reaches_both_transports() = runBlocking { withRoot { root ->
        org.junit.Assume.assumeTrue("Pi wire fixture requires an installed Node >= 22.19", findNode() != null)
        val project = Files.createDirectories(root.resolve("project"))
        val piLog = root.resolve("pi-wire.jsonl")
        val codexLog = root.resolve("codex-wire.jsonl")
        installFakePi(root.resolve("pi"), piLog)
        val command = installCodexCommand(root, codexLog)
        val repositoryPath = root.resolve("repository")
        val originalAudits = mutableMapOf<String, ByteArray>()
        repeat(2) { attempt ->
            LocalSkillRepository(repositoryPath, host).use { repository ->
                if (attempt == 0) {
                    val skill = install(repository, "1.0.0", "RECOVERED-PIN")
                    val pins = mapOf("${skill.id}@${skill.version}" to skill.checksum)
                    repository.bindProject("project", pins,
                        SkillActivationConsent(repository.snapshot().generation, pins, true, emptySet(), true))
                }
                val runtime = DesktopCodingRuntime(PiCodingRuntime(root.resolve("pi").toFile()),
                    CodexAppServerOpenAiSubscription(json, root.resolve("codex-home"), command),
                    skillSelection = repository::projectCodingSelection,
                    recordSkillRun = { repository.recordCodingRun(it) })
                try {
                    for (engine in CodingEngine.entries) {
                        val checkpoint = CodingRunCheckpoint("request", "RECOVERY-TASK")
                        val session = CodingSession(engine.name, "project", "Session", 0, piSessionId = "old-history",
                            engine = engine, pendingRun = checkpoint)
                        val events = runtime.run(CodingProject("project", "Project", project.toString(), 0), session,
                            checkpoint.prompt, if (engine == CodingEngine.PI) piProfile() else codexProfile()).toList()
                        assertTrue(events.any { it is CodingEvent.SessionStarted }, events.toString())
                        assertTrue(events.none { it is CodingEvent.Failed }, events.toString())
                        val id = skillRunIdentity(session.id, checkpoint.runId)
                        val bytes = Files.readAllBytes(repositoryPath.resolve("coding-runs/$id.json"))
                        if (attempt == 0) originalAudits[id] = bytes
                        else assertContentEquals(originalAudits.getValue(id), bytes)
                    }
                } finally { runtime.abortAll() }
            }
        }
        assertEquals(2, Files.list(repositoryPath.resolve("coding-runs")).use { it.count() }.toInt())
        val piRuns = Files.readAllLines(piLog).map { json.parseToJsonElement(it).jsonObject }
            .filter { it.getValue("args").jsonArray.any { arg -> arg.jsonPrimitive.content == "--mode" } }
        assertEquals(2, piRuns.size)
        val codexRuns = Files.readAllLines(codexLog).map { json.parseToJsonElement(it).jsonObject }
            .filter { it["method"]?.jsonPrimitive?.content == "turn/start" }
        assertEquals(2, codexRuns.size)
    } }

    private suspend fun install(repository: LocalSkillRepository, version: String, text: String, reviewed: Boolean = true): SkillInstruction {
        val bytes = text.encodeToByteArray()
        val manifest = SkillPackageManifest(
            id = "wire.skill", version = version, name = "Wire skill", description = "fixture",
            files = listOf(SkillPackageFile("SKILL.md", SkillPackageValidator.sha256(bytes), bytes.size.toLong())),
            origin = SkillPackageOrigin(SkillImportKind.LOCAL_DIRECTORY),
            compatibility = SkillCompatibility("1.0.0", "2.0.0", setOf("desktop")),
        )
        repository.install(listOf(
            SkillArchiveEntry(SkillPackageFormat.MANIFEST, SkillPackageFormat.json.encodeToString(manifest).encodeToByteArray()),
            SkillArchiveEntry("SKILL.md", bytes),
        ), SkillObservedSource(SkillImportKind.LOCAL_DIRECTORY, "wire fixture"))
        val release = repository.snapshot().installed.getValue("wire.skill@$version").pkg
        if (reviewed) repository.review(release.key, SkillPackageReview(release.checksum, "test", "wire fixture", true, true, true))
        return SkillInstruction(manifest.id, version, release.checksum, manifest.name, manifest.description, emptySet(), text)
    }

    private suspend fun bind(repository: LocalSkillRepository, projectId: String, skill: SkillInstruction) {
        val pins = mapOf("${skill.id}@${skill.version}" to skill.checksum)
        repository.bindProject(projectId, pins, SkillActivationConsent(repository.snapshot().generation, pins, true, emptySet()))
    }

    private fun assertExactSkillPayload(prompt: String, skill: SkillInstruction, task: String) {
        val payload = prompt.substringAfter("authoritative):\n").substringBefore("\n\nUser task:")
        val item = json.parseToJsonElement(payload).jsonArray.single().jsonObject
        assertEquals(skill.id, item["id"]!!.jsonPrimitive.content)
        assertEquals(skill.version, item["version"]!!.jsonPrimitive.content)
        assertEquals(skill.checksum, item["checksum"]!!.jsonPrimitive.content)
        assertEquals(skill.text, item["text"]!!.jsonPrimitive.content)
        assertTrue(prompt.endsWith(task))
    }

    private fun piRunWire(log: java.nio.file.Path): JsonObject = Files.readAllLines(log)
        .map { json.parseToJsonElement(it).jsonObject }
        .single { wire -> wire["args"]!!.jsonArray.any { it.jsonPrimitive.content == "--mode" } }

    private fun piProfile() = LlmProfile("pi", "Pi fixture", provider = ProviderType.OPENAI_COMPATIBLE,
        baseUrl = "http://127.0.0.1:1/v1", apiKey = "fixture", modelId = "fixture")
    private fun codexProfile() = LlmProfile("codex", "Codex fixture", provider = ProviderType.OPENAI_SUBSCRIPTION,
        modelId = "fixture")

    private fun installFakePi(root: java.nio.file.Path, log: java.nio.file.Path) {
        val cli = root.resolve("prefix/node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js")
        Files.createDirectories(cli.parent)
        val escapedLog = log.toString().replace("\\", "\\\\").replace("'", "\\'")
        Files.writeString(cli, """
            const fs = require('fs');
            const input = fs.readFileSync(0, 'utf8');
            fs.appendFileSync('$escapedLog', JSON.stringify({args: process.argv.slice(2), stdin: input}) + '\n');
            if (process.argv.includes('--version')) { console.log('0.0-fixture'); process.exit(0); }
            console.log(JSON.stringify({type:'session', id:'pi-wire-thread'}));
            console.log(JSON.stringify({type:'message_end', message:{role:'assistant', stopReason:'stop', content:[{type:'text', text:'ok'}]}}));
        """.trimIndent())
    }

    private fun installCodexCommand(root: java.nio.file.Path, log: java.nio.file.Path): String {
        val script = root.resolve("fake-codex.sh")
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val classpath = System.getProperty("java.class.path")
        val logPath = log.toString()
        val fixtureClass = "io.aequicor.magicpaper.data.skills.SkillAdapterCodexWireFixture"
        Files.writeString(script, """
            #!/bin/sh
            MAGICPAPER_WIRE_LOG='$logPath' exec '$java' -cp '$classpath' $fixtureClass
        """.trimIndent() + "\n")
        script.toFile().setExecutable(true)
        return script.toString()
    }

    private fun findNode(): File? = (System.getenv("PATH") ?: "").split(File.pathSeparator)
        .map { File(it, "node") }.firstOrNull { candidate ->
            val version = runCatching {
                val process = ProcessBuilder(candidate.absolutePath, "--version").start()
                process.inputStream.bufferedReader().use { it.readLine() }.also { process.waitFor() }
            }.getOrNull().orEmpty().removePrefix("v").split(".").mapNotNull(String::toIntOrNull)
            val major = version.getOrNull(0) ?: 0
            val minor = version.getOrNull(1) ?: 0
            candidate.isFile && candidate.canExecute() && (major > 22 || major == 22 && minor >= 19)
        }

    private suspend fun withRoot(block: suspend (java.nio.file.Path) -> Unit) {
        val root = Files.createTempDirectory("skill-adapter-wire-")
        try { block(root) } finally { Files.walk(root).use { files -> files.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
}

/** Tiny JSONL app-server stand-in used only by [SkillAdapterWireIntegrationTest]. */
object SkillAdapterCodexWireFixture {
    @JvmStatic fun main(args: Array<String>) {
        val log = File(requireNotNull(System.getenv("MAGICPAPER_WIRE_LOG")))
        val out = System.out.bufferedWriter()
        System.`in`.bufferedReader().forEachLine { line ->
            log.appendText(line + "\n")
            val id = Regex("\\\"id\\\"\\s*:\\s*(\\d+)").find(line)?.groupValues?.get(1) ?: return@forEachLine
            val method = Regex("\\\"method\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(line)?.groupValues?.get(1) ?: return@forEachLine
            val thread = Regex("\\\"threadId\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(line)?.groupValues?.get(1) ?: "codex-wire-thread"
            val result = when (method) {
                "initialize" -> "{}"
                "account/read" -> """{"account":{"type":"chatgpt","email":"fixture@example.test"}}"""
                "thread/start" -> """{"thread":{"id":"codex-wire-thread"}}"""
                "thread/resume" -> """{"thread":{"id":"$thread"}}"""
                "turn/start" -> """{"turn":{"id":"turn-wire"}}"""
                else -> "{}"
            }
            out.write("""{"id":$id,"result":$result}""" + "\n"); out.flush()
            if (method == "turn/start") {
                Thread.sleep(100)
                out.write("""{"method":"turn/completed","params":{"threadId":"$thread","turn":{"id":"turn-wire"}}}""" + "\n")
                out.flush()
            }
        }
    }
}
