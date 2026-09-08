package io.aequicor.magicpaper.data.skills

import java.io.InputStream
import java.net.*
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLContext

/** Dedicated anonymous GET-only transport. Has no project, chat, credentials, cookies or proxy.
 * DNS result is checked and the socket connects to that exact address, preserving TLS hostname checks.
 */
class SkillPublicDownload(approvedOrigins: Set<String>) {
    private val approved = approvedOrigins.toSet()
    data class Download(val bytes: ByteArray, val finalUrl: String)

    fun fetch(url: String): Download {
        var uri = URI(url)
        repeat(4) { redirect ->
            validateUrl(uri)
            val resolution = resolver.submit<Array<InetAddress>> { InetAddress.getAllByName(uri.host) }
            val addresses = try { resolution.get(10, java.util.concurrent.TimeUnit.SECONDS) }
            finally { resolution.cancel(true) }
            require(addresses.isNotEmpty() && addresses.all(::isPublicAddress)) { "Non-public source address" }
            val raw = Socket()
            val deadline = java.util.Timer("skill-download-deadline", true)
            deadline.schedule(object : java.util.TimerTask() { override fun run() { raw.close() } }, 60_000)
            try {
                raw.connect(InetSocketAddress(addresses.first(), 443), 10_000)
                raw.soTimeout = 15_000
                require(isPublicAddress(raw.inetAddress))
                val factory = SSLContext.getInstance("TLS").apply { init(emptyArray(), null, null) }.socketFactory
                (factory.createSocket(raw, uri.host, 443, true) as SSLSocket).use { socket ->
                    socket.sslParameters = socket.sslParameters.apply {
                        endpointIdentificationAlgorithm = "HTTPS"
                        serverNames = listOf(SNIHostName(uri.host))
                    }
                    socket.startHandshake()
                    val path = uri.rawPath.ifEmpty { "/" }
                    socket.outputStream.write("GET $path HTTP/1.1\r\nHost: ${uri.host}\r\nAccept: application/zip\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    socket.outputStream.flush()
                    val input = socket.inputStream.buffered()
                    val status = line(input).split(' ').getOrNull(1)?.toIntOrNull() ?: error("Invalid HTTP status")
                    val headers = mutableMapOf<String, String>()
                    var headerBytes = 0
                    while (true) {
                        val line = line(input)
                        headerBytes += line.length + 2
                        require(headerBytes <= 32 * 1024)
                        if (line.isEmpty()) break
                        val name = line.substringBefore(':').lowercase()
                        require(':' in line && name !in headers) { "Ambiguous HTTP headers" }
                        headers[name] = line.substringAfter(':').trim()
                    }
                    if (status in setOf(301, 302, 303, 307, 308)) {
                        require(redirect < 3) { "Too many redirects" }
                        uri = uri.resolve(headers["location"] ?: error("Missing redirect"))
                    } else {
                        require(status == 200) { "Package download failed: HTTP $status" }
                        require(headers["content-encoding"] in listOf(null, "identity"))
                        val transfer = headers["transfer-encoding"]?.lowercase()
                        val length = headers["content-length"]?.toLongOrNull()
                        require(transfer in listOf(null, "chunked") && !(transfer != null && length != null))
                        val bytes = if (transfer == "chunked") {
                            val out = java.io.ByteArrayOutputStream()
                            while (true) {
                                val size = line(input).substringBefore(';').toLong(16)
                                require(size >= 0 && size <= MAX_DOWNLOAD - out.size()) { "Download too large" }
                                if (size == 0L) break
                                val chunk = input.readNBytes(size.toInt())
                                require(chunk.size.toLong() == size && line(input).isEmpty()) { "Truncated chunk" }
                                out.write(chunk)
                            }
                            out.toByteArray()
                        } else {
                            require(length == null || length in 0..MAX_DOWNLOAD.toLong()) { "Download too large" }
                            input.readNBytes((length?.toInt() ?: MAX_DOWNLOAD) + 1).also {
                                require(it.size <= MAX_DOWNLOAD && (length == null || it.size.toLong() == length)) { "Truncated or oversized response" }
                            }
                        }
                        return Download(bytes, uri.toASCIIString())
                    }
                }
            } finally { deadline.cancel(); raw.close() }
        }
        error("Too many redirects")
    }

    internal fun validateUrl(uri: URI) {
        require(uri.scheme == "https" && uri.host != null && uri.port in setOf(-1, 443) &&
            uri.rawUserInfo == null && uri.rawFragment == null && uri.rawQuery == null &&
            "https://${uri.host.lowercase()}" in approved) { "Source must be explicitly approved public HTTPS without credentials or query" }
        require(uri.toASCIIString().none { it == '\r' || it == '\n' })
    }

    companion object {
        const val MAX_DOWNLOAD = 5 * 1024 * 1024
        private val resolver = java.util.concurrent.ThreadPoolExecutor(
            2, 2, 30, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.ArrayBlockingQueue(16),
            java.util.concurrent.ThreadFactory { task -> Thread(task, "skill-source-dns").apply { isDaemon = true } },
        ).apply { allowCoreThreadTimeOut(true) }
        private fun line(input: InputStream): String {
            val out = StringBuilder()
            while (true) {
                val c = input.read()
                require(c >= 0 && out.length <= 8192) { "Invalid HTTP line" }
                if (c == 13) { require(input.read() == 10); return out.toString() }
                require(c in 32..126)
                out.append(c.toChar())
            }
        }
        internal fun isPublicAddress(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
            val b = address.address.map { it.toInt() and 255 }
            if (b.size == 4) return !(b[0] in setOf(0, 10, 127) || b[0] >= 224 ||
                b[0] == 100 && b[1] in 64..127 || b[0] == 169 && b[1] == 254 ||
                b[0] == 172 && b[1] in 16..31 || b[0] == 192 && (b[1] in setOf(0, 168) || b[1] == 88 && b[2] == 99) ||
                b[0] == 198 && (b[1] in 18..19 || b[1] == 51 && b[2] == 100) || b[0] == 203 && b[1] == 0 && b[2] == 113)
            // Conservative global-unicast allowlist; excludes mapped, NAT64, Teredo, 6to4 and documentation space.
            return b.size == 16 && b[0] in 0x20..0x3f &&
                !(b[0] == 0x20 && b[1] == 0x01 && (b[2] < 2 || b[2] == 0x0d && b[3] == 0xb8)) &&
                !(b[0] == 0x20 && b[1] == 0x02) && !(b[0] == 0x3f && b[1] == 0xff)
        }
    }
}
