plugins {
    base
    alias(libs.plugins.spotless)
}

repositories {
    mavenCentral()
}

spotless {
    kotlin {
        target("buildSrc/src/**/*.kt")
        ktlint()
    }
    kotlinGradle {
        target("*.gradle.kts", "buildSrc/*.gradle.kts")
        ktlint()
    }
}

tasks.check {
    dependsOn(":app:check", "checkInstaller")
}

// buildSrc is unavailable through Gradle's public included-build task dependency API.
// A separate, bounded verification runs only for check, never for ordinary packaging/help.
tasks.register<Exec>("checkInstaller") {
    group = "verification"
    description = "Runs redirected buildSrc installer tests."
    workingDir(rootDir)
    val wrapper =
        if (providers.systemProperty("os.name").get().startsWith("Windows")) {
            listOf("cmd.exe", "/d", "/c", "gradlew.bat")
        } else {
            listOf(rootProject.file("gradlew").absolutePath)
        }
    commandLine(
        wrapper + listOf("-p", "buildSrc", "test", "--console=plain") +
            if (gradle.startParameter.isOffline) listOf("--offline") else emptyList(),
    )
    timeout.set(java.time.Duration.ofMinutes(5))
}

tasks.named("spotlessApply") {
    dependsOn(":app:spotlessApply")
}
