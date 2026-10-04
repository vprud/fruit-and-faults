package org.fruitandfaults.build

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.nio.file.attribute.BasicFileAttributes
import java.util.Base64
import java.util.UUID

/** Installs only a validated distribution and removes exact, manifest-owned regular entries. */
class CliInstaller(
    private val fileReader: InstallerFileReader = InstallerFileReader(),
    private val copyFile: (Path, Path) -> Unit = { from, to ->
        Files.copy(from, to, COPY_ATTRIBUTES)
        Unit
    },
) {
    companion object {
        const val MARKER = ".fruit-and-faults-install.properties"
    }

    fun install(
        distribution: Path,
        target: InstallLayout,
        version: String,
        force: Boolean = true,
    ) {
        validateWindowsRoot(target.platform, target.root)
        safePath(target.root)
        check(target.root != target.home && target.root.parent != null && !target.home.startsWith(target.root)) { "Unsafe install root." }
        require(version.matches(Regex("[A-Za-z0-9][A-Za-z0-9.+_-]{0,99}"))) { "Invalid CLI version." }
        val source = snapshot(distribution, false)
        check(MARKER !in source.files && MARKER !in source.directories) {
            "Distribution must not supply the installer's reserved ownership marker."
        }
        check(source.files.containsKey("bin/fruit-and-faults") && source.files.containsKey("bin/fruit-and-faults.bat")) {
            "Distribution needs both Unix and Windows launchers."
        }
        val old = if (Files.exists(target.root, NOFOLLOW_LINKS)) owned(target.root) else null
        if (old != null && old.version == version && old.tree.hashes() == source.hashes()) return
        check(old == null || force) { "Replacing ${target.root} requires -PcliForce=true. The marked CLI distribution will be replaced." }
        Files.createDirectories(target.root.parent)
        safePath(target.root.parent)
        val stage = Files.createTempDirectory(target.root.parent, ".fruit-and-faults-stage-")
        val stageIdentity = Files.readAttributes(stage, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
        var backup: Path? = null
        try {
            for (directory in source.directories.sortedBy { it.length }) Files.createDirectories(stage.resolve(directory))
            for (name in source.files.keys) {
                safePath(distribution.resolve(name))
                check(hash(distribution.resolve(name)) == source.files.getValue(name).hash) { "Distribution changed during staging." }
                copyFile(distribution.resolve(name), stage.resolve(name))
            }
            val staged = snapshot(stage, false)
            check(staged.hashes() == source.hashes()) { "Distribution changed during staging." }
            Files.writeString(stage.resolve(MARKER), marker(target.root, version, UUID.randomUUID().toString(), staged))
            if (old != null) {
                check(owned(target.root).bytes.contentEquals(old.bytes)) { "Installation changed before replacement." }
                backup = target.root.resolveSibling(".fruit-and-faults-backup-${UUID.randomUUID()}")
                move(target.root, backup)
            }
            try {
                publishClaimed(stage, target.root)
            } catch (failure: Exception) {
                if (backup != null && !Files.exists(target.root, NOFOLLOW_LINKS)) move(backup, target.root)
                throw failure
            }
            if (backup != null) deleteOwned(backup, old!!, target.root)
        } finally {
            if (Files.exists(stage, NOFOLLOW_LINKS)) {
                deleteStaging(stage, stageIdentity, source.files.keys + source.directories + MARKER)
            }
        }
    }

    /** CREATE_DIRECTORY is the exclusive claim; atomic rename may replace an existing target. */
    private fun publishClaimed(
        stage: Path,
        root: Path,
    ) {
        Files.createDirectory(root)
        val rootKey = Files.readAttributes(root, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
        check(rootKey != null) { "Provider cannot identify the claimed directory. Preserve it and choose another location." }
        val keys = linkedMapOf<Path, Any>(root to rootKey)
        val hashes = linkedMapOf<Path, String>()
        var complete = false
        try {
            val markerFile = root.resolve(MARKER)
            Files.copy(stage.resolve(MARKER), markerFile, COPY_ATTRIBUTES)
            keys[markerFile] = checkNotNull(Files.readAttributes(markerFile, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey())
            hashes[markerFile] = hash(stage.resolve(MARKER))
            val tree = snapshot(stage, true)
            for (directory in tree.directories.sortedBy { it.length }) {
                checkClaim(keys)
                val path = Files.createDirectory(root.resolve(directory))
                keys[path] = checkNotNull(Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey())
            }
            for ((name, entry) in tree.files) {
                checkClaim(keys)
                val path = root.resolve(name)
                copyFile(stage.resolve(name), path)
                keys[path] = checkNotNull(Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey())
                hashes[path] = entry.hash
            }
            checkClaim(keys)
            owned(root)
            check(hash(markerFile) == hashes[markerFile]) { "Claim marker changed before publication." }
            complete = true
        } finally {
            if (!complete) cleanupClaim(keys, hashes)
        }
    }

    private fun checkClaim(keys: Map<Path, Any>) {
        for ((path, key) in keys) {
            safePath(path)
            check(Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey() == key) {
                "Claimed installation entry was replaced. Preserve it before retrying."
            }
        }
    }

    private fun cleanupClaim(
        keys: Map<Path, Any>,
        hashes: Map<Path, String>,
    ) {
        val root = keys.keys.first()
        if (!Files.isDirectory(root, NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) return
        val entries = Files.walk(root).use { it.toList() }
        if (entries.toSet() != keys.keys || entries.any { Files.isSymbolicLink(it) }) return
        if (entries.any { Files.readAttributes(it, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey() != keys[it] }) return
        if (hashes.any { (path, expected) -> hash(path) != expected }) return
        for (path in entries.sortedByDescending { it.nameCount }) {
            checkClaim(keys.filterKeys { Files.exists(it, NOFOLLOW_LINKS) })
            Files.delete(path)
        }
    }

    fun validate(target: InstallLayout) {
        owned(target.root)
    }

    fun uninstall(target: InstallLayout) {
        validateWindowsRoot(target.platform, target.root)
        safePath(target.root)
        if (!Files.exists(target.root, NOFOLLOW_LINKS)) return
        val installation = owned(target.root)
        deleteOwned(target.root, installation, target.root)
    }

    private data class Entry(
        val hash: String,
        val key: Any?,
    )

    private data class Tree(
        val files: Map<String, Entry>,
        val directories: Set<String>,
    ) {
        fun hashes(): Map<String, String> = files.mapValues { it.value.hash }
    }

    private data class Owned(
        val bytes: ByteArray,
        val version: String,
        val tree: Tree,
    )

    private fun owned(
        root: Path,
        identityRoot: Path = root,
    ): Owned {
        safePath(root)
        val markerFile = root.resolve(MARKER)
        check(Files.isRegularFile(markerFile, NOFOLLOW_LINKS) && !Files.isSymbolicLink(markerFile)) {
            "Expected an owned installation marker at $markerFile. Preserve the directory or choose another install root."
        }
        val bytes = fileReader.read(markerFile)
        val lines = bytes.toString(Charsets.UTF_8).split('\n')
        check(lines.take(3) == listOf("formatVersion=1", "application=fruit-and-faults", "root=" + encode(identityRoot.toString()))) {
            "Invalid or incompatible installation marker. No files removed."
        }
        val version = lines.getOrNull(3)?.removePrefix("version=") ?: error("Missing marker version.")
        check(lines.getOrNull(4)?.startsWith("identity=") == true) { "Missing installation identity." }
        UUID.fromString(lines[4].removePrefix("identity="))
        val expected = linkedMapOf<String, String>()
        val expectedDirectories = linkedSetOf<String>()
        for (line in lines.drop(5).filter { it.isNotEmpty() }) {
            val parts = line.split(' ', limit = 3)
            check(parts.size in 2..3) { "Malformed installation inventory." }
            val name = decode(parts[1])
            check(
                name.isNotBlank() && !Path.of(name).isAbsolute &&
                    Path.of(name).none {
                        it.toString() == ".."
                    } && name != MARKER,
            ) { "Unsafe installation inventory." }
            when (parts[0]) {
                "dir" -> {
                    check(expectedDirectories.add(name)) { "Duplicate inventory directory." }
                }

                "file" -> {
                    check(
                        parts.size == 3 && parts[2].matches(Regex("[a-f0-9]{64}")) && expected.put(name, parts[2]) == null,
                    ) { "Invalid inventory file." }
                }

                else -> {
                    error("Unknown inventory entry.")
                }
            }
        }
        val actual = snapshot(root, true)
        check(actual.hashes() == expected && actual.directories == expectedDirectories) {
            "Installation has foreign, missing, or changed files. Preserve them before retrying; no files removed."
        }
        return Owned(bytes, version, actual)
    }

    private fun marker(
        root: Path,
        version: String,
        identity: String,
        tree: Tree,
    ): String =
        buildString {
            append("formatVersion=1\napplication=fruit-and-faults\nroot=${encode(root.toString())}\nversion=$version\nidentity=$identity\n")
            for (directory in tree.directories.sorted()) append("dir ${encode(directory)}\n")
            for ((name, entry) in tree.files.toSortedMap()) append("file ${encode(name)} ${entry.hash}\n")
        }

    private fun snapshot(
        root: Path,
        skipMarker: Boolean,
    ): Tree {
        safePath(root)
        check(Files.isDirectory(root, NOFOLLOW_LINKS)) { "Expected a regular distribution directory." }
        val files = linkedMapOf<String, Entry>()
        val directories = linkedSetOf<String>()
        Files.walk(root).use { paths ->
            paths.forEach { path ->
                check(!Files.isSymbolicLink(path)) { "Symbolic links are not permitted in distributions/installations." }
                if (path != root) {
                    val name = root.relativize(path).joinToString("/")
                    if (!(skipMarker && name == MARKER)) {
                        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                        if (attrs.isDirectory) {
                            directories.add(name)
                        } else {
                            check(
                                attrs.isRegularFile && files.size < 10_000 && attrs.size() <= 512L * 1024 * 1024,
                            ) { "Unsupported or oversized distribution entry." }
                            files[name] = Entry(hash(path), attrs.fileKey())
                        }
                    }
                }
            }
        }
        return Tree(files, directories)
    }

    private fun deleteOwned(
        root: Path,
        expected: Owned,
        identityRoot: Path,
    ) {
        val checked = owned(root, identityRoot)
        check(checked.bytes.contentEquals(expected.bytes) && checked.tree.hashes() == expected.tree.hashes()) {
            "Installation identity changed. No files removed."
        }
        for ((name, entry) in checked.tree.files) {
            val path = root.resolve(name)
            safePath(path)
            check(
                Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey() == entry.key &&
                    hash(path) == entry.hash,
            ) { "Installation file changed during removal." }
            Files.delete(path)
        }
        for (directory in checked.tree.directories.sortedByDescending { it.length }) {
            safePath(root.resolve(directory))
            Files.delete(root.resolve(directory))
        }
        check(fileReader.read(root.resolve(MARKER)).contentEquals(expected.bytes)) { "Marker identity changed during removal." }
        Files.delete(root.resolve(MARKER))
        Files.delete(root)
    }

    private fun deleteStaging(
        stage: Path,
        identity: Any?,
        allowedNames: Set<String>,
    ) {
        // Without stable provider identity, preserve the temporary directory rather than risk foreign cleanup.
        safePath(stage)
        if (identity == null || Files.readAttributes(stage, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey() != identity) return
        val entries = Files.walk(stage).use { it.toList() }
        if (entries.any { Files.isSymbolicLink(it) || (it != stage && stage.relativize(it).joinToString("/") !in allowedNames) }) return
        val keys = entries.associateWith { Files.readAttributes(it, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey() }
        if (keys.values.any { it == null }) return
        for (path in entries.sortedByDescending { it.nameCount }) {
            safePath(path)
            check(Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey() == keys[path]) {
                "Staged entry changed during cleanup; preserve it."
            }
            Files.delete(path)
        }
    }

    private fun move(
        from: Path,
        to: Path,
    ) {
        safePath(from)
        safePath(to)
        try {
            Files.move(from, to, ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(from, to)
        }
    }

    private fun hash(path: Path): String = fileReader.hash(path)

    private fun encode(text: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(Charsets.UTF_8))

    private fun decode(text: String): String = Base64.getUrlDecoder().decode(text).toString(Charsets.UTF_8)
}

@DisableCachingByDefault(because = "Installs outside the build directory with explicit ownership checks")
abstract class InstallCliTask : CliEnvironmentTask() {
    @get:InputDirectory abstract val distribution: DirectoryProperty

    @TaskAction fun installCli() {
        val target = installation()
        logger.lifecycle("Install CLI distribution: ${target.root}")
        CliInstaller().install(distribution.get().asFile.toPath(), target, cliVersion.get(), force.get())
    }
}
