package io.aequicor.magicpaper

import io.aequicor.magicpaper.logging.AppLog
import kotlin.coroutines.cancellation.CancellationException
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.EnumSet
import java.util.Properties
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One owner of the user's runtime per application build. Secondary launches of the *same*
 * build forward activation before creating DI; a launch of another build (IDE run, another
 * worktree, a portable copy) never hands its start-over to a foreign runtime.
 */
internal class DesktopActivationBroker private constructor(
    private val directory: Path,
    private val channel: FileChannel,
    private val lock: FileLock,
    private val server: ServerSocket,
    private val token: String,
    private val onActivation: (List<String>) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean()
    private val accepted = LinkedHashSet<String>()
    private val listener = Thread(::listen, "magicpaper-activation").apply { isDaemon = true }

    private fun listen() {
        while (!closed.get()) {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 2_000
                    val input = DataInputStream(socket.getInputStream())
                    require(input.readInt() == PROTOCOL)
                    require(MessageDigest.isEqual(input.readBounded(128).toByteArray(), token.toByteArray()))
                    val requestId = input.readBounded(128)
                    UUID.fromString(requestId)
                    val count = input.readInt()
                    require(count in 0..MAX_URLS)
                    val links = List(count) { input.readBounded(MAX_URL_BYTES) }
                    if (requestId !in accepted) {
                        onActivation(links)
                        accepted.add(requestId)
                        AppLog.info("desktop_activation", "request_received", mapOf("requestId" to requestId, "count" to count.toString()))
                        if (accepted.size > 256) accepted.remove(accepted.first())
                    }
                    DataOutputStream(socket.getOutputStream()).apply { writeBoolean(true); flush() }
                }
            } catch (failure: Exception) {
                when {
                    closed.get() -> AppLog.debug("desktop_activation", "listener_stopped")
                    failure is IllegalArgumentException || failure is java.io.EOFException || failure is java.net.SocketTimeoutException ->
                        AppLog.debug("desktop_activation", "request_rejected", mapOf("reason" to "invalid_or_incomplete"))
                    else -> AppLog.error("desktop_activation", "receive_failed", failure, mapOf("result" to "request_unacknowledged"))
                }
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var failure: Exception? = null
        fun cleanup(action: () -> Unit) {
            try { action() } catch (next: Exception) { if (failure == null) failure = next else failure!!.addSuppressed(next) }
        }
        cleanup { server.close() }
        cleanup { Files.deleteIfExists(directory.resolve(ENDPOINT)) }
        cleanup { lock.release() }
        cleanup { channel.close() }
        failure?.let { throw it }
        AppLog.info("desktop_activation", "closed")
    }

    sealed interface Result {
        data class Primary(val broker: DesktopActivationBroker) : Result
        data object Forwarded : Result
    }

    companion object {
        private const val PROTOCOL = 1
        private const val ENDPOINT = "endpoint.properties"
        private const val MAX_URL_BYTES = 8192
        private const val MAX_URLS = 16

        fun acquire(
            directory: Path,
            initialLinks: List<String>,
            onActivation: (List<String>) -> Unit,
            timeoutMillis: Long = 10_000,
        ): Result {
            require(initialLinks.size <= MAX_URLS && initialLinks.all { it.toByteArray().size <= MAX_URL_BYTES })
            Files.createDirectories(directory)
            require(!Files.isSymbolicLink(directory)) { "Activation directory must not be a symbolic link" }
            makePrivate(directory, directory = true)
            val lockPath = directory.resolve("instance.lock")
            require(!Files.isSymbolicLink(lockPath))
            val channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            makePrivate(lockPath)
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000
            val requestId = UUID.randomUUID().toString()
            var lastFailure: Throwable? = null
            var attempt = 0
            try {
                do {
                    attempt++
                    val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                    if (lock != null) {
                        val server = ServerSocket()
                        try {
                            server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 16)
                            val token = ByteArray(32).also(SecureRandom()::nextBytes)
                                .joinToString("") { "%02x".format(it.toInt() and 255) }
                            val broker = DesktopActivationBroker(directory, channel, lock, server, token, onActivation)
                            broker.writeEndpoint()
                            broker.listener.start()
                            if (initialLinks.isNotEmpty()) onActivation(initialLinks)
                            AppLog.info("desktop_activation", "primary_acquired", mapOf("namespace" to namespaceName(directory)))
                            return Result.Primary(broker)
                        } catch (failure: Exception) {
                            try { server.close() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                            try { lock.release() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                            throw failure
                        }
                    }
                    val forwarded = forward(directory, requestId, initialLinks)
                    if (forwarded.isSuccess) {
                        channel.close()
                        AppLog.info("desktop_activation", "forwarded", mapOf(
                            "requestId" to requestId,
                            "count" to initialLinks.size.toString(),
                            // Names the owner namespace and the process that has just been asked to
                            // raise its window, so a launch that intentionally exits can be traced.
                            "namespace" to namespaceName(directory),
                            "ownerPid" to forwarded.getOrThrow().toString(),
                        ))
                        return Result.Forwarded
                    }
                    lastFailure = forwarded.exceptionOrNull()
                    AppLog.debug("desktop_activation", "forward_retry", mapOf("requestId" to requestId, "attempt" to attempt.toString(), "reason" to "owner_unavailable"))
                    Thread.sleep(50)
                } while (System.nanoTime() < deadline)
                throw IllegalStateException("MagicPaper уже запущен, но не отвечает на запрос открытия.", lastFailure)
            } catch (failure: Exception) {
                try { channel.close() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }

        /**
         * Activation namespace of one application build. Launches running the same installed
         * image or the same Gradle build output share the single runtime owner, so the native
         * `magicpaper://` handler still reaches the running instance. A different location gets
         * its own namespace: an unpackaged development launch must not silently exit because an
         * installed copy owns the user's runtime. A `null` location keeps the legacy shared
         * namespace rather than inventing a throwaway one.
         */
        fun activationDirectory(root: Path, applicationLocation: Path?): Path =
            applicationLocation?.let { root.resolve(instanceKey(it)) } ?: root

        private fun instanceKey(applicationLocation: Path): String {
            // Real path first: it collapses the different spellings the OS and launchers use
            // for one and the same installation (case, separators, short names, mount points).
            val normalized = runCatching { applicationLocation.toRealPath() }
                .getOrElse { applicationLocation.toAbsolutePath().normalize() }
                .toString().replace('\\', '/')
            val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
            return "instance-" + digest.take(6).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        private fun namespaceName(directory: Path): String = directory.fileName?.toString() ?: directory.toString()

        /** Success carries the pid of the runtime that received the activation. */
        private fun forward(directory: Path, requestId: String, links: List<String>): kotlin.Result<Long> = runCatching {
            val endpoint = directory.resolve(ENDPOINT)
            require(!Files.isSymbolicLink(endpoint) && Files.size(endpoint) < 4096)
            val properties = Properties().apply { Files.newInputStream(endpoint).use(::load) }
            require(properties.getProperty("version") == PROTOCOL.toString())
            val pid = properties.getProperty("pid").toLong()
            require(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
            val port = properties.getProperty("port").toInt()
            require(port in 1..65535)
            val token = properties.getProperty("token")
            require(token.length == 64)
            DesktopForegroundTransfer.allow(pid)
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 500)
                socket.soTimeout = 2_000
                DataOutputStream(socket.getOutputStream()).apply {
                    writeInt(PROTOCOL)
                    writeBounded(token); writeBounded(requestId); writeInt(links.size)
                    links.forEach { writeBounded(it) }
                    flush()
                }
                check(DataInputStream(socket.getInputStream()).readBoolean())
            }
            pid
        }

        private fun makePrivate(path: Path, directory: Boolean = false) {
            if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(path, buildSet {
                    add(PosixFilePermission.OWNER_READ); add(PosixFilePermission.OWNER_WRITE)
                    if (directory) add(PosixFilePermission.OWNER_EXECUTE)
                })
            } else {
                val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
                    ?: error("Private activation storage is unavailable")
                view.acl = listOf(AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                    .setPrincipal(Files.getOwner(path)).setPermissions(EnumSet.allOf(AclEntryPermission::class.java)).build())
            }
        }

        private fun DataInputStream.readBounded(maxBytes: Int): String {
            val size = readInt()
            require(size in 0..maxBytes)
            return ByteArray(size).also(::readFully).toString(Charsets.UTF_8)
        }

        private fun DataOutputStream.writeBounded(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            writeInt(bytes.size); write(bytes)
        }
    }

    private fun writeEndpoint() {
        val properties = Properties().apply {
            setProperty("version", PROTOCOL.toString())
            setProperty("pid", ProcessHandle.current().pid().toString())
            setProperty("port", server.localPort.toString())
            setProperty("token", token)
        }
        val temporary = Files.createTempFile(directory, "endpoint-", ".pending")
        try {
            makePrivate(temporary)
            Files.newOutputStream(temporary).use { properties.store(it, null) }
            try { Files.move(temporary, directory.resolve(ENDPOINT), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                AppLog.debug("desktop_activation", "endpoint_write_strategy", mapOf("strategy" to "replace", "reason" to "atomic_move_unavailable"))
                Files.move(temporary, directory.resolve(ENDPOINT), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { Files.deleteIfExists(temporary) }
    }
}

/** JNA is already used by the desktop runtime; keep Win32 references out of other platforms. */
private object DesktopForegroundTransfer {
    fun allow(pid: Long) {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        try {
            val library = Class.forName("com.sun.jna.NativeLibrary")
                .getMethod("getInstance", String::class.java).invoke(null, "user32")
            val function = library.javaClass.getMethod("getFunction", String::class.java)
                .invoke(library, "AllowSetForegroundWindow")
            function.javaClass.getMethod("invokeInt", Array<Any>::class.java)
                .invoke(function, arrayOf<Any>(pid.toInt()))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // The foreground hint is a courtesy to the OS shell, never a precondition of
            // activation: linkage and initializer errors (an unusable native library in a
            // shrunk or blocked runtime) must not turn forwarding into a startup failure.
            AppLog.error("desktop_activation", "foreground_permission_failed", failure, mapOf("result" to "activation_continues"))
        }
    }
}
