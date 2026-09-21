package io.aequicor.magicpaper.domain.checks

import kotlin.test.*

class CheckTaskGitQueryTest {
    @Test fun exactReferenceGrammarAcceptsOnlyConstructedQueries() {
        val accepted = listOf(
            CheckGitReferenceQuery.REVISION.arguments("HEAD"),
            CheckGitReferenceQuery.REVISION.arguments("refs/heads/work"),
            CheckGitReferenceQuery.REVISION.arguments("refs/heads/release+fix"),
            CheckGitReferenceQuery.ANCESTOR.arguments("HEAD", "refs/heads/feature@work=(next)!"),
            CheckGitReferenceQuery.EXISTS.arguments("refs/magicpaper/task-pre-integration-one"),
            CheckGitReferenceQuery.ANCESTOR.arguments("HEAD", "abc123"),
            CheckGitReferenceQuery.DISTANCE.arguments("HEAD", "refs/heads/main"),
            CheckGitReferenceQuery.MARKER.arguments("rebase-merge"),
        ) + CheckGitReadQuery.entries.map { it.arguments() }
        accepted.forEach { assertTrue(isCheckGitReadArguments(it), it.toString()) }
        for (arguments in accepted) {
            assertFalse(isCheckGitReadArguments(arguments + "--exec=touch /tmp/foreign"))
            assertFalse(isCheckGitReadArguments(arguments.map { if (it == "git") "sh" else it }))
        }
        for (reference in listOf("--output=/tmp/out", "main\nother", "HEAD:secret", "main...other", "")) {
            assertFailsWith<IllegalArgumentException> { CheckGitReferenceQuery.REVISION.arguments(reference) }
        }
        assertFailsWith<IllegalArgumentException> { CheckGitReferenceQuery.MARKER.arguments("../../outside") }
        assertFailsWith<IllegalArgumentException> { CheckGitReferenceQuery.EXISTS.arguments("HEAD") }
        for (reference in listOf("refs/heads/.hidden", "refs/heads/work.lock", "refs/heads/main^", "refs//heads/main"))
            assertFailsWith<IllegalArgumentException> { CheckGitReferenceQuery.REVISION.arguments(reference) }
    }

    @Test fun readPolicyRejectsMutationEvenWhenArgumentsAreValidGit() {
        val prefix = CheckGitReadQuery.HEAD.arguments().dropLast(3)
        assertFalse(isCheckGitReadArguments(prefix + listOf("update-ref", "refs/heads/main", "abc")))
        assertFalse(isCheckGitReadArguments(prefix + listOf("rev-parse", "--output=/tmp/out", "HEAD")))
        assertFalse(isCheckGitReadArguments(prefix + listOf("rev-parse", "--git-path", "config")))
    }
}
