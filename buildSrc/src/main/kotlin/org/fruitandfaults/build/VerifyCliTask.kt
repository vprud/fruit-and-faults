package org.fruitandfaults.build

import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.nio.file.Path

class CliVerifier(
    private val runner: CommandRunner = BoundedCommandRunner(),
) {
    fun verify(
        target: InstallLayout,
        version: String,
        javaHome: Path,
    ): String {
        CliInstaller().validate(target)
        val executable =
            target.root.resolve(
                if (target.platform ==
                    CliPlatform.WINDOWS
                ) {
                    "bin/fruit-and-faults.bat"
                } else {
                    "bin/fruit-and-faults"
                },
            )
        val arguments =
            if (target.platform ==
                CliPlatform.WINDOWS
            ) {
                listOf("cmd.exe", "/d", "/c", "fruit-and-faults.bat", "--version")
            } else {
                listOf(executable.toString(), "--version")
            }
        val environment = mapOf("JAVA_HOME" to javaHome.toString())
        val result =
            if (target.platform == CliPlatform.WINDOWS) {
                runner.runInDirectory(arguments, environment, target.root.resolve("bin"))
            } else {
                runner.run(arguments, environment)
            }
        check(!result.timedOut && !result.truncated && result.exitCode == 0 && result.output.trim() == "fruit-and-faults $version") {
            "Expected installed fruit-and-faults $version; launcher verification failed${if (result.timedOut) " (timeout)" else " (exit ${result.exitCode})"}. Check JDK 26/JAVA_HOME and rerun setupCli."
        }
        return result.output.trim()
    }
}

@DisableCachingByDefault(because = "Verifies a locally installed launcher")
abstract class VerifyCliTask : CliEnvironmentTask() {
    @TaskAction fun verifyCli() {
        logger.lifecycle(CliVerifier().verify(installation(), cliVersion.get(), Path.of(javaHome.get())))
    }
}
