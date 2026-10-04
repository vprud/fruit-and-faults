package org.fruitandfaults.build

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.util.Base64

/** Reads untrusted installer state only inside an owned, deadline-bound process. */
class InstallerFileReader(
    private val runner: CommandRunner = BoundedCommandRunner(5, 2_097_152),
) {
    fun read(
        path: Path,
        limit: Int = 1_048_576,
    ): ByteArray {
        require(limit in 1..1_048_576)
        val (before, output) = probe(path, limit, "bytes")
        val bytes =
            try {
                Base64.getDecoder().decode(output)
            } catch (_: IllegalArgumentException) {
                error("Invalid installer read-worker response.")
            }
        check(bytes.size <= limit && bytes.size.toLong() == before.size) { "Installer state exceeded its byte limit or changed." }
        return bytes
    }

    fun hash(path: Path): String {
        val output = probe(path, 512 * 1_048_576, "hash").second
        check(output.matches(Regex("[a-f0-9]{64}"))) { "Invalid installer digest-worker response." }
        return output
    }

    /** Only the child opens source bytes; CREATE_NEW prevents overwriting any destination. */
    fun copy(
        source: Path,
        destination: Path,
        expectedHash: String = hash(source),
    ) {
        require(expectedHash.matches(Regex("[a-f0-9]{64}")))
        val target = CliPlatform.absolute(destination)
        safePath(target)
        val parentKey = directoryKey(target.parent)
        check(!Files.exists(target, NOFOLLOW_LINKS)) { "Copy destination already exists; no files replaced." }
        val result = probe(source, 512 * 1_048_576, "copy", listOf(target.toString(), parentKey, expectedHash)).second.split(' ')
        check(result.size == 2 && result[1] == expectedHash) { "Invalid copy-worker content response." }
        val key = Base64.getDecoder().decode(result[0]).toString(Charsets.UTF_8)
        safePath(target)
        check(directoryKey(target.parent) == parentKey && installerFileStamp(target, 512 * 1_048_576).key == key) {
            "Copy destination identity changed. Preserve it before retrying."
        }
        check(hash(target) == expectedHash) { "Copied bytes changed. Preserve them before retrying." }
        check(installerFileStamp(target, 512 * 1_048_576).key == key && directoryKey(target.parent) == parentKey) {
            "Copy destination changed after verification."
        }
    }

    private fun probe(
        path: Path,
        limit: Int,
        mode: String,
        extraArguments: List<String> = emptyList(),
    ): Pair<FileStamp, String> {
        val normalized = CliPlatform.absolute(path)
        safePath(normalized)
        val before = installerFileStamp(normalized, limit)
        val javaHome = Path.of(System.getProperty("java.home"))
        val javaExecutable = javaHome.resolve(if (System.getProperty("os.name").startsWith("Windows")) "bin/java.exe" else "bin/java")
        val workerClasses =
            Path.of(
                InstallerReadWorker::class.java.protectionDomain.codeSource.location
                    .toURI(),
            )
        val kotlinClasses =
            Path.of(
                kotlin.Unit::class.java.protectionDomain.codeSource.location
                    .toURI(),
            )
        val result =
            runner.run(
                listOf(
                    javaExecutable.toString(),
                    "-Duser.home=${normalized.parent}",
                    "-XX:-UsePerfData",
                    "-cp",
                    "$workerClasses${java.io.File.pathSeparator}$kotlinClasses",
                    InstallerReadWorker::class.java.name,
                    normalized.toString(),
                    before.key,
                    before.size.toString(),
                    before.modified,
                    limit.toString(),
                    mode,
                ) + extraArguments,
                emptyMap(),
            )
        check(result.exitCode == 0 && !result.timedOut && !result.truncated) {
            "Could not safely read installer state (changed, unsupported, or timed out). Preserve it and retry."
        }
        safePath(normalized)
        check(
            installerFileStamp(normalized, limit) == before,
        ) { "Installer state was replaced or changed during read. No mutation performed." }
        return before to result.output
    }

    fun text(
        path: Path,
        limit: Int = 1_048_576,
    ): String = read(path, limit).toString(Charsets.UTF_8)
}

private fun directoryKey(path: Path): String {
    safePath(path)
    val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    check(attrs.isDirectory && !attrs.isSymbolicLink) { "Copy parent must be a regular directory." }
    return checkNotNull(attrs.fileKey()) { "Provider cannot identify the copy parent." }.toString()
}

internal data class FileStamp(
    val key: String,
    val size: Long,
    val modified: String,
)

internal fun installerFileStamp(
    path: Path,
    limit: Int = 1_048_576,
): FileStamp {
    val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    check(attrs.isRegularFile && !attrs.isSymbolicLink && attrs.size() <= limit) {
        "Installer state must be a regular no-follow file under $limit bytes; special files are not supported."
    }
    return FileStamp(
        checkNotNull(attrs.fileKey()) { "Provider cannot safely identify installer state." }.toString(),
        attrs.size(),
        attrs.lastModifiedTime().toString(),
    )
}

/** A substitution between metadata inspection and open can block only this bounded child. */
object InstallerReadWorker {
    @JvmStatic fun main(arguments: Array<String>) {
        try {
            val path = Path.of(arguments[0])
            val limit = arguments[4].toInt()
            val mode = arguments[5]
            require(mode in setOf("bytes", "hash", "copy"))
            require(limit in 1..(if (mode == "bytes") 1_048_576 else 512 * 1_048_576))
            val expected = FileStamp(arguments[1], arguments[2].toLong(), arguments[3])
            safePath(path)
            check(installerFileStamp(path, limit) == expected)
            if (mode == "copy") {
                System.out.print(copyLimited(path, expected, Path.of(arguments[6]), arguments[7], arguments[8], limit))
                return
            }
            val first = readLimited(path, limit, mode)
            safePath(path)
            check(installerFileStamp(path, limit) == expected)
            val second = readLimited(path, limit, mode)
            safePath(path)
            check(installerFileStamp(path, limit) == expected && first == second)
            System.out.print(first)
        } catch (_: Exception) {
            System.err.print("Installer state read rejected.")
            kotlin.system.exitProcess(2)
        }
    }

    private fun copyLimited(
        source: Path,
        expected: FileStamp,
        target: Path,
        parentKey: String,
        expectedHash: String,
        limit: Int,
    ): String {
        safePath(target)
        check(directoryKey(target.parent) == parentKey)
        val sourceAttrs = Files.readAttributes(source, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        val permissions =
            Files
                .getFileAttributeView(source, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
                ?.readAttributes()
                ?.permissions()
        var key = ""
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        Files.newByteChannel(source, setOf(READ, NOFOLLOW_LINKS)).use { input ->
            check(installerFileStamp(source, limit) == expected)
            Files.newDirectoryStream(target.parent).use { directory ->
                check(directoryKey(target.parent) == parentKey)
                val secure = directory as? SecureDirectoryStream<Path>
                val options = setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS)
                val channel = secure?.newByteChannel(target.fileName, options) ?: Files.newByteChannel(target, options)
                channel.use { output ->
                    val basic =
                        secure?.getFileAttributeView(target.fileName, BasicFileAttributeView::class.java, NOFOLLOW_LINKS)
                            ?: Files.getFileAttributeView(target, BasicFileAttributeView::class.java, NOFOLLOW_LINKS)
                    key = checkNotNull(basic.readAttributes().fileKey()).toString()
                    var count = 0L
                    val buffer = java.nio.ByteBuffer.allocate(16_384)
                    while (input.read(buffer) >= 0) {
                        buffer.flip()
                        count += buffer.remaining()
                        check(count <= limit)
                        digest.update(buffer.asReadOnlyBuffer())
                        while (buffer.hasRemaining()) output.write(buffer)
                        buffer.clear()
                    }
                    check(count == expected.size && installerFileStamp(source, limit) == expected)
                    val hash = digest.digest().joinToString("") { "%02x".format(it) }
                    check(hash == expectedHash)
                    check(basic.readAttributes().fileKey().toString() == key && directoryKey(target.parent) == parentKey)
                    if (permissions != null) {
                        val posix =
                            secure?.getFileAttributeView(target.fileName, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
                                ?: Files.getFileAttributeView(target, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
                        posix?.setPermissions(permissions)
                    }
                    basic.setTimes(sourceAttrs.lastModifiedTime(), null, null)
                    check(basic.readAttributes().fileKey().toString() == key && directoryKey(target.parent) == parentKey)
                    return Base64.getEncoder().encodeToString(key.toByteArray(Charsets.UTF_8)) + " " + hash
                }
            }
        }
    }

    private fun readLimited(
        path: Path,
        limit: Int,
        mode: String,
    ): String {
        val bytes = java.io.ByteArrayOutputStream()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var count = 0L
        Files.newByteChannel(path, setOf(READ, NOFOLLOW_LINKS)).use { channel ->
            val buffer = java.nio.ByteBuffer.allocate(8192)
            while (channel.read(buffer) >= 0) {
                buffer.flip()
                count += buffer.remaining()
                check(count <= limit)
                if (mode == "hash") digest.update(buffer) else bytes.write(buffer.array(), 0, buffer.remaining())
                buffer.clear()
            }
        }
        return if (mode == "hash") {
            digest.digest().joinToString("") { "%02x".format(it) }
        } else {
            Base64.getEncoder().encodeToString(bytes.toByteArray())
        }
    }
}
