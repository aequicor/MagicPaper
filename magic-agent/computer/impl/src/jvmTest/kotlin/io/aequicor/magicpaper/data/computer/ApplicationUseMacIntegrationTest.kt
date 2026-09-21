package io.aequicor.magicpaper.data.computer

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*

/** Real AX/ScreenCaptureKit, no LLM or user documents. Run only on a prepared interactive Mac. */
class ApplicationUseMacIntegrationTest {
    @Test(timeout = 120_000) fun backgroundActionsAndOccludedCapturePreserveSharedDesktop() = runBlocking {
        assumeTrue("Native window acceptance is opt-in", System.getProperty("magicpaper.application.native") == "true")
        assumeTrue("Requires macOS 14+", System.getProperty("os.name").startsWith("Mac") && NativeApplicationDesktop.supported)
        val directory = Files.createTempDirectory("application-native-fixture-")
        try {
            // Retain only the bundled binary at a stable path so a prepared host can keep its TCC identity.
            val installation = Path.of(checkNotNull(System.getProperty("magicpaper.application.testInstallation")))
            NativeApplicationDesktop(installation).use { native ->
                // Fail an explicitly enabled acceptance run, rather than turning denied TCC into a pass/skip.
                // This preflight does NOT request permissions, enumerate windows or launch the fixture.
                val permissions = native.request(request("permissions")) {}
                assertEquals(buildJsonObject { put("accessibility", true); put("screen_capture", true) }, permissions,
                    "Native test host requires existing TCC Accessibility and Screen Recording grants; helper installation: $installation")
                val source = directory.resolve("fixture.swift")
                val binary = directory.resolve("fixture")
                javaClass.getResourceAsStream("/computer/application-fixture.swift").use { stream ->
                    Files.write(source, checkNotNull(stream).readBytes())
                }
                val compiler = ProcessBuilder("xcrun", "swiftc", source.toString(), "-o", binary.toString())
                    .redirectErrorStream(true).redirectOutput(directory.resolve("compiler.log").toFile()).start()
                try {
                    assertTrue(compiler.waitFor(45, TimeUnit.SECONDS), "Fixture compiler timed out")
                    assertEquals(0, compiler.exitValue(), "Native fixture compilation failed: " + Files.readString(directory.resolve("compiler.log")).take(2000))
                } finally { compiler.destroyForcibly() }
                val title = "MagicPaper-native-fixture-${UUID.randomUUID()}"
                NativeApplicationDesktop(launchProcess = {
                    ProcessBuilder(binary.toString(), title).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                }).use { fixture ->
                    val before = fixture.request(request("snapshot")) {}
                    val desktop = FakeComputerDesktop()
                    val computer = testComputer(desktop, applicationFactory = { native })
                    computer.configure(io.aequicor.magicpaper.domain.ComputerAccess.OFF, io.aequicor.magicpaper.domain.ComputerAccess.CONTROL)
                    val epoch = computer.begin("fixture", "test-run")!!
                    try {
                        suspend fun call(action: String, fields: JsonObjectBuilder.() -> Unit = {}): JsonObject {
                            val result = computer.executeApplication("fixture", epoch, request(action, fields))
                            assertFalse(result.failed(), "Native application action failed: $action")
                            return result
                        }
                        val windows = call("windows").body()["windows"]!!.jsonArray
                        val window = windows.single { it.jsonObject["title"]?.jsonPrimitive?.content == title }
                            .jsonObject["window_id"]!!.jsonPrimitive.content
                        suspend fun inspect() = call("inspect") { put("window_id", window) }
                        val initial = inspect()
                        assertFalse(initial.toString().contains("fixture-password-must-not-leak"), "Secure field leaked")
                        fun element(inspection: JsonObject, action: String) = inspection.body()["elements"]!!.jsonArray
                            .single { node ->
                                val item = node.jsonObject
                                item["actions"]!!.jsonArray.contains(JsonPrimitive(action)) &&
                                    (action != "invoke" || item["name"] == JsonPrimitive("Increment fixture"))
                            }
                            .jsonObject["element_id"]!!.jsonPrimitive.content
                        suspend fun mutate(action: String, inspection: JsonObject, text: String? = null) = call(action) {
                            put("window_id", window); put("snapshot_id", inspection.snapshotId())
                            put("element_id", element(inspection, action)); text?.let { put("text", it) }
                        }
                        val unicode = "Привет ✨ native fixture"
                        val afterText = mutate("set_value", initial, unicode)
                        assertEquals(unicode, fixture.request(request("snapshot")) {}["text"]!!.jsonPrimitive.content)
                        mutate("invoke", afterText)
                        assertEquals(1, fixture.request(request("snapshot")) {}["count"]!!.jsonPrimitive.int)
                        val duplicate = computer.executeApplication("fixture", epoch, request("invoke") {
                            put("window_id", window); put("snapshot_id", afterText.snapshotId()); put("element_id", element(afterText, "invoke"))
                        })
                        assertTrue(duplicate.failed(), "Snapshot must be single-use")
                        // Independent target-side changes invalidate retained native fingerprints too.
                        val stale = inspect()
                        fixture.request(request("edit")) {}
                        val staleResult = computer.executeApplication("fixture", epoch, request("set_value") {
                            put("window_id", window); put("snapshot_id", stale.snapshotId())
                            put("element_id", element(stale, "set_value")); put("text", "Must not overwrite newer input")
                        })
                        assertTrue(staleResult.failed())
                        assertEquals("Edited independently", fixture.request(request("snapshot")) {}["text"]!!.jsonPrimitive.content)
                        val screenshot = call("screenshot") { put("window_id", window) }
                        val png = screenshot["content"]!!.jsonArray.single { it.jsonObject["type"] == JsonPrimitive("image") }
                            .jsonObject["data"]!!.jsonPrimitive.content
                        val image = ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(png)))
                        assertNotNull(image)
                        assertTrue(image.width in 1..1600 && image.height in 1..1600)
                        // Target is red; an exactly overlapping blue window is above it. A desktop crop fails this check.
                        val rgb = image.getRGB(image.width / 2, image.height * 9 / 10)
                        assertTrue((rgb shr 16 and 255) > 180 && (rgb and 255) < 80, "Capture must contain the occluded red window, not its blue cover")
                        assertEquals(0, desktop.captures)
                        assertTrue(desktop.performed.isEmpty())
                        val after = fixture.request(request("snapshot")) {}
                        for (key in listOf("foreground", "mouse_x", "mouse_y", "clipboard_sequence")) {
                            assertEquals(before[key], after[key], "Shared desktop changed: $key (do not move the pointer during this short check)")
                        }
                        assertEquals(1, after["count"]!!.jsonPrimitive.int, "No repeated effect")
                        fixture.request(request("close")) {}
                        assertTrue(computer.executeApplication("fixture", epoch, request("inspect") { put("window_id", window) }).failed())
                    } finally { computer.disable() }
                }
            }
        } finally { directory.toFile().deleteRecursively() }
    }

    private fun JsonObject.body() = Json.parseToJsonElement(get("content")!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content).jsonObject
}
