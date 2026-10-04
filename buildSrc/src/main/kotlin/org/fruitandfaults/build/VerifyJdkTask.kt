package org.fruitandfaults.build

import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class ProbeResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
)

fun interface CommandRunner {
    fun run(
        arguments: List<String>,
        environment: Map<String, String>,
    ): ProbeResult

    fun runInDirectory(
        arguments: List<String>,
        environment: Map<String, String>,
        directory: Path,
    ): ProbeResult = run(arguments, environment)
}

/** Owns one process, a bounded output collector, and a deadline including collector cleanup. */
class BoundedCommandRunner(
    private val timeoutSeconds: Long = 15,
    private val outputLimit: Int = 16_384,
) : CommandRunner {
    override fun run(
        arguments: List<String>,
        environment: Map<String, String>,
    ): ProbeResult = execute(arguments, environment, null)

    override fun runInDirectory(
        arguments: List<String>,
        environment: Map<String, String>,
        directory: Path,
    ): ProbeResult = execute(arguments, environment, directory)

    private fun execute(
        arguments: List<String>,
        environment: Map<String, String>,
        directory: Path?,
    ): ProbeResult {
        check(!Thread.currentThread().isInterrupted) { "Verification interrupted. Retry the task." }
        require(timeoutSeconds in 1..60)
        require(outputLimit in 1..2_097_152)
        val builder = ProcessBuilder(arguments).redirectErrorStream(true)
        if (directory != null) builder.directory(directory.toFile())
        builder.environment().putAll(environment)
        val process =
            try {
                builder.start()
            } catch (failure: java.io.IOException) {
                throw IllegalStateException(
                    "Could not launch ${Path.of(arguments.first()).fileName}. Check executable/JDK installation.",
                    failure,
                )
            }
        val collector = Executors.newSingleThreadExecutor { job -> Thread(job, "cli-install-probe").apply { isDaemon = true } }
        val bytes = ByteArrayOutputStream()
        val truncated =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        val output =
            collector.submit {
                process.inputStream.use { stream ->
                    val buffer = ByteArray(1024)
                    var count = stream.read(buffer)
                    while (count >= 0) {
                        synchronized(bytes) {
                            val retained = minOf(count, outputLimit - bytes.size())
                            if (retained < count) truncated.set(true)
                            bytes.write(buffer, 0, retained)
                        }
                        count = stream.read(buffer)
                    }
                }
            }
        try {
            process.outputStream.close()
            if (!process.waitFor(
                    timeoutSeconds,
                    TimeUnit.SECONDS,
                )
            ) {
                return ProbeResult(-1, "Verification timed out after $timeoutSeconds seconds.", true)
            }
            try {
                output.get(1, TimeUnit.SECONDS)
            } catch (_: java.util.concurrent.TimeoutException) {
                return ProbeResult(-1, "Verification output did not close.", true)
            }
            return ProbeResult(process.exitValue(), synchronized(bytes) { bytes.toString(Charsets.UTF_8) }, truncated = truncated.get())
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Verification interrupted. Retry the task.", failure)
        } finally {
            try {
                process.toHandle().descendants().use { descendants ->
                    descendants.forEach { it.destroyForcibly() }
                }
            } catch (
                _: UnsupportedOperationException,
            ) {
                // Parent cleanup must still run on limited providers.
            } catch (
                _: SecurityException,
            ) {
                // Parent cleanup remains independent of descendant access.
            } finally {
                if (process.isAlive) process.destroyForcibly()
                try {
                    process.inputStream.close()
                } finally {
                    output.cancel(true)
                    collector.shutdownNow()
                    try {
                        collector.awaitTermination(1, TimeUnit.SECONDS)
                    } catch (
                        _: InterruptedException,
                    ) {
                        Thread.currentThread().interrupt()
                    }
                }
            }
        }
    }
}

class JdkVerifier(
    private val runner: CommandRunner = BoundedCommandRunner(),
) {
    fun verify(
        home: Path,
        platform: CliPlatform,
    ): String {
        val versions =
            listOf("java", "javac").map { executable ->
                val suffix = if (platform == CliPlatform.WINDOWS) ".exe" else ""
                val result = runner.run(listOf(home.resolve("bin/$executable$suffix").toString(), "-version"), emptyMap())
                val match =
                    if (executable == "java") {
                        Regex("(?:java|openjdk) version \\\"(\\d+)([^\\\"]*)\\\"").find(result.output)
                    } else {
                        Regex("javac (\\d+)(\\S*)").find(result.output)
                    }
                check(!result.timedOut && !result.truncated && result.exitCode == 0 && match?.groupValues?.get(1) == "26") {
                    "Expected JDK 26 java and javac; observed $executable verification failure. Install a JDK 26 for ${platform.name}, set JAVA_HOME to its root, and rerun setupCli. No JDK was downloaded."
                }
                "$executable ${match.groupValues[1]}${match.groupValues[2]}"
            }
        return versions.joinToString("; ")
    }
}

@DisableCachingByDefault(because = "Checks locally installed JDK executables")
abstract class VerifyJdkTask : CliEnvironmentTask() {
    @TaskAction fun verifyJdk() {
        logger.lifecycle(JdkVerifier().verify(Path.of(javaHome.get()), CliPlatform.detect(osName.get())))
    }
}
