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
        fun script(stream: String, tail: String = "", before: String = "") {
            binary.writeText("#!/bin/sh\nD=\"${home.path}\"\nprintf '%s\\n' \"\$@\" > \"\$D/args\"\npwd > \"\$D/cwd\"\n" +
                "echo \$\$ > \"\$D/pid\"\ncat > \"\$D/stdin\"\n$before\ncat <<'JSON'\n$stream\nJSON\n$tail\n")
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

    @Test fun answerPassesClaudePlanWindowsAlongsideTokenUsage() {
        if (windows) return
        fixture().use { f ->
            f.script("""{"type":"rate_limit_event","rate_limit_info":{"status":"allowed","unifiedWindows":{"five_hour":{"utilization":0.12,"resetsAt":1790269200},"seven_day":{"utilization":0.25,"resetsAt":1790856000}}}}
{"type":"assistant","message":{"id":"m1","content":[{"type":"text","text":"Готово."}],"usage":{"input_tokens":10,"output_tokens":2}}}
{"type":"result","subtype":"success","is_error":false,"result":"Готово."}""")
            assertEquals("Готово.", f.complete())
            assertEquals(12L, f.usage.single().tokens.totalTokens)
            assertEquals(listOf("five_hour", "seven_day"), f.usage.single().planUsage?.windows?.map { it.id })
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

    @Test fun imageTypeIsTheBytesAndAnUnsupportedFormatIsNamedInsteadOfFailingTheAnswer() {
        if (windows) return
        fixture().use { f ->
            f.script(answer)
            f.complete(buildJsonArray {
                addJsonObject { put("type", "text"); put("text", "Пользователь: сравни") }
                // A JPEG sent as image/png would be refused by the API as a mismatched media type.
                addJsonObject { put("type", "image"); put("url", "data:image/png;base64,/9j/4AAQ") }
                addJsonObject { put("type", "image"); put("url", "data:image/bmp;base64,Qk0AAA==") }
            })
            val content = Json.parseToJsonElement(f.home.resolve("stdin").readText().trim()).jsonObject["message"]!!
                .jsonObject["content"]!!.jsonArray.map { it.jsonObject }
            assertEquals("image/jpeg", content[1]["source"]!!.jsonObject["media_type"]!!.jsonPrimitive.content)
            assertEquals("text", content[2]["type"]!!.jsonPrimitive.content)
            assertContains(content[2]["text"]!!.jsonPrimitive.content, "PNG, JPEG, GIF или WebP")
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

    /** A profile's default timeout is 0; read as a one-second limit it failed every real answer, session titles included. */
    @Test fun zeroTimeoutWaitsForASlowAnswer() {
        if (windows) return
        fixture().use { f ->
            f.script(answer, before = "sleep 2")
            assertEquals("Солнечно.", f.complete(timeoutSeconds = 0))
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
