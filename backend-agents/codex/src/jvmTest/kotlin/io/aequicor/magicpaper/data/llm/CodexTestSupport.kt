package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.backend.*
import io.aequicor.magicpaper.domain.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Process ownership kept in memory: a process is owned by the id it was recorded under until it is cleared,
 * and reconciling an id stops the whole tree. The durable, file-backed receipts belong to the host and are
 * covered by its own tests; the client only depends on this contract.
 */
internal class InMemoryProcessOwnership : NativeProcessRecovery {
    private val owned = ConcurrentHashMap<String, ProcessHandle>()
    override fun record(id: String, process: Process, attachLifetime: Boolean) { owned[id] = process.toHandle() }
    override fun clear(id: String) { owned.remove(id) }
    override fun belongsTo(id: String, process: Process?) = process != null && owned[id]?.pid() == process.pid()
    override fun reconcile(id: String): Boolean {
        val process = owned[id] ?: return false
        val alive = process.isAlive
        if (alive) {
            val children = process.descendants().use { it.toList() }
            children.asReversed().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.onExit().get(10, TimeUnit.SECONDS)
            children.forEach { if (it.isAlive) it.onExit().get(10, TimeUnit.SECONDS) }
        }
        owned.remove(id)
        return alive
    }
}

/** A client that reaches nothing outside the process: no tokens, no questionnaire, failures are only recorded. */
internal fun codexTestClient(json: Json, home: Path, processes: NativeProcessRecovery = InMemoryProcessOwnership(),
    failures: MutableList<Throwable> = mutableListOf()) = CodexNativeClient(json, home, null, processes,
    NativeAuthTokens { null },
    object : NativeQuestionnaires {
        override suspend fun ask(request: UserInteractionRequest): List<PlanningAnswer> = error("Unexpected questionnaire")
        override suspend fun beginDelivery(requestId: String): String = error("Unexpected delivery")
        override suspend fun finishDelivery(requestId: String, attemptId: String, outcome: QuestionnaireDeliveryOutcome): Unit = error("Unexpected delivery")
    }, NativeDiagnostics { _, _, cause, _ -> failures += cause },
    NativeToolPresentationResolver { server, tool, arguments -> NativeToolPresentation("$server:$tool", "$tool · ${arguments ?: ""}".take(1500)) })

internal fun nativeAccumulatorType(client: CodexNativeClient): Class<*> =
    client.javaClass.declaredClasses.single { it.simpleName == "CodingAccumulator" }

internal fun nativeTurnAccumulator(client: CodexNativeClient, onActivity: (CodingStep) -> Unit): Any =
    client.javaClass.declaredClasses.single { it.simpleName == "TurnAccumulator" }
        .getDeclaredConstructor(Function1::class.java).apply { isAccessible = true }.newInstance(onActivity)

internal fun CodexNativeClient.handleServerRequest(id: JsonPrimitive, method: String, params: JsonObject): Boolean =
    javaClass.declaredMethods.single { it.name.startsWith("handleServerRequest") }
        .apply { isAccessible = true }.invoke(this, id, method, params) as Boolean

/**
 * A local parent process that starts a child of its own on request, so that tree cleanup is observable.
 * It is a single-file Java program: no installation, provider or network is involved.
 */
internal fun startProcessTreeParent(directory: Path): Process {
    val source = directory.resolve("TreeParent.java")
    Files.writeString(source, """
        class TreeParent {
            public static void main(String[] args) throws Exception {
                if (args.length > 0 && args[0].equals("child")) { Thread.sleep(30000); return; }
                System.in.read();
                Process child = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "${source.toString().replace("\\", "\\\\")}", "child").start();
                System.out.println(child.pid()); System.out.flush();
                Thread.sleep(30000);
            }
        }
    """.trimIndent())
    return ProcessBuilder(System.getProperty("java.home") + "/bin/java", source.toString()).start()
}
