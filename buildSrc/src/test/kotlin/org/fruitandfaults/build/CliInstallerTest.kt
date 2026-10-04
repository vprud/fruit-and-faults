package org.fruitandfaults.build

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CliInstallerTest {
    @TempDir lateinit var temp: Path

    @BeforeEach fun canonicalTemporaryRoot() {
        temp = temp.toRealPath()
    }

    private fun layout(
        platform: CliPlatform = CliPlatform.MACOS,
        shell: String = "/bin/zsh",
    ): InstallLayout {
        val home = temp.resolve("Дом пользователя")
        Files.createDirectories(home)
        return platform.layout(home, temp.resolve("Local App Data"), null, shell)
    }

    private fun distribution(version: String = "one"): Path {
        val dist = temp.resolve("distribution-$version")
        Files.createDirectories(dist.resolve("bin"))
        Files.createDirectories(dist.resolve("lib"))
        Files.writeString(dist.resolve("bin/fruit-and-faults"), "launcher-$version")
        Files.writeString(dist.resolve("bin/fruit-and-faults.bat"), "windows-$version")
        Files.writeString(dist.resolve("lib/app.jar"), "jar-$version")
        return dist
    }

    @Test fun `platform roots respect Windows app data and Unix conventions`() {
        assertEquals(temp.resolve("Local App Data/Programs/FruitAndFaults"), layout(CliPlatform.WINDOWS).root)
        assertEquals(temp.resolve("Дом пользователя/Library/Application Support/FruitAndFaults"), layout().root)
        assertEquals(temp.resolve("Дом пользователя/.local/share/fruit-and-faults"), layout(CliPlatform.LINUX).root)
        assertEquals(
            temp.resolve("XDG/fruit-and-faults"),
            CliPlatform.LINUX.layout(temp.resolve("home"), null, temp.resolve("XDG"), "/bin/bash").root,
        )
        assertEquals(CliPlatform.WINDOWS, CliPlatform.detect("Windows 11"))
        assertEquals(CliPlatform.MACOS, CliPlatform.detect("Mac OS X"))
        assertThrows(IllegalArgumentException::class.java) { CliPlatform.detect("Plan 9") }
    }

    @Test fun `relative XDG data home uses the default Linux root`() {
        val home = temp.resolve("linux-home")
        assertEquals(
            home.resolve(".local/share/fruit-and-faults"),
            CliPlatform.LINUX.layout(home, null, Path.of("relative-data"), "/bin/bash").root,
        )
        assertEquals(
            home.resolve(".local/share/fruit-and-faults"),
            CliPlatform.LINUX.layout(home, null, Path.of(""), "/bin/bash").root,
        )
    }

    @Test fun `Windows delimiter roots are rejected before installation or PATH mutation`() {
        for (unsafe in listOf("install;foreign")) {
            val home = temp.resolve("windows-home")
            val root = temp.resolve(unsafe)
            assertThrows(IllegalArgumentException::class.java) {
                CliPlatform.WINDOWS.layout(home, temp.resolve("App Data"), null, "", root)
            }
            val bypass = InstallLayout(CliPlatform.WINDOWS, home, root, home.resolve(".local/bin/fruit-and-faults"), null)
            val store = MemoryPath("C:\\Unrelated")
            assertThrows(
                IllegalArgumentException::class.java,
            ) { CliInstaller().install(distribution("valid-windows-fixture"), bypass, "0.1.0") }
            assertThrows(IllegalArgumentException::class.java) { CliPathExposure(store).add(bypass, "") }
            assertThrows(IllegalArgumentException::class.java) { CliPathExposure(store).remove(bypass) }
            assertThrows(IllegalArgumentException::class.java) { CliInstaller().uninstall(bypass) }
            assertEquals("C:\\Unrelated", store.value)
            assertFalse(Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        }
    }

    @Test fun `raw Windows quote and control rejection does not depend on native path parsing`() {
        for (raw in listOf("install;foreign", "install\"quoted", "install\tcontrol", "install\u0000nul")) {
            assertThrows(IllegalArgumentException::class.java) { validateWindowsRootText(raw) }
            assertThrows(IllegalArgumentException::class.java) {
                val root = temp.resolve(raw)
                CliPlatform.WINDOWS.layout(temp.resolve("valid-home"), temp.resolve("valid-app-data"), null, "", root)
            }
        }
    }

    @Test fun `install update repeat and uninstall own only the marked distribution`() {
        val target = layout()
        val installer = CliInstaller()
        installer.install(distribution(), target, "0.1.0")
        val marker = Files.readAllBytes(target.root.resolve(CliInstaller.MARKER))
        installer.install(distribution("two"), target, "0.2.0")
        assertEquals("jar-two", Files.readString(target.root.resolve("lib/app.jar")))
        assertFalse(marker.contentEquals(Files.readAllBytes(target.root.resolve(CliInstaller.MARKER))))
        installer.install(temp.resolve("distribution-two"), target, "0.2.0")
        installer.uninstall(target)
        installer.uninstall(target)
        assertFalse(Files.exists(target.root))
        assertTrue(Files.exists(target.home))
    }

    @Test fun `unmarked foreign and modified installations are preserved`() {
        val target = layout()
        Files.createDirectories(target.root)
        Files.writeString(target.root.resolve("foreign.txt"), "keep")
        assertThrows(IllegalStateException::class.java) { CliInstaller().install(distribution(), target, "0.1.0") }
        assertThrows(IllegalStateException::class.java) { CliInstaller().uninstall(target) }
        assertEquals("keep", Files.readString(target.root.resolve("foreign.txt")))
    }

    @Test fun `unknown marker version and added files prevent uninstall`() {
        val target = layout()
        val installer = CliInstaller()
        installer.install(distribution(), target, "0.1.0")
        Files.writeString(target.root.resolve("learner.txt"), "keep")
        assertThrows(IllegalStateException::class.java) { installer.uninstall(target) }
        assertEquals("keep", Files.readString(target.root.resolve("learner.txt")))
        Files.delete(target.root.resolve("learner.txt"))
        val marker = target.root.resolve(CliInstaller.MARKER)
        Files.writeString(marker, Files.readString(marker).replace("formatVersion=1", "formatVersion=99"))
        assertThrows(IllegalStateException::class.java) { installer.uninstall(target) }
        assertTrue(Files.exists(target.root.resolve("lib/app.jar")))
    }

    @Test fun `symlink ancestors distribution entries and broad roots are rejected before mutation`() {
        val target = layout()
        val outside = Files.createDirectory(temp.resolve("outside"))
        val link = temp.resolve("linked")
        Files.createSymbolicLink(link, outside)
        assertThrows(IllegalStateException::class.java) {
            CliInstaller().install(distribution(), target.copy(root = link.resolve("install")), "0.1.0")
        }
        assertFalse(Files.exists(outside.resolve("install")))
        val dist = distribution("symlink")
        Files.createSymbolicLink(dist.resolve("lib/link.jar"), temp.resolve("secret"))
        assertThrows(IllegalStateException::class.java) { CliInstaller().install(dist, target, "0.1.0") }
        assertFalse(Files.exists(target.root))
        assertThrows(IllegalArgumentException::class.java) {
            CliPlatform.LINUX.layout(target.home, null, null, "/bin/bash", target.home)
        }
    }

    @Test fun `failed staged copy keeps the previous valid installation`() {
        val target = layout()
        CliInstaller().install(distribution(), target, "0.1.0")
        val original = Files.readAllBytes(target.root.resolve(CliInstaller.MARKER))
        val failing = CliInstaller { _, _ -> throw java.io.IOException("injected copy failure") }
        assertThrows(java.io.IOException::class.java) { failing.install(distribution("two"), target, "0.2.0") }
        assertArrayEquals(original, Files.readAllBytes(target.root.resolve(CliInstaller.MARKER)))
        assertEquals("jar-one", Files.readString(target.root.resolve("lib/app.jar")))
    }

    @Test fun `Windows user PATH deduplicates case insensitively and preserves unrelated entries`() {
        val target = layout(CliPlatform.WINDOWS)
        val bin = target.root.resolve("bin").toString()
        val store = MemoryPath("C:\\Windows;${bin.uppercase()};C:\\Other;$bin")
        val exposure = CliPathExposure(store)
        exposure.add(target, "")
        assertEquals("C:\\Windows;$bin;C:\\Other", store.value)
        exposure.add(target, "")
        exposure.remove(target)
        exposure.remove(target)
        assertEquals("C:\\Windows;C:\\Other", store.value)
    }

    @Test fun `Unix setup is repeatable and preserves profile bytes and line endings`() {
        for ((platform, shell, profileName) in listOf(
            Triple(CliPlatform.MACOS, "/bin/zsh", ".zprofile"),
            Triple(CliPlatform.LINUX, "/bin/bash", ".bashrc"),
            Triple(CliPlatform.LINUX, "/bin/zsh", ".zshrc"),
            Triple(CliPlatform.MACOS, "/bin/bash", ".bash_profile"),
        )) {
            val target = layout(platform, shell)
            val profile = target.home.resolve(profileName)
            val before = "# настройки\r\nexport CUSTOM='keep'".toByteArray(Charsets.UTF_8)
            Files.write(profile, before)
            CliInstaller().install(distribution(profileName), target, "0.1.0")
            val exposure = CliPathExposure(MemoryPath(""))
            exposure.add(target, "/usr/bin:/bin")
            val first = Files.readAllBytes(profile)
            assertTrue(first.toString(Charsets.UTF_8).contains("# >>> fruit-and-faults >>>"))
            assertTrue(Files.isSymbolicLink(target.command))
            exposure.add(target, "/usr/bin:/bin")
            assertArrayEquals(first, Files.readAllBytes(profile))
            exposure.remove(target)
            assertArrayEquals(before, Files.readAllBytes(profile))
            assertFalse(Files.exists(target.command, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            CliInstaller().uninstall(target)
        }
    }

    @Test fun `existing PATH and unknown shell avoid unnecessary profile writes`() {
        val target = layout(CliPlatform.LINUX, "/bin/fish")
        CliInstaller().install(distribution(), target, "0.1.0")
        val exposure = CliPathExposure(MemoryPath(""))
        val message = exposure.add(target, "/usr/bin")
        assertTrue(message.contains("manual", ignoreCase = true))
        assertFalse(Files.exists(target.home.resolve(".profile")))
        exposure.remove(target)
        CliInstaller().uninstall(target)
        val known = layout(CliPlatform.LINUX, "/bin/bash")
        CliInstaller().install(temp.resolve("distribution-one"), known, "0.1.0")
        exposure.add(known, known.command.parent.toString() + ":/usr/bin")
        assertFalse(Files.exists(known.profile!!))
    }

    @Test fun `foreign command and symlink profile are never overwritten`() {
        val target = layout()
        CliInstaller().install(distribution(), target, "0.1.0")
        Files.createDirectories(target.command.parent)
        Files.writeString(target.command, "foreign")
        val exposure = CliPathExposure(MemoryPath(""))
        assertThrows(IllegalStateException::class.java) { exposure.add(target, "") }
        assertEquals("foreign", Files.readString(target.command))
        Files.delete(target.command)
        val outside = temp.resolve("outside-profile")
        Files.writeString(outside, "keep")
        Files.createSymbolicLink(target.profile!!, outside)
        assertThrows(IllegalStateException::class.java) { exposure.add(target, "") }
        assertEquals("keep", Files.readString(outside))
        assertFalse(Files.exists(target.command, java.nio.file.LinkOption.NOFOLLOW_LINKS))
    }

    @Test fun `JDK verification requires both java and javac 26 without downloading`() {
        val ok =
            CommandRunner {
                args,
                _,
                ->
                ProbeResult(0, if (args[0].contains("javac")) "javac 26.0.1" else "openjdk version \"26.0.1\"")
            }
        assertTrue(JdkVerifier(ok).verify(temp.resolve("JDK 26"), CliPlatform.MACOS).contains("26.0.1"))
        for (result in listOf(
            ProbeResult(0, "javac 25"),
            ProbeResult(1, "missing"),
            ProbeResult(0, "garbage"),
            ProbeResult(0, "", timedOut = true),
        )) {
            val bad = CommandRunner { args, _ -> if (args[0].contains("javac")) result else ProbeResult(0, "openjdk version \"26\"") }
            assertThrows(IllegalStateException::class.java) { JdkVerifier(bad).verify(temp, CliPlatform.WINDOWS) }
        }
    }

    @Test fun `installed launcher verification checks exact version and failures`() {
        val target = layout()
        CliInstaller().install(distribution(), target, "0.1.0")
        val runner =
            CommandRunner { args, env ->
                assertEquals(listOf(target.root.resolve("bin/fruit-and-faults").toString(), "--version"), args)
                assertEquals(temp.resolve("JDK 26").toString(), env["JAVA_HOME"])
                ProbeResult(0, "fruit-and-faults 0.1.0\n")
            }
        CliVerifier(runner).verify(target, "0.1.0", temp.resolve("JDK 26"))
        assertThrows(IllegalStateException::class.java) {
            CliVerifier(CommandRunner { _, _ -> ProbeResult(0, "fruit-and-faults 0.2.0") }).verify(target, "0.1.0", temp)
        }
        assertThrows(IllegalStateException::class.java) {
            CliVerifier(CommandRunner { _, _ -> ProbeResult(0, "", timedOut = true) }).verify(target, "0.1.0", temp)
        }
    }

    @Test fun `modified owned files and marker root cannot authorize deletion`() {
        val target = layout()
        val installer = CliInstaller()
        installer.install(distribution(), target, "0.1.0")
        Files.writeString(target.root.resolve("lib/app.jar"), "user modification")
        assertThrows(IllegalStateException::class.java) { installer.uninstall(target) }
        assertEquals("user modification", Files.readString(target.root.resolve("lib/app.jar")))
        Files.writeString(target.root.resolve("lib/app.jar"), "jar-one")
        val other = target.copy(root = temp.resolve("other"))
        Files.move(target.root, other.root)
        assertThrows(IllegalStateException::class.java) { installer.uninstall(other) }
        assertTrue(Files.exists(other.root.resolve("lib/app.jar")))
    }

    @Test fun `replacement requires explicit force while unchanged setup needs none`() {
        val target = layout()
        val installer = CliInstaller()
        installer.install(distribution(), target, "0.1.0", false)
        val marker = Files.readAllBytes(target.root.resolve(CliInstaller.MARKER))
        installer.install(temp.resolve("distribution-one"), target, "0.1.0", false)
        assertArrayEquals(marker, Files.readAllBytes(target.root.resolve(CliInstaller.MARKER)))
        assertThrows(IllegalStateException::class.java) { installer.install(distribution("two"), target, "0.2.0", false) }
        assertEquals("jar-one", Files.readString(target.root.resolve("lib/app.jar")))
    }

    @Test fun `modified marked profile content is preserved on uninstall`() {
        val target = layout()
        CliInstaller().install(distribution(), target, "0.1.0")
        val exposure = CliPathExposure(MemoryPath(""))
        exposure.add(target, "")
        val profile = target.profile!!
        Files.writeString(profile, Files.readString(profile).replace("export PATH=", "export CUSTOM="))
        val before = Files.readAllBytes(profile)
        assertThrows(IllegalStateException::class.java) { exposure.remove(target) }
        assertArrayEquals(before, Files.readAllBytes(profile))
        assertTrue(Files.isSymbolicLink(target.command))
    }

    @Test fun `Windows PATH boundary changes are preserved instead of overwritten`() {
        val target = layout(CliPlatform.WINDOWS)
        val store =
            object : UserPathStore {
                var reads = 0
                var value = "C:\\Old"

                override fun read(): String {
                    if (++reads == 2) value = "C:\\New user entry"
                    return value
                }

                override fun write(value: String) {
                    this.value = value
                }
            }
        assertThrows(IllegalStateException::class.java) { CliPathExposure(store).add(target, "") }
        assertEquals("C:\\New user entry", store.value)
    }

    @Test fun `profile edits preserve opaque bytes and existing file permissions`() {
        val target = layout()
        CliInstaller().install(distribution(), target, "0.1.0")
        val profile = target.profile!!
        Files.createDirectories(profile.parent)
        val bytes = byteArrayOf(0xff.toByte(), 0x80.toByte(), '\n'.code.toByte())
        Files.write(profile, bytes)
        val supportsPosix = Files.getFileAttributeView(profile, java.nio.file.attribute.PosixFileAttributeView::class.java) != null
        val permissions =
            if (supportsPosix) {
                java.nio.file.attribute.PosixFilePermissions
                    .fromString("rw-r--r--")
            } else {
                null
            }
        if (permissions != null) Files.setPosixFilePermissions(profile, permissions)
        val exposure = CliPathExposure(MemoryPath(""))
        exposure.add(target, "")
        assertTrue(Files.isSymbolicLink(target.command))
        if (permissions != null) assertEquals(permissions, Files.getPosixFilePermissions(profile))
        exposure.remove(target)
        assertArrayEquals(bytes, Files.readAllBytes(profile))
        assertFalse(Files.exists(target.command, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        if (permissions != null) assertEquals(permissions, Files.getPosixFilePermissions(profile))
    }

    @Test fun `truncated process output cannot become authoritative PATH or version`() {
        val runner = CommandRunner { _, _ -> ProbeResult(0, "C:\\Windows", truncated = true) }
        assertThrows(IllegalStateException::class.java) { WindowsUserPathStore(runner, CliPlatform.WINDOWS).read() }
        val target = layout()
        CliInstaller().install(distribution(), target, "0.1.0")
        assertThrows(IllegalStateException::class.java) {
            CliVerifier(CommandRunner { _, _ -> ProbeResult(0, "fruit-and-faults 0.1.0", truncated = true) }).verify(target, "0.1.0", temp)
        }
    }

    @Test fun `Windows registry adapter uses only user environment and safe separate values`() {
        val commands = mutableListOf<Pair<List<String>, Map<String, String>>>()
        val store =
            WindowsUserPathStore(
                CommandRunner {
                    args,
                    environment,
                    ->
                    commands.add(args to environment)
                    ProbeResult(0, "C:\\Other")
                },
                CliPlatform.WINDOWS,
            )
        assertEquals("C:\\Other", store.read())
        val path = "C:\\Дом пользователя;C:\\quoted ' & entry"
        store.write(path)
        assertEquals(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command"), commands[0].first.take(4))
        assertTrue(commands[0].first.last().contains("GetEnvironmentVariable('Path','User')"))
        assertTrue(commands[1].first.last().contains("SetEnvironmentVariable('Path',\$env:FRUIT_AND_FAULTS_USER_PATH,'User')"))
        assertFalse(commands[1].first.last().contains(path))
        assertEquals(path, commands[1].second["FRUIT_AND_FAULTS_USER_PATH"])
    }

    @Test fun `Windows launcher uses a fixed command name inside the explicit install bin`() {
        val target = layout(CliPlatform.WINDOWS)
        CliInstaller().install(distribution(), target, "0.1.0")
        val runner =
            object : CommandRunner {
                override fun run(
                    arguments: List<String>,
                    environment: Map<String, String>,
                ): ProbeResult = error("Working directory is required")

                override fun runInDirectory(
                    arguments: List<String>,
                    environment: Map<String, String>,
                    directory: Path,
                ): ProbeResult {
                    assertEquals(listOf("cmd.exe", "/d", "/c", "fruit-and-faults.bat", "--version"), arguments)
                    assertEquals(target.root.resolve("bin"), directory)
                    assertEquals(temp.resolve("JDK 26").toString(), environment["JAVA_HOME"])
                    return ProbeResult(0, "fruit-and-faults 0.1.0")
                }
            }
        assertEquals("fruit-and-faults 0.1.0", CliVerifier(runner).verify(target, "0.1.0", temp.resolve("JDK 26")))
    }

    @Test fun `distribution cannot supply the reserved ownership marker`() {
        val target = layout()
        val dist = distribution()
        Files.writeString(dist.resolve(CliInstaller.MARKER), "foreign marker")
        assertThrows(IllegalStateException::class.java) { CliInstaller().install(dist, target, "0.1.0") }
        assertFalse(Files.exists(target.root))
    }

    @Test fun `failed staging never deletes a foreign replacement directory`() {
        val target = layout()
        var replacement: Path? = null
        val installer =
            CliInstaller { _, destination ->
                val stage = destination.parent.parent
                Files.move(stage, temp.resolve("retained-original-stage"))
                Files.createDirectory(stage)
                Files.writeString(stage.resolve("foreign.txt"), "preserve")
                replacement = stage
                throw java.io.IOException("injected stage replacement")
            }
        assertThrows(java.io.IOException::class.java) { installer.install(distribution(), target, "0.1.0") }
        assertEquals("preserve", Files.readString(replacement!!.resolve("foreign.txt")))
        assertFalse(Files.exists(target.root))
    }

    @Test fun `same byte foreign replacement after completed copy is never adopted for cleanup`() {
        for (publishing in listOf(false, true)) {
            val target = layout().copy(root = temp.resolve(if (publishing) "published-replacement" else "staged-replacement"))
            val source = distribution(if (publishing) "publish-replacement" else "stage-replacement")
            val original = Files.readAllBytes(source.resolve("bin/fruit-and-faults"))
            var replacement: Path? = null
            var key: Any? = null
            val installer =
                CliInstaller { from, to ->
                    val publication =
                        from.parent.parent.fileName
                            .toString()
                            .startsWith(".fruit-and-faults-stage-")
                    if (replacement != null) throw java.io.IOException("failure after completed copy")
                    val proof = InstallerFileReader().copy(from, to)
                    if (publication == publishing) {
                        val bytes = Files.readAllBytes(to)
                        Files.move(to, temp.resolve(if (publishing) "retained-published-copy" else "retained-staged-copy"))
                        Files.write(to, bytes, java.nio.file.StandardOpenOption.CREATE_NEW)
                        replacement = to
                        key =
                            Files
                                .readAttributes(
                                    to,
                                    java.nio.file.attribute.BasicFileAttributes::class.java,
                                    java.nio.file.LinkOption.NOFOLLOW_LINKS,
                                ).fileKey()
                    }
                    proof
                }
            assertThrows(Exception::class.java) { installer.install(source, target, "0.1.0") }
            val foreign = checkNotNull(replacement)
            assertTrue(Files.exists(foreign), "A same-byte foreign inode must not become cleanup-owned")
            assertEquals(
                key,
                Files
                    .readAttributes(
                        foreign,
                        java.nio.file.attribute.BasicFileAttributes::class.java,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS,
                    ).fileKey(),
            )
            assertArrayEquals(original, Files.readAllBytes(foreign))
            assertArrayEquals(original, Files.readAllBytes(source.resolve("bin/fruit-and-faults")))
            if (!publishing) {
                assertFalse(Files.exists(target.root))
            } else {
                assertThrows(IllegalStateException::class.java) { CliInstaller().validate(target) }
            }
        }
    }

    @Test fun `initial publication preserves foreign targets created while staging`() {
        for (kind in listOf("empty", "file", "symlink")) {
            val target = layout().copy(root = temp.resolve("install-$kind"))
            var identity: Any? = null
            var appeared = false
            val installer =
                CliInstaller { source, destination ->
                    if (!appeared) {
                        when (kind) {
                            "empty" -> Files.createDirectory(target.root)
                            "file" -> Files.writeString(target.root, "foreign content")
                            else -> Files.createSymbolicLink(target.root, temp.resolve("outside-$kind"))
                        }
                        identity =
                            Files
                                .readAttributes(
                                    target.root,
                                    java.nio.file.attribute.BasicFileAttributes::class.java,
                                    java.nio.file.LinkOption.NOFOLLOW_LINKS,
                                ).fileKey()
                        appeared = true
                    }
                    InstallerFileReader().copy(source, destination)
                }
            assertThrows(java.nio.file.FileAlreadyExistsException::class.java) {
                installer.install(distribution(kind), target, "0.1.0", false)
            }
            assertEquals(
                identity,
                Files
                    .readAttributes(
                        target.root,
                        java.nio.file.attribute.BasicFileAttributes::class.java,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS,
                    ).fileKey(),
            )
            when (kind) {
                "empty" -> Files.list(target.root).use { assertEquals(0L, it.count()) }
                "file" -> assertEquals("foreign content", Files.readString(target.root))
                else -> assertEquals(temp.resolve("outside-$kind"), Files.readSymbolicLink(target.root))
            }
        }
    }

    @Test fun `failed claimed publication restores the previous owned installation`() {
        val target = layout()
        CliInstaller().install(distribution(), target, "0.1.0")
        val marker = Files.readAllBytes(target.root.resolve(CliInstaller.MARKER))
        val failing =
            CliInstaller { source, destination ->
                if (source.parent.parent.fileName
                        .toString()
                        .startsWith(".fruit-and-faults-stage-")
                ) {
                    throw java.io.IOException("injected publication copy failure")
                }
                InstallerFileReader().copy(source, destination)
            }
        assertThrows(java.io.IOException::class.java) { failing.install(distribution("update"), target, "0.2.0") }
        assertArrayEquals(marker, Files.readAllBytes(target.root.resolve(CliInstaller.MARKER)))
        assertEquals("jar-one", Files.readString(target.root.resolve("lib/app.jar")))
        CliInstaller().validate(target)
    }

    @Test fun `failed publication retains a claim containing unknown foreign state`() {
        val target = layout()
        val failing =
            CliInstaller { source, destination ->
                if (source.parent.parent.fileName
                        .toString()
                        .startsWith(".fruit-and-faults-stage-")
                ) {
                    Files.writeString(target.root.resolve("foreign.txt"), "preserve")
                    throw java.io.IOException("injected foreign publication state")
                }
                InstallerFileReader().copy(source, destination)
            }
        assertThrows(java.io.IOException::class.java) { failing.install(distribution(), target, "0.1.0") }
        assertEquals("preserve", Files.readString(target.root.resolve("foreign.txt")))
        assertTrue(Files.exists(target.root.resolve(CliInstaller.MARKER)))
    }

    @Test fun `interrupted partial update restores exact old installation and terminates its worker`() {
        val target = layout()
        CliInstaller().install(distribution("before-interrupt"), target, "0.1.0")
        val marker = Files.readAllBytes(target.root.resolve(CliInstaller.MARKER))
        val source = distribution("interrupted-update")
        val pidFile = temp.resolve("update-interrupt.pid")
        val drains =
            Thread
                .getAllStackTraces()
                .keys
                .filter { it.name == "cli-install-probe" }
                .map { it.threadId() }
                .toSet()
        val failure =
            java.util.concurrent.atomic
                .AtomicReference<Throwable>()
        val interrupted =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        var published = 0
        val runner = BoundedCommandRunner(5, 2_097_152)
        val reader =
            InstallerFileReader(
                CommandRunner { arguments, environment ->
                    val from = Path.of(arguments[6])
                    val publishing =
                        from.parent.parent.fileName
                            .toString()
                            .startsWith(".fruit-and-faults-stage-")
                    if (arguments[11] == "copy" && publishing && from.fileName.toString() != CliInstaller.MARKER && ++published == 2) {
                        BoundedCommandRunner(5).run(probeArguments("busy", pidFile.toString()), emptyMap())
                    } else {
                        runner.run(arguments, environment)
                    }
                },
            )
        val worker =
            Thread {
                try {
                    CliInstaller(reader).install(source, target, "0.2.0", true)
                } catch (problem: Throwable) {
                    failure.set(problem)
                } finally {
                    interrupted.set(Thread.currentThread().isInterrupted)
                }
            }
        worker.start()
        try {
            val deadline =
                System.nanoTime() +
                    java.util.concurrent.TimeUnit.SECONDS
                        .toNanos(15)
            while (!Files.exists(pidFile) && worker.isAlive && System.nanoTime() < deadline) Thread.yield()
            assertTrue(Files.exists(pidFile), "Publication must copy a file before interruption")
            worker.interrupt()
            worker.join(15_000)
            assertFalse(worker.isAlive)
            assertTrue(failure.get() is IllegalStateException)
            assertTrue(interrupted.get())
            val pid = Files.readString(pidFile).toLong()
            ProcessHandle.of(pid).ifPresent { it.onExit().get(3, java.util.concurrent.TimeUnit.SECONDS) }
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
            assertArrayEquals(marker, Files.readAllBytes(target.root.resolve(CliInstaller.MARKER)))
            assertEquals("jar-before-interrupt", Files.readString(target.root.resolve("lib/app.jar")))
            assertEquals("launcher-interrupted-update", Files.readString(source.resolve("bin/fruit-and-faults")))
            CliInstaller().validate(target)
            Files.list(target.root.parent).use { entries ->
                assertTrue(entries.noneMatch { it.fileName.toString().startsWith(".fruit-and-faults-") })
            }
            val drained =
                System.nanoTime() +
                    java.util.concurrent.TimeUnit.SECONDS
                        .toNanos(3)
            while (Thread.getAllStackTraces().keys.any { it.isAlive && it.name == "cli-install-probe" && it.threadId() !in drains } &&
                System.nanoTime() < drained
            ) {
                Thread.yield()
            }
            assertFalse(Thread.getAllStackTraces().keys.any { it.isAlive && it.name == "cli-install-probe" && it.threadId() !in drains })
        } finally {
            worker.interrupt()
            worker.join(15_000)
        }
    }

    @Test fun `staging cleanup failure still restores backup and preserves a new interruption`() {
        val target = layout()
        CliInstaller().install(distribution("cleanup-old"), target, "0.1.0")
        val marker = Files.readAllBytes(target.root.resolve(CliInstaller.MARKER))
        val source = distribution("cleanup-update")
        val failure =
            java.util.concurrent.atomic
                .AtomicReference<Throwable>()
        val interrupted =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        var published = 0
        var publicationFailed = false
        var cleanupFailed = false
        val runner = BoundedCommandRunner(5, 2_097_152)
        val reader =
            InstallerFileReader(
                CommandRunner { arguments, environment ->
                    val path = Path.of(arguments[6])
                    val inStage =
                        path.parent.parent.fileName
                            .toString()
                            .startsWith(".fruit-and-faults-stage-")
                    if (arguments[11] == "copy" && inStage && path.fileName.toString() != CliInstaller.MARKER && ++published == 2) {
                        publicationFailed = true
                        ProbeResult(7, "injected publication failure")
                    } else if (publicationFailed && !cleanupFailed && arguments[11] == "hash" && inStage) {
                        cleanupFailed = true
                        Thread.currentThread().interrupt()
                        throw IllegalStateException("injected staging cleanup interruption")
                    } else {
                        runner.run(arguments, environment)
                    }
                },
            )
        val worker =
            Thread {
                try {
                    CliInstaller(reader).install(source, target, "0.2.0", true)
                } catch (problem: Throwable) {
                    failure.set(problem)
                } finally {
                    interrupted.set(Thread.currentThread().isInterrupted)
                }
            }
        worker.start()
        try {
            worker.join(15_000)
            assertFalse(worker.isAlive)
            assertTrue(cleanupFailed)
            assertTrue(failure.get() is IllegalStateException)
            assertTrue(failure.get().suppressed.any { it.message == "injected staging cleanup interruption" })
            assertTrue(interrupted.get())
            assertArrayEquals(marker, Files.readAllBytes(target.root.resolve(CliInstaller.MARKER)))
            assertEquals("jar-cleanup-old", Files.readString(target.root.resolve("lib/app.jar")))
            CliInstaller().validate(target)
            Files.list(target.root.parent).use { entries ->
                assertFalse(entries.anyMatch { it.fileName.toString().startsWith(".fruit-and-faults-backup-") })
            }
        } finally {
            worker.interrupt()
            worker.join(15_000)
        }
    }

    @Test fun `real owned process bounds output and reports nonzero exit`() {
        val runner = BoundedCommandRunner(5)
        val output = runner.run(probeArguments("output"), emptyMap())
        assertEquals(0, output.exitCode)
        assertTrue(output.truncated)
        assertTrue(output.output.toByteArray(Charsets.UTF_8).size <= 16_384)
        val failed = runner.run(probeArguments("failure"), emptyMap())
        assertEquals(7, failed.exitCode)
        assertTrue(failed.output.contains("fixture failure"))
    }

    @Test fun `special ownership files are rejected without blocking the task process`() {
        org.junit.jupiter.api.Assumptions
            .assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        for (kind in listOf("command-marker", "windows-marker", "windows-store", "windows-store-write", "install-marker", "profile")) {
            val platform = if (kind.startsWith("windows")) CliPlatform.WINDOWS else CliPlatform.MACOS
            val target = layout(platform).copy(root = temp.resolve("fifo-install-$kind"))
            val path =
                when (kind) {
                    "command-marker" -> target.command.resolveSibling(".fruit-and-faults-command.properties")
                    "windows-marker" -> target.root.resolveSibling(".${target.root.fileName}-path.properties")
                    "windows-store", "windows-store-write" -> temp.resolve("fifo-user-path")
                    "install-marker" -> target.root.resolve(CliInstaller.MARKER)
                    else -> target.profile!!
                }
            Files.createDirectories(path.parent)
            val makeFifo = ProcessBuilder(listOf("mkfifo", path.toString())).start()
            try {
                assertTrue(makeFifo.waitFor(3, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(0, makeFifo.exitValue())
            } finally {
                if (makeFifo.isAlive) makeFifo.destroyForcibly()
            }
            val arguments = probeArguments("ownership-read", path.toString()) + listOf(kind, target.home.toString(), target.root.toString())
            val result = BoundedCommandRunner(2).run(arguments, emptyMap())
            assertFalse(result.timedOut, "$kind must reject before blocking")
            assertEquals(0, result.exitCode, result.output)
            assertEquals("rejected", result.output)
            assertFalse(Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            Files.delete(path)
        }
    }

    @Test fun `bounded ownership reads preserve bytes and reject replacement identity`() {
        val path = temp.resolve("opaque-state")
        val bytes = byteArrayOf(0xff.toByte(), 0, '\r'.code.toByte(), '\n'.code.toByte())
        Files.write(path, bytes)
        assertArrayEquals(bytes, InstallerFileReader().read(path, 4))
        assertThrows(IllegalStateException::class.java) { InstallerFileReader().read(path, 3) }
        val realRunner = BoundedCommandRunner(5, 2_097_152)
        val substituted =
            InstallerFileReader(
                CommandRunner { arguments, environment ->
                    val result = realRunner.run(arguments, environment)
                    Files.move(path, temp.resolve("original-state"))
                    Files.write(path, bytes)
                    result
                },
            )
        assertThrows(IllegalStateException::class.java) { substituted.read(path) }
        assertArrayEquals(bytes, Files.readAllBytes(path))
        assertArrayEquals(bytes, Files.readAllBytes(temp.resolve("original-state")))
    }

    @Test fun `inventory hashes use bounded reads without exposing large file content`() {
        val path = temp.resolve("inventory.jar")
        val bytes = ByteArray(2 * 1_048_576) { 0x61 }
        Files.write(path, bytes)
        val expected =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
        assertEquals(expected, InstallerFileReader().hash(path))
        val realRunner = BoundedCommandRunner(5, 2_097_152)
        val replaced =
            InstallerFileReader(
                CommandRunner { arguments, environment ->
                    val result = realRunner.run(arguments, environment)
                    assertEquals(64, result.output.length)
                    Files.move(path, temp.resolve("retained-inventory.jar"))
                    Files.write(path, bytes)
                    result
                },
            )
        assertThrows(IllegalStateException::class.java) { replaced.hash(path) }
        assertArrayEquals(bytes, Files.readAllBytes(path))
    }

    @Test fun `post hash FIFO copy failure preserves source and previous installation`() {
        org.junit.jupiter.api.Assumptions
            .assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        for (update in listOf(false, true)) {
            val target = layout().copy(root = temp.resolve(if (update) "update-copy-root" else "initial-copy-root"))
            val source = distribution(if (update) "copy-update" else "copy-initial")
            val oldMarker =
                if (update) {
                    CliInstaller().install(distribution("old-copy"), target, "0.1.0")
                    Files.readAllBytes(target.root.resolve(CliInstaller.MARKER))
                } else {
                    null
                }
            var replaced: Path? = null
            var retained: Path? = null
            var foreignKey: Any? = null
            val pidFile = temp.resolve(if (update) "update-copy.pid" else "initial-copy.pid")
            val realRunner = BoundedCommandRunner(5, 2_097_152)
            val reader =
                InstallerFileReader(
                    CommandRunner { arguments, environment ->
                        val from = Path.of(arguments[6])
                        val publishing =
                            from.parent.parent.fileName
                                .toString()
                                .startsWith(".fruit-and-faults-stage-")
                        if (arguments[11] == "copy" && replaced == null && from.fileName.toString() != CliInstaller.MARKER &&
                            publishing == update
                        ) {
                            val original = from.resolveSibling("retained-${from.fileName}")
                            Files.move(from, original)
                            val fifo = ProcessBuilder(listOf("mkfifo", from.toString())).start()
                            try {
                                check(fifo.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) && fifo.exitValue() == 0)
                            } finally {
                                if (fifo.isAlive) fifo.destroyForcibly()
                            }
                            replaced = from
                            retained = original
                            foreignKey =
                                Files
                                    .readAttributes(
                                        from,
                                        java.nio.file.attribute.BasicFileAttributes::class.java,
                                        java.nio.file.LinkOption.NOFOLLOW_LINKS,
                                    ).fileKey()
                            BoundedCommandRunner(2).run(
                                probeArguments("blocked-copy", from.toString()) +
                                    listOf(arguments[12], pidFile.toString()),
                                emptyMap(),
                            )
                        } else {
                            realRunner.run(arguments, environment)
                        }
                    },
                )
            assertThrows(IllegalStateException::class.java) { CliInstaller(reader).install(source, target, "0.2.0", update) }
            val foreign = checkNotNull(replaced)
            assertEquals(
                foreignKey,
                Files
                    .readAttributes(
                        foreign,
                        java.nio.file.attribute.BasicFileAttributes::class.java,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS,
                    ).fileKey(),
            )
            assertFalse(Files.isRegularFile(foreign, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            assertTrue(Files.readString(checkNotNull(retained)).startsWith("launcher-"))
            val pid = Files.readString(pidFile).toLong()
            ProcessHandle.of(pid).ifPresent { it.onExit().get(3, java.util.concurrent.TimeUnit.SECONDS) }
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
            if (oldMarker == null) {
                assertFalse(Files.exists(target.root))
            } else {
                assertArrayEquals(oldMarker, Files.readAllBytes(target.root.resolve(CliInstaller.MARKER)))
                assertEquals("jar-old-copy", Files.readString(target.root.resolve("lib/app.jar")))
                CliInstaller().validate(target)
            }
        }
    }

    @Test fun `copy worker preserves a foreign destination appearing after preflight`() {
        val source = temp.resolve("copy-input")
        val destination = temp.resolve("copy-foreign")
        Files.writeString(source, "approved source")
        var key: Any? = null
        val realRunner = BoundedCommandRunner(5, 2_097_152)
        val reader =
            InstallerFileReader(
                CommandRunner { arguments, environment ->
                    if (arguments[11] == "copy") {
                        Files.writeString(destination, "foreign destination")
                        key =
                            Files
                                .readAttributes(
                                    destination,
                                    java.nio.file.attribute.BasicFileAttributes::class.java,
                                    java.nio.file.LinkOption.NOFOLLOW_LINKS,
                                ).fileKey()
                    }
                    realRunner.run(arguments, environment)
                },
            )
        assertThrows(IllegalStateException::class.java) { reader.copy(source, destination) }
        assertEquals("foreign destination", Files.readString(destination))
        assertEquals(
            key,
            Files
                .readAttributes(
                    destination,
                    java.nio.file.attribute.BasicFileAttributes::class.java,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS,
                ).fileKey(),
        )
    }

    @Test fun `interrupted copy worker terminates and safely cleans an untouched claim`() {
        org.junit.jupiter.api.Assumptions
            .assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val target = layout().copy(root = temp.resolve("interrupted-copy-install"))
        val source = distribution("interrupted-copy")
        val pidFile = temp.resolve("interrupted-copy.pid")
        val replaced =
            java.util.concurrent.atomic
                .AtomicReference<Path>()
        val failure =
            java.util.concurrent.atomic
                .AtomicReference<Throwable>()
        val interrupted =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        val realRunner = BoundedCommandRunner(5, 2_097_152)
        val reader =
            InstallerFileReader(
                CommandRunner { arguments, environment ->
                    if (arguments[11] != "copy") {
                        realRunner.run(arguments, environment)
                    } else {
                        val from = Path.of(arguments[6])
                        Files.move(from, from.resolveSibling("retained-${from.fileName}"))
                        val fifo = ProcessBuilder(listOf("mkfifo", from.toString())).start()
                        try {
                            check(fifo.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) && fifo.exitValue() == 0)
                        } finally {
                            if (fifo.isAlive) fifo.destroyForcibly()
                        }
                        replaced.set(from)
                        BoundedCommandRunner(
                            5,
                        ).run(probeArguments("blocked-copy", from.toString()) + listOf(arguments[12], pidFile.toString()), emptyMap())
                    }
                },
            )
        val worker =
            Thread {
                try {
                    CliInstaller(reader).install(source, target, "0.1.0")
                } catch (problem: Throwable) {
                    failure.set(problem)
                } finally {
                    interrupted.set(Thread.currentThread().isInterrupted)
                }
            }
        worker.start()
        try {
            val deadline =
                System.nanoTime() +
                    java.util.concurrent.TimeUnit.SECONDS
                        .toNanos(15)
            while (!Files.exists(pidFile) && worker.isAlive && System.nanoTime() < deadline) Thread.yield()
            assertTrue(Files.exists(pidFile))
            worker.interrupt()
            worker.join(5_000)
            assertFalse(worker.isAlive)
            assertTrue(failure.get() is IllegalStateException)
            assertTrue(interrupted.get())
            val pid = Files.readString(pidFile).toLong()
            ProcessHandle.of(pid).ifPresent { it.onExit().get(3, java.util.concurrent.TimeUnit.SECONDS) }
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
            assertFalse(Files.exists(target.root))
            assertFalse(Files.isRegularFile(replaced.get(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            Files.list(target.root.parent).use { entries ->
                assertTrue(entries.noneMatch { it.fileName.toString().startsWith(".fruit-and-faults-stage-") })
            }
        } finally {
            worker.interrupt()
            worker.join(5_000)
        }
    }

    @Test fun `a blocked ownership worker cannot block its parent`() {
        org.junit.jupiter.api.Assumptions
            .assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val path = temp.resolve("read-race")
        Files.writeString(path, "old")
        val pidFile = temp.resolve("blocked-reader.pid")
        val reader =
            InstallerFileReader(
                CommandRunner { _, _ ->
                    Files.move(path, temp.resolve("retained-read-race"))
                    val fifo = ProcessBuilder(listOf("mkfifo", path.toString())).start()
                    try {
                        check(fifo.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) && fifo.exitValue() == 0)
                    } finally {
                        if (fifo.isAlive) fifo.destroyForcibly()
                    }
                    BoundedCommandRunner(2).run(probeArguments("blocked-read", path.toString()) + pidFile.toString(), emptyMap())
                },
            )
        assertThrows(IllegalStateException::class.java) { reader.read(path) }
        val pid = Files.readString(pidFile).toLong()
        ProcessHandle.of(pid).ifPresent { it.onExit().get(3, java.util.concurrent.TimeUnit.SECONDS) }
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        assertFalse(Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals("old", Files.readString(temp.resolve("retained-read-race")))
        Files.delete(path)
    }

    @Test fun `real process timeout terminates the owned process without sleeps`() {
        val pidFile = temp.resolve("timeout.pid")
        val result = BoundedCommandRunner(2).run(probeArguments("busy", pidFile.toString()), emptyMap())
        assertTrue(result.timedOut)
        val pid = Files.readString(pidFile).toLong()
        ProcessHandle.of(pid).ifPresent { it.onExit().get(3, java.util.concurrent.TimeUnit.SECONDS) }
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
    }

    @Test fun `interrupted process terminates and restores the worker interrupt flag`() {
        val pidFile = temp.resolve("interruption.pid")
        val failure =
            java.util.concurrent.atomic
                .AtomicReference<Throwable>()
        val interrupted =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        val worker =
            Thread {
                try {
                    BoundedCommandRunner().run(probeArguments("busy", pidFile.toString()), emptyMap())
                } catch (
                    problem: Throwable,
                ) {
                    failure.set(problem)
                } finally {
                    interrupted.set(Thread.currentThread().isInterrupted)
                }
            }
        worker.start()
        try {
            val deadline =
                System.nanoTime() +
                    java.util.concurrent.TimeUnit.SECONDS
                        .toNanos(10)
            while (!Files.exists(pidFile) && worker.isAlive && System.nanoTime() < deadline) Thread.yield()
            assertTrue(Files.exists(pidFile), "Owned fixture must start before interruption")
            worker.interrupt()
            worker.join(3_000)
            assertFalse(worker.isAlive)
            assertTrue(failure.get() is IllegalStateException)
            assertTrue(interrupted.get())
            val pid = Files.readString(pidFile).toLong()
            ProcessHandle.of(pid).ifPresent { it.onExit().get(3, java.util.concurrent.TimeUnit.SECONDS) }
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        } finally {
            worker.interrupt()
            worker.join(3_000)
        }
    }

    private fun probeArguments(
        mode: String,
        path: String = "unused",
    ): List<String> {
        val javaHome = Path.of(System.getProperty("java.home"))
        val executable = javaHome.resolve(if (System.getProperty("os.name").startsWith("Windows")) "bin/java.exe" else "bin/java")
        val classes =
            Path.of(
                InstallerProbeFixture::class.java.protectionDomain.codeSource.location
                    .toURI(),
            )
        val kotlin =
            Path.of(
                kotlin.Unit::class.java.protectionDomain.codeSource.location
                    .toURI(),
            )
        val mainClasses =
            Path.of(
                CliInstaller::class.java.protectionDomain.codeSource.location
                    .toURI(),
            )
        return listOf(
            executable.toString(),
            "-Duser.home=$temp",
            "-XX:-UsePerfData",
            "-cp",
            "$classes${java.io.File.pathSeparator}$mainClasses${java.io.File.pathSeparator}$kotlin",
            InstallerProbeFixture::class.java.name,
            mode,
            path,
        )
    }

    private class MemoryPath(
        var value: String,
    ) : UserPathStore {
        override fun read(): String = value

        override fun write(value: String) {
            this.value = value
        }
    }
}

/** Owned subprocess fixture: no network, user environment writes, or sleeps. */
object InstallerProbeFixture {
    @JvmStatic fun main(arguments: Array<String>) {
        when (arguments[0]) {
            "output" -> {
                System.out.print("я".repeat(20_000))
            }

            "failure" -> {
                System.err.print("fixture failure")
                kotlin.system.exitProcess(7)
            }

            "busy" -> {
                val ready = Path.of(arguments[1])
                val pending = ready.resolveSibling("${ready.fileName}.tmp")
                Files.writeString(pending, ProcessHandle.current().pid().toString())
                Files.move(pending, ready, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                while (true) Thread.onSpinWait()
            }

            "ownership-read" -> {
                val path = Path.of(arguments[1])
                val kind = arguments[2]
                val platform = if (kind.startsWith("windows")) CliPlatform.WINDOWS else CliPlatform.MACOS
                val target = platform.layout(Path.of(arguments[3]), null, null, "/bin/zsh", Path.of(arguments[4]))
                val pathStore =
                    object : UserPathStore {
                        override fun read() = "C:\\Unrelated"

                        override fun write(value: String) = error("Unexpected write")
                    }
                try {
                    when (kind) {
                        "windows-store" -> FileUserPathStore(path).read()
                        "windows-store-write" -> FileUserPathStore(path).write("C:\\Replacement")
                        "install-marker" -> CliInstaller().validate(target)
                        else -> CliPathExposure(pathStore).add(target, "")
                    }
                    System.out.print("accepted")
                } catch (_: IllegalStateException) {
                    System.out.print("rejected")
                }
            }

            "blocked-read" -> {
                Files.writeString(Path.of(arguments[2]), ProcessHandle.current().pid().toString())
                Files
                    .newByteChannel(
                        Path.of(arguments[1]),
                        setOf(
                            java.nio.file.StandardOpenOption.READ,
                            java.nio.file.LinkOption.NOFOLLOW_LINKS,
                        ),
                    ).use { it.read(java.nio.ByteBuffer.allocate(1)) }
            }

            "blocked-copy" -> {
                val ready = Path.of(arguments[3])
                val pending = ready.resolveSibling("${ready.fileName}.tmp")
                Files.writeString(pending, ProcessHandle.current().pid().toString())
                Files.move(pending, ready, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                // A FIFO has size zero, forcing the standard Unix buffered-copy fallback.
                Files.copy(Path.of(arguments[1]), Path.of(arguments[2]), java.nio.file.StandardCopyOption.COPY_ATTRIBUTES)
            }
        }
    }
}
