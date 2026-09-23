package io.aequicor.magicpaper.data.research

import kotlin.test.*

/**
 * A batch check runs through `cmd.exe`, which parses its own command line instead of splitting it like other programs.
 * Regression: every switch was quoted (`"/d" "/s" "/c"`), so the text after `/c` began with the stray closing quote of
 * `"/c"`; stripping the outer quotes then glued the script's path to its arguments, and `gradlew.bat` on Windows failed
 * at once with «Системе не удается найти указанный путь» (exit 1). The line now has the shape Node.js uses:
 * bare switches and one quoted command.
 */
class WindowsBatchCommandLineTest {
    /** What `cmd /s /c` runs, per its documentation: the first quote of the string after `/c` and the last quote go. */
    private fun commandRunByCmd(line: String): String {
        val switch = Regex("""(?i)(^|\s)/c(\s|$)""").find(line) ?: fail("cmd would not see /c in: $line")
        val string = line.substring(switch.range.last + 1).trimStart()
        assertTrue(string.startsWith('"'), "the command after /c is one quoted string: $string")
        return string.substring(1, string.lastIndexOf('"')) + string.substring(string.lastIndexOf('"') + 1)
    }

    @Test fun batchCheckRunsTheScriptWithItsOwnArguments() {
        val (application, line) = WindowsResearchSandbox.commandLine(
            listOf("C:\\Users\\dev\\.MagicPaper\\task-worktrees\\slot\\gradlew.bat", ":app:jvmTest", "--tests", "Suite Test", "--offline"),
            systemRoot = "C:\\Windows")
        assertEquals("C:\\Windows\\System32\\cmd.exe", application)
        assertEquals("\"C:\\Windows\\System32\\cmd.exe\" /d /s /c " +
            "\"\"C:\\Users\\dev\\.MagicPaper\\task-worktrees\\slot\\gradlew.bat\" \":app:jvmTest\" \"--tests\" \"Suite Test\" \"--offline\"\"", line)
        assertEquals("\"C:\\Users\\dev\\.MagicPaper\\task-worktrees\\slot\\gradlew.bat\" \":app:jvmTest\" \"--tests\" \"Suite Test\" \"--offline\"",
            commandRunByCmd(line))
    }

    @Test fun cmdScriptUsesTheSameShapeAndAMissingSystemRootFallsBackToWindows() {
        val (application, line) = WindowsResearchSandbox.commandLine(listOf("C:\\tools\\npm.CMD", "test"), systemRoot = null)
        assertEquals("C:\\Windows\\System32\\cmd.exe", application)
        assertEquals("\"C:\\tools\\npm.CMD\" \"test\"", commandRunByCmd(line))
    }

    @Test fun launchMethodInTheLogIsTheOneTheCommandLineUses() {
        assertEquals("cmd", WindowsResearchSandbox.launchMethod("C:\\p\\gradlew.bat"))
        assertEquals("cmd", WindowsResearchSandbox.launchMethod("C:\\tools\\npm.CMD"))
        assertEquals("direct", WindowsResearchSandbox.launchMethod("C:\\Git\\cmd\\git.exe"))
    }

    @Test fun programIsStartedDirectlyWithQuotedArguments() {
        val (application, line) = WindowsResearchSandbox.commandLine(listOf("C:\\Git\\cmd\\git.exe", "status", "a b"), systemRoot = "C:\\Windows")
        assertEquals("C:\\Git\\cmd\\git.exe", application)
        assertEquals("\"C:\\Git\\cmd\\git.exe\" \"status\" \"a b\"", line)
    }

    @Test fun batchArgumentsThatCmdWouldInterpretAreRefused() {
        for (argument in listOf("a&b", "a|b", "a>b", "%PATH%", "a\"b", "!x!")) {
            assertFailsWith<IllegalArgumentException>(argument) {
                WindowsResearchSandbox.commandLine(listOf("C:\\p\\gradlew.bat", argument), systemRoot = "C:\\Windows")
            }
        }
    }
}
