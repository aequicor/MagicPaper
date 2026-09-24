package io.aequicor.magicpaper.data.claude

import io.aequicor.magicpaper.backend.NativeCompletionFailure
import io.aequicor.magicpaper.backend.NativeCompletionRequest
import io.aequicor.magicpaper.backend.NativeDiagnostics
import io.aequicor.magicpaper.backend.NativeToolPresentation
import io.aequicor.magicpaper.backend.NativeToolPresentationResolver
import io.aequicor.magicpaper.domain.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

/** The child is a shell script that plays `claude -p`; nothing here reaches a network or an account. */
class ClaudeCompletionTest {
    private val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    private class Fixture(val home: File) : AutoCloseable {
        val binary = home.resolve("claude")
        val errors = CopyOnWriteArrayList<String>()
        val steps = CopyOnWriteArrayList<CodingStep>()
        val usage = CopyOnWriteArrayList<UsageCallResult>()
        /** Records argv, stdin and the working directory, then plays [stream]. */
        fun script(stream: String, tail: String = "") {
            binary.writeText("#!/bin/sh\nD=\"${home.path}\"\nprintf '%s\\n' \"\$@\" > \"\$D/args\"\npwd > \"\$D/cwd\"\n" +
                "echo \$\$ > \"\$D/pid\"\ncat > \"\$D/stdin\"\ncat <<'JSON'\n$stream\nJSON\n$tail\n")
            binary.setExecutable(true)
        }
        fun completion() = ClaudeCompletion(ClaudeExecutable(binary.path), home.resolve("chat"),
            NativeDiagnostics { _, event, _, _ -> errors += event },
            NativeToolPresentationResolver { server, tool, _ -> NativeToolPresentation("$server:$tool", tool) })
        fun complete(input: JsonArray = text("Привет"), timeoutSeconds: Int = 30) = runBlocking {
            completion().complete(NativeCompletionRequest("sonnet", "system text", "base text", input, "high", timeoutSeconds),
                { steps += it }, { usage += it })
        }
        fun text(value: String) = buildJsonArray { addJsonObject { put("type", "text"); put("text", value) } }
        override fun close() { home.deleteRecursively() }
    }

    private fun fixture() = Fixture(Files.createTempDirectory("claude-completion").toFile())

    private val answer = """
{"type":"system","subtype":"init","session_id":"n"}
{"type":"assistant","message":{"id":"m1","role":"assistant","content":[{"type":"tool_use","id":"t1","name":"WebSearch","input":{"query":"погода"}}],"usage":{"input_tokens":10,"output_tokens":2}}}
{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"ok"}]}}
{"type":"assistant","message":{"id":"m2","role":"assistant","content":[{"type":"text","text":"Солнечно."}],"usage":{"input_tokens":20,"output_tokens":3}}}
{"type":"result","subtype":"success","is_error":false,"result":"Солнечно."}""".trim()

    @Test fun answerComesFromTheStreamWithItsWebActivityAndUsage() {
        if (windows) return
        fixture().use { f ->
            f.script(answer)
            assertEquals("Солнечно.", f.complete())
            assertTrue(f.home.resolve("cwd").readText().trim().endsWith("/chat"), "No project directory takes part")
            val args = f.home.resolve("args").readLines()
            assertEquals("high", args[args.indexOf("--effort") + 1])
            assertTrue(f.steps.any { it.tool == "WebSearch" }, "The web search is shown as activity: ${f.steps}")
            val tokens = f.usage.single().tokens
            assertEquals(30L, tokens.input); assertEquals(5L, tokens.output)
            assertTrue(f.home.resolve("chat").listFiles().orEmpty().none { it.name.startsWith("system-") }, "The system prompt file is removed")
        }
    }

    @Test fun conversationArrivesAsOneUserMessageWithImages() {
        if (windows) return
        fixture().use { f ->
            f.script(answer)
            f.complete(buildJsonArray {
                addJsonObject { put("type", "text"); put("text", "Пользователь: что на картинке?") }
                addJsonObject { put("type", "image"); put("url", "data:image/png;base64,iVBORw0K") }
            })
            val message = Json.parseToJsonElement(f.home.resolve("stdin").readText().trim()).jsonObject
            assertEquals("user", message["type"]!!.jsonPrimitive.content)
            val content = message["message"]!!.jsonObject["content"]!!.jsonArray.map { it.jsonObject }
            assertEquals("Пользователь: что на картинке?", content[0]["text"]!!.jsonPrimitive.content)
            val source = content[1]["source"]!!.jsonObject
            assertEquals("image/png", source["media_type"]!!.jsonPrimitive.content)
            assertEquals("iVBORw0K", source["data"]!!.jsonPrimitive.content)
        }
    }

    @Test fun signedOutCliIsReportedAsTheFailureSignInResolves() {
        if (windows) return
        fixture().use { f ->
            f.script("""{"type":"result","subtype":"success","is_error":true,"result":"Not logged in · Please run /login"}""", "exit 1")
            val failure = assertFailsWith<NativeCompletionFailure> { f.complete() }
            assertTrue(failure.signedOut)
            assertContains(failure.message.orEmpty(), "не авторизован")
        }
    }

    @Test fun anAnswerThatNeverEndsTimesOutAndStopsTheChild() {
        if (windows) return
        fixture().use { f ->
            f.script("""{"type":"system","subtype":"init","session_id":"n"}""", "exec sleep 30")
            val failure = assertFailsWith<NativeCompletionFailure> { f.complete(timeoutSeconds = 2) }
            assertContains(failure.message.orEmpty(), "не ответил вовремя")
            val pid = f.home.resolve("pid").readText().trim().toLong()
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "The CLI must not outlive the request")
        }
    }

    @Test fun exitWithoutAResultIsAFailureNotAnEmptyAnswer() {
        if (windows) return
        fixture().use { f ->
            f.script("", "exit 3")
            val failure = assertFailsWith<NativeCompletionFailure> { f.complete() }
            assertContains(failure.message.orEmpty(), "без ответа")
            assertEquals(listOf("completion_unfinished"), f.errors.toList())
        }
    }
}
