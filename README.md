# Fruit & Faults

[![Code quality](https://github.com/vprud/fruit-and-faults/actions/workflows/quality.yml/badge.svg)](https://github.com/vprud/fruit-and-faults/actions/workflows/quality.yml)
[![Codecov](https://codecov.io/gh/vprud/fruit-and-faults/branch/main/graph/badge.svg)](https://codecov.io/gh/vprud/fruit-and-faults)
[![Java 26](https://img.shields.io/badge/Java-26-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/26/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

Fruit & Faults is a local, interactive Java course that teaches through a small
game project. The CLI gives you one lesson at a time, prepares a separate Git
workspace, checks your work, offers progressive hints, and remembers your
progress. You write every solution yourself; the course never silently rewrites
your code.

Phase A currently contains four lessons covering diagnostics, coordinates and
directions, field boundaries, and immutable game state. A browser-based game
renderer is planned but is not included yet.

## What you need

- JDK 26: both `java -version` and `javac -version` must report version 26
- Git
- macOS, Linux, or Windows

The first build may download Gradle and project dependencies. Once installed
and prepared, the course itself does not require a network service.

> [!IMPORTANT]
> The native macOS learner journey is verified. Native Windows commonly lacks
> the secure filesystem capability required by workspace operations, so a
> successful installation or `--version` check does not yet guarantee that the
> course can run there. Native Windows and Linux installation smoke tests are
> still pending. See the [platform evidence](docs/testing/phase-a-installation-smoke.md).

## Quick start

### macOS and Linux

From the cloned source repository:

```sh
java -version
javac -version
./gradlew check
./gradlew setupCli
```

Open a new terminal, verify the installation, and create a learner workspace
outside this repository:

```sh
fruit-and-faults --version
fruit-and-faults start "$HOME/Projects/my-fruit-game" --yes
cd "$HOME/Projects/my-fruit-game"
fruit-and-faults status
fruit-and-faults lesson
```

On macOS, the CLI is installed under
`~/Library/Application Support/FruitAndFaults`. On Linux, it is installed under
`$XDG_DATA_HOME/fruit-and-faults` when that variable contains an absolute path,
or under `~/.local/share/fruit-and-faults` otherwise. The command is exposed as
`~/.local/bin/fruit-and-faults`; the installer explains any required PATH
change.

### Windows

From PowerShell or Command Prompt in the cloned source repository:

```powershell
java -version
javac -version
.\gradlew.bat check
.\gradlew.bat setupCli
```

Open a new terminal, then run:

```powershell
fruit-and-faults --version
fruit-and-faults start "C:\Projects\my-fruit-game" --yes
```

The CLI is installed under `%LOCALAPPDATA%\Programs\FruitAndFaults`, and only
your user PATH is updated. Administrator access is not required. The learner
workspace still depends on the Windows filesystem limitation described above.

## The learning loop

Read the current instructions with `lesson`, then start with `check`. The first lesson intentionally contains a compilation
error, so a failing check is part of the course—not a broken installation.

```text
lesson → edit the game → check → commit → next
                         ↑                 │
                         └──── new lesson ─┘
```

1. Run `fruit-and-faults lesson` to read the current instructions; use `status` for progress and the next action.
2. Edit the game in your learner workspace.
3. Run `fruit-and-faults check` and use `fruit-and-faults hint` when needed.
4. When the check passes, review and commit your changes with Git.
5. Run `fruit-and-faults next`, answer the reflection question, and confirm the
   next lesson.

You can stop at any point. Run `status` from the workspace—or `start` with the
same workspace path—to continue later. Commands also work from nested workspace
directories.

## Commands

| Command | What it does |
| --- | --- |
| `fruit-and-faults start <directory> [--yes]` | Preview, create, or resume a Git-backed learner workspace. |
| `fruit-and-faults status` | Show the current lesson, relevant files, hints, Git state, and next action. |
| `fruit-and-faults check` | Run the visible tests and independent behavior checks. |
| `fruit-and-faults hint` | Reveal the next of three persisted hint levels. |
| `fruit-and-faults lesson` | Read the active lesson's complete instructions without changing progress. |
| `fruit-and-faults next [--answer <id>] [--yes]` | Validate, reflect, and open the next lesson after a clean commit. |
| `fruit-and-faults list` | Show lesson titles and progress without revealing future exercises. |

Global options include `--help`, `--version`, `--no-color`, and `--verbose`.
Human-readable output goes to stdout and failures go to stderr. For scripts,
the CLI uses these exit codes:

| Code | Meaning |
| ---: | --- |
| 0 | Success |
| 1 | Lesson is incomplete |
| 2 | Invalid command or option |
| 3 | Unsafe or conflicting workspace state |
| 4 | Compilation or test failure |
| 5 | Timeout or interruption |
| 10 | Internal error |

In a non-interactive terminal, `start` requires `--yes`. While a lesson is
active, `next` requires both `--answer <id>` and `--yes`; the displayed choices
include stable answer IDs.

## Installing JDK 26 with SDKMAN

[SDKMAN](https://sdkman.io/) can install and select JDK 26 on macOS and Linux.
It does not support native Windows terminals; use a native Windows JDK installer
there.

Install SDKMAN, start a new shell or load it in the current one, and select a
Java 26 distribution:

```sh
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk list java
sdk install java 26.0.1-tem
sdk default java 26.0.1-tem
java -version
javac -version
```

The project is currently verified with Temurin 26.0.1. If that exact identifier
is no longer available, choose a current Java 26 identifier from
`sdk list java`.

## Progress, Git, and safety

The learner workspace stores progress in `.fruit-and-faults/progress.json` and
tracks course-owned files in `managed-files.json` and `workspace.properties`.
Commit these files with your work so a clone retains its progress. Build output,
caches, and temporary transition journals are ignored.

Before `next`, inspect `git status`, `git diff`, and `git diff --cached`, stage
the intended files, and create your own local commit. A wrong reflection answer,
dirty workspace, or missing commit does not discard valid progress. After the
fourth lesson, follow the CLI's metadata-commit advice so completion is also
recorded in a clone.

Publishing to GitHub is optional. The CLI never requests a token, creates a
remote, stages, commits, pushes, or contacts GitHub. If you want to publish,
create an empty public repository and use Git normally:

```sh
git remote add origin https://github.com/YOUR-ACCOUNT/YOUR-REPOSITORY.git
git branch -M main
git push -u origin main
```

## Offline use

An installed course can run offline when JDK 26, Git, the bundled Gradle wrapper
distribution, and pinned JUnit dependencies are already cached. Run
`./gradlew check` (`.\gradlew.bat check` on Windows) before `setupCli` while a
network connection is available. This prepares both project and learner-test
dependencies in the same Gradle user home.

The CLI deliberately does not download or repair an incomplete wrapper cache.
See the [lesson check contract](docs/lesson-check-contract.md) for the detailed
offline and validation behavior.

## Update or uninstall

To update the CLI, return to this source repository, review and pull the source
changes, then reinstall:

```sh
git pull
./gradlew setupCli -PcliForce=true
```

On Windows, use `gradlew.bat`. There is no online update command, and updating
the CLI does not alter the separate learner workspace.

To uninstall:

```sh
./gradlew uninstallCli -PcliForce=true
```

```powershell
.\gradlew.bat uninstallCli -PcliForce=true
```

Installation and removal are ownership-aware: they stop rather than overwrite
an unmarked directory, foreign command, symlinked path, changed installed file,
or edited installer-owned profile block. Uninstalling never removes a learner
workspace.

Advanced installation overrides and the temporary-root smoke-test recipe are
documented in the
[installation smoke matrix](docs/testing/phase-a-installation-smoke.md).

## Development

Use the repository Gradle wrapper and its Java 26 toolchain. The complete
quality gate runs tests, Spotless, Checkstyle, Error Prone with NullAway, and
JaCoCo coverage verification with a minimum of 80% line coverage:

```sh
./gradlew check
```

After changing Java or Gradle files, format them before running the gate:

```sh
./gradlew spotlessApply
./gradlew check
```

Build installable distributions with:

```sh
./gradlew :app:installDist :app:distZip
```

For guided testing, see the
[pilot observer guide](docs/pilot/phase-a-observer-guide.md) and
[intentional-error drills](docs/pilot/phase-a-intentional-errors.md).

## License

Licensed under the [Apache License 2.0](LICENSE).
