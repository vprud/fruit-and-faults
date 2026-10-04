package org.fruitandfaults.build

import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

@DisableCachingByDefault(because = "Removes an exact marker-owned installation after explicit force")
abstract class UninstallCliTask : CliEnvironmentTask() {
    @TaskAction fun uninstallCli() {
        val target = installation()
        logger.lifecycle(
            "Remove marked CLI distribution: ${target.root}; owned command: ${target.command}; marked profile block: ${target.profile ?: "user PATH/manual"}",
        )
        check(force.get()) { "Uninstall requires -PcliForce=true. Only matching owned installation/PATH/profile state will be removed." }
        val installer = CliInstaller()
        val exposure = CliPathExposure(pathStore())
        if (Files.exists(target.root, NOFOLLOW_LINKS)) installer.validate(target)
        exposure.validateRemoval(target)
        exposure.remove(target)
        installer.uninstall(target)
        logger.lifecycle("Owned CLI installation removed. Learner workspaces were not affected.")
    }
}
