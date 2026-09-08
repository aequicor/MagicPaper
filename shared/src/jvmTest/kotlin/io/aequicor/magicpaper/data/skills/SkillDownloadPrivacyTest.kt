package io.aequicor.magicpaper.data.skills

import java.net.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Tests exact plaintext bytes passed to TLS, not a claim of packet capture or live server inspection. */
class SkillDownloadPrivacyTest {
    @Test fun completeOutboundPayloadHasOnlyPublicPathAndFixedHeadersEvenWithAmbientCredentials() {
        val transport = SkillPublicDownload(setOf("https://api.github.com", "https://codeload.github.com"))
        val markers = listOf("PRIVATE_PROJECT_CODE_7c12", "PRIVATE_CHAT_7c12", "PRIVATE_EXPERIENCE_7c12", "PRIVATE_LLM_KEY_7c12")
        val output = Files.createDirectories(Path.of("build/reports/skills-catalog-privacy"))
        val data = Files.createTempDirectory(output, "private-fixture-")
        markers.forEachIndexed { i, marker -> Files.writeString(data.resolve("private-$i.txt"), marker) }
        val previousCookies = CookieHandler.getDefault()
        val previousAuth = Authenticator.getDefault()
        var ambientReads = 0
        try {
            CookieHandler.setDefault(object : CookieHandler() {
                override fun get(uri: URI, headers: MutableMap<String, MutableList<String>>): MutableMap<String, MutableList<String>> {
                    ambientReads++; return mutableMapOf("Cookie" to markers.toMutableList())
                }
                override fun put(uri: URI, headers: MutableMap<String, MutableList<String>>) { ambientReads++ }
            })
            Authenticator.setDefault(object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    ambientReads++; return PasswordAuthentication("private-user", markers.last().toCharArray())
                }
            })
            val urls = listOf("https://api.github.com/repos/owner/repo/commits/HEAD",
                "https://codeload.github.com/owner/repo/zip/${"a".repeat(40)}")
            val payloads = urls.map { url ->
                val uri = URI(url)
                val actual = transport.requestBytes(uri).toString(Charsets.US_ASCII)
                val expected = "GET ${uri.rawPath} HTTP/1.1\r\nHost: ${uri.host}\r\nUser-Agent: MagicPaper-Skill-Catalog/1\r\nAccept: */*\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n"
                assertEquals(expected, actual, "Complete socket payload, not a subset of headers")
                assertEquals("", actual.substringAfter("\r\n\r\n"), "No request body")
                markers.forEach { assertFalse(actual.contains(it)) }
                for (header in listOf("Authorization:", "Cookie:", "Referer:", "Content-Length:", "Proxy-Authorization:")) assertFalse(actual.contains(header))
                actual
            }
            assertEquals(0, ambientReads)
            Files.writeString(output.resolve("outbound-http.txt"), payloads.joinToString("\n---\n"))
            Files.writeString(output.resolve("assertions.txt"), "PASS: exact GET request bytes for API and ZIP; no body; no private markers; no credential/cookie callbacks; no Authorization, Cookie, Referer, Content-Length or Proxy-Authorization. Synthetic data; no live packet capture.\n")
        } finally {
            CookieHandler.setDefault(previousCookies); Authenticator.setDefault(previousAuth)
            data.toFile().deleteRecursively()
        }
    }

    @Test fun credentialQueryAndFragmentChannelsRejectedBeforeSerialization() {
        val transport = SkillPublicDownload(setOf("https://api.github.com"))
        for (url in listOf("https://user:secret@api.github.com/repos/a/b", "https://api.github.com/repos/a/b?token=secret",
            "https://api.github.com/repos/a/b#chat", "http://api.github.com/repos/a/b", "https://unapproved.example/a/b")) {
            assertFailsWith<IllegalArgumentException>(url) { transport.requestBytes(URI(url)) }
        }
    }
}
