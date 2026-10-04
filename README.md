# fruit-and-faults

A local Java course CLI. Install the CLI from this source repository, then keep
your learner game in a separate directory. JDK **26** (both `java` and `javac`)
and Git are prerequisites. The installer verifies your selected JDK; it does
not download or install Java. The first source build may download Gradle and
build dependencies. Installed CLI commands do not contact a service.

Phase A implements four lessons: diagnostics, coordinates/directions, field
boundaries, and immutable game state. It has no browser renderer yet. Installer
tasks support Windows, macOS, and Linux, but course operations require a Java
filesystem provider with `SecureDirectoryStream` and stable file identities.
The native macOS learner journey is verified. Native Windows commonly lacks
that capability; Windows learner-workspace support is an acceptance blocker,
even if installation succeeds. Native Windows/Linux installation smoke is
still unverified. See [platform evidence](docs/testing/phase-a-installation-smoke.md).

## Windows (PowerShell or cmd)

Open a terminal in the source repository. Check `java -version` and
`javac -version`: both must report 26. Set `JAVA_HOME` to the JDK directory if
needed, then run:

```powershell
.\gradlew.bat setupCli
```

The distribution is installed under
`%LOCALAPPDATA%\Programs\FruitAndFaults`. Only your **user** PATH is updated;
administrator access is unnecessary. Open a new terminal and verify:

```powershell
fruit-and-faults --version
fruit-and-faults start "C:\Projects\Моя игра" --yes
```

The start command requires the filesystem capability described above; do not
interpret a successful `--version` as proof that the Windows course can run.

Remove the CLI from the source repository with:

```powershell
.\gradlew.bat uninstallCli -PcliForce=true
```

## macOS

With JDK 26 and Git installed, run these commands in the source repository:

```sh
java -version
javac -version
./gradlew setupCli
```

The distribution goes to `~/Library/Application Support/FruitAndFaults`, and
the command is linked from `~/.local/bin/fruit-and-faults`. If that directory
is missing from PATH, the installer previews the affected profile and appends
an owned block to `~/.zprofile` for zsh or `~/.bash_profile` for Bash. Open a new
terminal, then run:

```sh
fruit-and-faults --version
fruit-and-faults start "$HOME/Projects/Моя игра" --yes
```

Uninstall from the source repository with `./gradlew uninstallCli -PcliForce=true`.

## Linux

Check that `java -version` and `javac -version` report 26, then run
`./gradlew setupCli` in the source repository. The distribution goes to
`$XDG_DATA_HOME/fruit-and-faults` when XDG_DATA_HOME is set to an absolute path,
otherwise `~/.local/share/fruit-and-faults` (blank/relative values are ignored).
The command is linked from
`~/.local/bin/fruit-and-faults`. Bash uses `~/.bashrc`; zsh uses `~/.zshrc`.
Open a new terminal and run `fruit-and-faults --version`.

For another Unix shell, the installer prints a manual PATH instruction and
does not choose or edit a profile. Add `~/.local/bin` using that shell's own
syntax. Uninstall with `./gradlew uninstallCli -PcliForce=true`.

## Complete the four lessons

Create the learner workspace outside this CLI source repository, then change
into it. The commands also work from its nested directories.

| Command | Purpose |
| --- | --- |
| `fruit-and-faults start <directory> [--yes]` | Preview/create a Git-backed workspace or safely resume it. |
| `fruit-and-faults status` | Show the current goal, files, hints, local Git facts, and one next action. |
| `fruit-and-faults check` | Run cumulative visible tests and independent public-behavior checks. |
| `fruit-and-faults hint` | Reveal one of three persisted hint levels. |
| `fruit-and-faults next [--answer <option-id>] [--yes]` | Check, reflect, require a new clean local commit, and preview the next disclosure. |
| `fruit-and-faults list` | Show lesson titles and states without future exercise details. |

`--help`, `--version`, `--no-color`, and `--verbose` are supported. Human views
go to stdout; failures go to stderr. Exit codes distinguish incomplete work
(1), usage (2), unsafe/conflicting state (3), compilation/tests (4), timeout or
interruption (5), and internal errors (10); success is 0.

Start with `check`: lesson 1 intentionally contains a compilation error in
learner source. Read the reported source location and the lesson goal, make
your own edit, and rerun `check`. Later tests accumulate. The CLI supplies
scaffolds and hints, and never applies a solution or rewrites your edits.

After a passing check, inspect `git status`, `git diff`, and `git diff --cached`,
stage the intended files, and make your own local commit. Then use interactive
`next` to select a numbered reflection answer and confirm the disclosure.
Without a terminal, use `next --answer <option-id> --yes`; the displayed choices
include stable IDs. A wrong answer or dirty/missing commit preserves progress.
After lesson 4, follow the final metadata-commit advice so a clone also records
completion. A completed course needs no new answer: `next --yes` is idempotent
and opens a compatible later continuation when one is installed.

The workspace tracks `.fruit-and-faults/progress.json`, `managed-files.json`,
and `workspace.properties`. Commit them with your work. Gradle output, caches,
and temporary transition journals are ignored. Stop at any time; `status` or
`start <same-directory>` resumes the current lesson. Keep backups or local Git
history when intentionally experimenting with state or immutable checks.

## Offline preparation and public GitHub publishing

An installed course runs offline once JDK 26, Git, the exact bundled wrapper
distribution, and pinned JUnit dependencies are cached. `setupCli` installs
runtime dependencies but does not prepare the learner test dependencies. During
initial source setup, while network access is deliberately available, run
`./gradlew check` (`.\gradlew.bat check` on Windows) before `setupCli`. This
resolves the matching test dependencies and verifies the course. Use the same
Gradle user home for later checks; a custom home needs its own prepared cache.
The wrapper URL must match the installed cache exactly, including its `.ok`
completion marker. Cold/unsafe wrapper caches fail before wrapper launch;
the CLI never downloads or repairs them. Dependency cache failures are toolchain
diagnostics, not exercise failures. See [the check contract](docs/lesson-check-contract.md).

Publishing is optional. The disclosed `docs/publishing-to-github.md` teaches
creating an empty **public** GitHub repository through the website, with no
generated README/license/gitignore. Review and commit selected files locally,
then run these commands with your own repository URL:

```sh
git remote add origin https://github.com/YOUR-ACCOUNT/YOUR-REPOSITORY.git
git branch -M main
git push -u origin main
```

Use GitHub's normal credential flow; account passwords do not authenticate Git
over HTTPS. The CLI never asks for a token, creates a remote, stages, commits,
pushes, or contacts GitHub. Missing origin/upstream/network does not block a
lesson. A local clone carries committed progress; prepare its new machine's
JDK/cache separately and run `status` in that clone.

## Update and remove the installed course

Inside your separate learner workspace, use `status`, `check`, `hint`, `next`,
and `list`. `start <directory>` creates the workspace with a preview; `--yes`
accepts it for noninteractive use. `--help` describes flags. GitHub publishing
is optional. Offline lesson checks require the learner wrapper distribution,
JDK, and dependencies to be present in the local Gradle cache.

To update, return to the **CLI source repository**, inspect your source changes,
run `git pull`, and rerun `setupCli -PcliForce=true` (with `gradlew.bat` on
Windows). There is no online CLI update command. Your learner workspace and
its progress are separate from the CLI installation. A compatible appended
course continues through `status` and `next --yes` in the same learner workspace.
The current release contains only four lessons; the automated continuation
fixture is not released Phase B content. Compatible future releases must ship
trusted historical contracts; incompatible/future formats stop before mutation.

Installation and removal are repeatable. An unchanged setup needs no force
flag. Replacing an existing owned distribution and uninstalling require
`-PcliForce=true`; paths are printed before mutation. An unmarked directory,
foreign command, symlinked path, changed installed file, or edited owned
profile block stops the corresponding operation with preservation guidance.
Uninstall removes only matching owned files/command/PATH/profile content,
leaves unrelated profile bytes and PATH entries intact, and never removes a
learner workspace. A newly created profile can remain as an empty file.

For isolated testing or custom locations, tasks accept absolute-path overrides:
`-PcliUserHome=...`, `-PcliLocalAppData=...`, `-PcliXdgDataHome=...`,
`-PcliInstallRoot=...`, `-PcliProfile=...`, and `-PcliJavaHome=...`.
`-PcliShell=/bin/zsh` selects a known shell. `-PcliCurrentPath=...` supplies the
PATH observation; `-PcliUserPathFile=...` redirects Windows user PATH to a
regular test file instead of the registry. Use the same overrides on setup and
uninstall. Profiles and all existing installer path components must be regular
paths, not symlinks. Custom install roots must be dedicated directories.
Windows install roots cannot contain semicolons, quotes, or control characters.
Ownership markers, profiles, and redirected PATH state must be regular files
under 1 MiB; bounded read workers reject substituted or special files safely.
Distribution copies also use bounded owned workers and exclusive destinations.
Uncertain partial or substituted staging state is retained for inspection.
Copied-file ownership uses the worker-created identity, not a later replacement.
Interrupted updates preserve cancellation and restore the verified prior
installation when the incomplete claim can be safely removed.

Native platform evidence and the temporary-root smoke recipe are recorded in
[the installation smoke matrix](docs/testing/phase-a-installation-smoke.md).

For development, use the repository wrapper and Java 26 toolchain. After Java
or build edits, run focused tests, `./gradlew spotlessApply`, inspect the diff,
and run `./gradlew check`. The complete gate includes installer tests,
Checkstyle, Error Prone/NullAway, Spotless, and at least 80% JaCoCo line coverage.
Build distributions with `./gradlew :app:installDist :app:distZip`; they contain
both Unix and Windows launchers. [Pilot observation](docs/pilot/phase-a-observer-guide.md)
and [intentional-error drills](docs/pilot/phase-a-intentional-errors.md) keep
human assessment separate from automated completion.
