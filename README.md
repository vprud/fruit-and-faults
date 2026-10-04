# fruit-and-faults

A local Java course CLI. Install the CLI from this source repository, then keep
your learner game in a separate directory. JDK **26** (both `java` and `javac`)
and Git are prerequisites. The installer verifies your selected JDK; it does
not download or install Java. The first source build may download Gradle and
build dependencies. Installed CLI commands do not contact a service.

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

## Use and update the installed course

Inside your separate learner workspace, use `status`, `check`, `hint`, `next`,
and `list`. `start <directory>` creates the workspace with a preview; `--yes`
accepts it for noninteractive use. `--help` describes flags. GitHub publishing
is optional. Offline lesson checks require the learner wrapper distribution,
JDK, and dependencies to be present in the local Gradle cache.

To update, return to the **CLI source repository**, inspect your source changes,
run `git pull`, and rerun `setupCli -PcliForce=true` (with `gradlew.bat` on
Windows). There is no online CLI update command. Your learner workspace and
its progress are separate from the CLI installation. A compatible appended
course continues through the ordinary `status` and `next` commands.

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

Native platform evidence and the temporary-root smoke recipe are recorded in
[the installation smoke matrix](docs/testing/phase-a-installation-smoke.md).
