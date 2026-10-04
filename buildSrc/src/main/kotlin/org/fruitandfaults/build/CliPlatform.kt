package org.fruitandfaults.build

import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Locale

/** Supported installer platforms. No platform detection mutates user state. */
enum class CliPlatform {
    WINDOWS,
    MACOS,
    LINUX,
    ;

    fun layout(
        home: Path,
        localAppData: Path?,
        xdgData: Path?,
        shell: String,
        rootOverride: Path? = null,
        profileOverride: Path? = null,
    ): InstallLayout {
        val userHome = absolute(home)
        val root =
            absolute(
                rootOverride ?: when (this) {
                    WINDOWS -> {
                        requireNotNull(
                            localAppData,
                        ) { "LOCALAPPDATA is missing. Set -PcliLocalAppData=<directory>." }.resolve("Programs/FruitAndFaults")
                    }

                    MACOS -> {
                        userHome.resolve("Library/Application Support/FruitAndFaults")
                    }

                    LINUX -> {
                        (xdgData ?: userHome.resolve(".local/share")).resolve("fruit-and-faults")
                    }
                },
            )
        require(root != userHome && root.parent != null && root.nameCount > 1 && !userHome.startsWith(root)) {
            "Install root must be a dedicated directory, never the home directory or its ancestor."
        }
        val profileName =
            when (Path.of(shell.ifBlank { "unknown" }).fileName.toString()) {
                "zsh" -> if (this == MACOS) ".zprofile" else ".zshrc"
                "bash" -> if (this == MACOS) ".bash_profile" else ".bashrc"
                else -> null
            }
        val profile = if (this == WINDOWS || profileName == null) null else absolute(profileOverride ?: userHome.resolve(profileName))
        return InstallLayout(this, userHome, root, userHome.resolve(".local/bin/fruit-and-faults"), profile)
    }

    companion object {
        fun detect(osName: String): CliPlatform =
            when {
                osName.lowercase(Locale.ROOT).startsWith("windows") -> WINDOWS
                osName.lowercase(Locale.ROOT).contains("mac") -> MACOS
                osName.lowercase(Locale.ROOT).contains("linux") -> LINUX
                else -> throw IllegalArgumentException("Unsupported OS: use Windows, macOS, or Linux.")
            }

        internal fun absolute(path: Path): Path {
            require(path.isAbsolute) { "Installer paths must be absolute." }
            require(path.none { it.toString() == ".." }) { "Installer paths must not contain '..'." }
            require(
                path.toString().none { it == '\u0000' || it == '\r' || it == '\n' },
            ) { "Installer paths must not contain control characters." }
            return path.normalize()
        }
    }
}

data class InstallLayout(
    val platform: CliPlatform,
    val home: Path,
    val root: Path,
    val command: Path,
    val profile: Path?,
)

/** Check every existing component without following symbolic links. */
internal fun safePath(path: Path) {
    val normalized = CliPlatform.absolute(path)
    var cursor = normalized.root
    for (part in normalized) {
        cursor = cursor.resolve(part)
        check(!Files.isSymbolicLink(cursor)) { "Unsafe symbolic link in installer path: $cursor. Choose a regular directory/file." }
        if (cursor != normalized && Files.exists(cursor, NOFOLLOW_LINKS)) {
            check(Files.isDirectory(cursor, NOFOLLOW_LINKS)) { "Expected directory: $cursor." }
        }
    }
}

/** Explicit inputs keep task execution independent of Gradle Project and mutable process globals. */
abstract class CliEnvironmentTask : DefaultTask() {
    @get:Input abstract val osName: Property<String>

    @get:Input abstract val userHome: Property<String>

    @get:Input abstract val localAppData: Property<String>

    @get:Input abstract val xdgDataHome: Property<String>

    @get:Input abstract val shell: Property<String>

    @get:Input abstract val installRoot: Property<String>

    @get:Input abstract val profileFile: Property<String>

    @get:Input abstract val currentPath: Property<String>

    @get:Input abstract val userPathFile: Property<String>

    @get:Input abstract val javaHome: Property<String>

    @get:Input abstract val cliVersion: Property<String>

    @get:Input abstract val force: Property<Boolean>

    fun installation(): InstallLayout =
        CliPlatform.detect(osName.get()).layout(
            Path.of(userHome.get()),
            optionalPath(localAppData.get()),
            optionalPath(xdgDataHome.get()),
            shell.get(),
            optionalPath(installRoot.get()),
            optionalPath(profileFile.get()),
        )

    fun pathStore(): UserPathStore =
        if (userPathFile.get().isNotBlank()) FileUserPathStore(Path.of(userPathFile.get())) else WindowsUserPathStore()

    private fun optionalPath(text: String): Path? = text.takeIf { it.isNotBlank() }?.let(Path::of)
}
