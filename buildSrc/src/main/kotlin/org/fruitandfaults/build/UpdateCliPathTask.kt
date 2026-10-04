package org.fruitandfaults.build

import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Base64
import java.util.Locale

/** The sole user-level Windows PATH boundary; tests substitute memory or an explicit file. */
interface UserPathStore {
    fun read(): String

    fun write(value: String)
}

class FileUserPathStore(
    private val path: Path,
    private val reader: InstallerFileReader = InstallerFileReader(),
) : UserPathStore {
    override fun read(): String {
        safePath(path)
        return if (Files.exists(path, NOFOLLOW_LINKS)) reader.text(path) else ""
    }

    override fun write(value: String) {
        safePath(path)
        if (Files.exists(path, NOFOLLOW_LINKS)) reader.read(path)
        atomicFile(path, value.toByteArray(Charsets.UTF_8))
    }
}

class WindowsUserPathStore(
    private val runner: CommandRunner = BoundedCommandRunner(),
    private val nativePlatform: CliPlatform = CliPlatform.detect(System.getProperty("os.name")),
) : UserPathStore {
    override fun read(): String =
        invoke(
            "[Console]::OutputEncoding=[Text.UTF8Encoding]::new(\$false); " +
                "[Console]::Write([Environment]::GetEnvironmentVariable('Path','User'))",
            emptyMap(),
        )

    override fun write(value: String) {
        invoke(
            "[Environment]::SetEnvironmentVariable('Path',\$env:FRUIT_AND_FAULTS_USER_PATH,'User')",
            mapOf("FRUIT_AND_FAULTS_USER_PATH" to value),
        )
    }

    private fun invoke(
        script: String,
        environment: Map<String, String>,
    ): String {
        check(nativePlatform == CliPlatform.WINDOWS) { "Windows PATH requires native Windows or -PcliUserPathFile=<temporary file>." }
        val result = runner.run(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script), environment)
        check(result.exitCode == 0 && !result.timedOut && !result.truncated) {
            "Could not read/update complete user-level Windows PATH. Set it manually; machine PATH was not changed."
        }
        return result.output
    }
}

/** Owns only the matching command marker and its exact marked profile block/PATH entry. */
class CliPathExposure(
    private val windowsPath: UserPathStore,
    private val reader: InstallerFileReader = InstallerFileReader(),
) {
    private val begin = "# >>> fruit-and-faults >>>"
    private val end = "# <<< fruit-and-faults <<<"

    fun add(
        target: InstallLayout,
        currentPath: String,
    ): String {
        validateWindowsRoot(target.platform, target.root)
        if (target.platform == CliPlatform.WINDOWS) {
            val marker = pathMarker(target)
            safePath(marker)
            val bin = target.root.resolve("bin").toString()
            val ownership = owner(target)
            if (Files.exists(marker, NOFOLLOW_LINKS)) check(reader.text(marker) == ownership) { "Foreign PATH ownership marker." }
            val original = windowsPath.read()
            val entries = original.split(';').toMutableList()
            val matching = entries.indices.filter { windowsKey(entries[it]) == windowsKey(bin) }
            val updated =
                if (matching.isEmpty()) {
                    (entries.filter { it.isNotEmpty() } + bin).joinToString(";")
                } else {
                    entries
                        .filterIndexed { index, _ -> index == matching.first() || index !in matching }
                        .mapIndexed { _, value ->
                            if (windowsKey(value) ==
                                windowsKey(bin)
                            ) {
                                bin
                            } else {
                                value
                            }
                        }.joinToString(";")
                }
            check(windowsPath.read() == original) { "User PATH changed during setup. Retry; new user entries were preserved." }
            atomicFile(marker, ownership.toByteArray(Charsets.UTF_8))
            if (original != updated) windowsPath.write(updated)
            return "User Windows PATH: $bin. Open a new terminal."
        }
        safePath(target.command.parent)
        val marker = commandMarker(target)
        safePath(marker)
        preflightCommand(target, marker)
        val profile = target.profile
        val before = profile?.let { readProfile(it) }
        val block = before?.let { ownedBlock(it, target) }
        val bin = target.command.parent.toString()
        val needsProfile = profile != null && block == null && currentPath.split(':').none { it == bin }
        if (needsProfile) {
            check(before!!.toString(Charsets.ISO_8859_1).indexOf(begin) < 0) {
                "Foreign or malformed profile block. Preserve it before retrying."
            }
        }
        Files.createDirectories(target.command.parent)
        safePath(target.command.parent)
        if (!Files.exists(target.command, NOFOLLOW_LINKS)) {
            Files.createSymbolicLink(target.command, target.root.resolve("bin/fruit-and-faults"))
            try {
                atomicFile(marker, owner(target).toByteArray(Charsets.UTF_8))
            } catch (failure: Exception) {
                if (Files.isSymbolicLink(target.command) &&
                    Files.readSymbolicLink(target.command) == target.root.resolve("bin/fruit-and-faults")
                ) {
                    Files.delete(target.command)
                }
                throw failure
            }
        }
        if (needsProfile) {
            val newline = if (before!!.toString(Charsets.ISO_8859_1).contains("\r\n")) "\r\n" else "\n"
            val separator = before.isNotEmpty() && before.last() != '\n'.code.toByte()
            val text = (if (separator) newline else "") + profileBlock(target, newline, separator)
            check(readProfile(profile).contentEquals(before)) { "Profile changed during setup. Retry; unrelated bytes were preserved." }
            atomicFile(profile, before + text.toByteArray(Charsets.UTF_8))
        }
        return if (profile == null) {
            "Unknown shell: manual PATH setup required. Add $bin to your shell PATH " +
                "(POSIX shells: export PATH=${quote(bin)}:\"\$PATH\")."
        } else if (needsProfile) {
            "Managed profile block added: $profile. Open a new terminal."
        } else {
            "Command available at ${target.command}. Open a new terminal if needed."
        }
    }

    fun validateRemoval(target: InstallLayout) {
        validateWindowsRoot(target.platform, target.root)
        if (target.platform == CliPlatform.WINDOWS) {
            val marker = pathMarker(target)
            safePath(marker)
            if (Files.exists(
                    marker,
                    NOFOLLOW_LINKS,
                )
            ) {
                check(reader.text(marker) == owner(target)) { "Foreign PATH marker; no user PATH change." }
            }
        } else {
            safePath(target.command.parent)
            preflightCommand(target, commandMarker(target))
            target.profile?.let { ownedBlock(readProfile(it), target) }
        }
    }

    fun remove(target: InstallLayout) {
        validateRemoval(target)
        if (target.platform == CliPlatform.WINDOWS) {
            val marker = pathMarker(target)
            if (!Files.exists(marker, NOFOLLOW_LINKS)) return
            val bin = windowsKey(target.root.resolve("bin").toString())
            val current = windowsPath.read()
            val updated = current.split(';').filter { windowsKey(it) != bin }.joinToString(";")
            check(windowsPath.read() == current) { "User PATH changed during removal. Retry; new user entries were preserved." }
            if (updated != current) windowsPath.write(updated)
            Files.delete(marker)
            return
        }
        target.profile?.let { profile ->
            val original = readProfile(profile)
            ownedBlock(original, target)?.let { range ->
                atomicFile(profile, original.copyOfRange(0, range.first) + original.copyOfRange(range.last + 1, original.size))
            }
        }
        val marker = commandMarker(target)
        if (Files.exists(marker, NOFOLLOW_LINKS)) {
            preflightCommand(target, marker)
            if (Files.exists(target.command, NOFOLLOW_LINKS)) Files.delete(target.command)
            Files.delete(marker)
        }
    }

    private fun ownedBlock(
        bytes: ByteArray,
        target: InstallLayout,
    ): IntRange? {
        val text = bytes.toString(Charsets.ISO_8859_1)
        val start = text.indexOf(begin)
        if (start < 0) {
            check(!text.contains(end)) { "Malformed profile block." }
            return null
        }
        val finish = text.indexOf(end, start)
        check(finish >= 0 && text.indexOf(begin, start + begin.length) < 0 && text.indexOf(end, finish + end.length) < 0) {
            "Malformed or duplicated profile block."
        }
        check(start == 0 || text[start - 1] == '\n') { "Profile marker must start on its own line." }
        val ending =
            if (text.startsWith("\r\n", finish + end.length)) {
                2
            } else if (text.startsWith("\n", finish + end.length)) {
                1
            } else {
                0
            }
        check(ending > 0) { "Malformed profile ending." }
        val block = text.substring(start, finish + end.length + ending)
        val ownerLine = "# owner=${encode(target.root.toString())}"
        check(block.contains("$ownerLine\n") || block.contains("$ownerLine\r\n")) {
            "Profile block belongs to another installation."
        }
        val prefixAdded = block.contains("# prefix-newline=1")
        val newline = if (ending == 2) "\r\n" else "\n"
        val expectedBlock = profileBlock(target, newline, prefixAdded).toByteArray(Charsets.UTF_8).toString(Charsets.ISO_8859_1)
        check(block == expectedBlock) { "Managed profile block was edited. Preserve your changes before uninstalling." }
        val prefix =
            if (prefixAdded) {
                if (start >= 2 && text.substring(start - 2, start) == "\r\n") {
                    2
                } else {
                    1
                }
            } else {
                0
            }
        check(start >= prefix && (!prefixAdded || text.substring(start - prefix, start) == newline)) { "Malformed profile separator." }
        return (start - prefix)..(finish + end.length + ending - 1)
    }

    private fun readProfile(profile: Path): ByteArray {
        safePath(profile)
        if (!Files.exists(profile, NOFOLLOW_LINKS)) return ByteArray(0)
        return reader.read(profile)
    }

    private fun profileBlock(
        target: InstallLayout,
        newline: String,
        separator: Boolean,
    ): String =
        begin + newline +
            "# owner=${encode(target.root.toString())}" + newline + "# prefix-newline=${if (separator) 1 else 0}" + newline +
            "export PATH=${quote(target.command.parent.toString())}:\"\$PATH\"" + newline + end + newline

    private fun preflightCommand(
        target: InstallLayout,
        marker: Path,
    ) {
        safePath(marker)
        if (Files.exists(marker, NOFOLLOW_LINKS)) {
            check(reader.text(marker) == owner(target)) { "Foreign command ownership marker." }
            check(
                !Files.exists(target.command, NOFOLLOW_LINKS) ||
                    (
                        Files.isSymbolicLink(target.command) &&
                            Files.readSymbolicLink(target.command) == target.root.resolve("bin/fruit-and-faults")
                    ),
            ) { "Owned command was replaced; preserve it before retrying." }
        } else {
            check(!Files.exists(target.command, NOFOLLOW_LINKS)) { "Existing command is not owned by this installer: ${target.command}." }
        }
    }

    private fun commandMarker(target: InstallLayout): Path = target.command.resolveSibling(".fruit-and-faults-command.properties")

    private fun pathMarker(target: InstallLayout): Path = target.root.resolveSibling(".${target.root.fileName}-path.properties")

    private fun owner(target: InstallLayout): String =
        "formatVersion=1\napplication=fruit-and-faults\nroot=${encode(target.root.toString())}\n"

    private fun windowsKey(text: String): String =
        text
            .trim()
            .trim('"')
            .replace('/', '\\')
            .trimEnd('\\')
            .lowercase(Locale.ROOT)

    private fun encode(text: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(Charsets.UTF_8))

    private fun quote(text: String): String = "'" + text.replace("'", "'\\''") + "'"
}

/** Same-directory staged write; the old file survives until the complete new bytes are ready. */
internal fun atomicFile(
    path: Path,
    bytes: ByteArray,
) {
    require(bytes.size <= 1_048_576) { "Installer state must remain under 1 MiB." }
    safePath(path)
    val before = if (Files.exists(path, NOFOLLOW_LINKS)) installerFileStamp(path) else null
    val permissions =
        if (Files.exists(
                path,
                NOFOLLOW_LINKS,
            )
        ) {
            Files
                .getFileAttributeView(
                    path,
                    java.nio.file.attribute.PosixFileAttributeView::class.java,
                    NOFOLLOW_LINKS,
                )?.readAttributes()
                ?.permissions()
        } else {
            null
        }
    Files.createDirectories(path.parent)
    safePath(path.parent)
    val temp = Files.createTempFile(path.parent, ".fruit-and-faults-write-", ".tmp")
    try {
        java.nio.channels.FileChannel.open(temp, java.nio.file.StandardOpenOption.WRITE).use { channel ->
            val buffer = java.nio.ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        if (permissions != null) Files.setPosixFilePermissions(temp, permissions)
        safePath(path)
        check(if (before == null) !Files.exists(path, NOFOLLOW_LINKS) else installerFileStamp(path) == before) {
            "Installer state changed during write. Preserve it and retry."
        }
        try {
            Files.move(temp, path, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp, path, REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temp)
    }
}

@DisableCachingByDefault(because = "Updates only explicitly owned user command/profile state")
abstract class UpdateCliPathTask : CliEnvironmentTask() {
    @TaskAction fun addCliToPath() {
        val target = installation()
        CliInstaller().validate(target)
        val command = if (target.platform == CliPlatform.WINDOWS) target.root.resolve("bin") else target.command
        logger.lifecycle("Command exposure: $command; profile: ${target.profile ?: "manual/user PATH"}")
        logger.lifecycle(CliPathExposure(pathStore()).add(target, currentPath.get()))
    }
}
