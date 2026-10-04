package org.fruitandfaults.build

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
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

    private fun probe(
        path: Path,
        limit: Int,
        mode: String,
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
                ),
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
            require(mode in setOf("bytes", "hash"))
            require(limit in 1..(if (mode == "hash") 512 * 1_048_576 else 1_048_576))
            val expected = FileStamp(arguments[1], arguments[2].toLong(), arguments[3])
            safePath(path)
            check(installerFileStamp(path, limit) == expected)
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
