# Phase A installation smoke evidence

Task 13 evidence, 2026-10-04. Every executed installer task used explicit
temporary home, distribution, profile, local-app-data, XDG, PATH-file, shell,
OS, current-PATH, and JDK inputs. No real user profile, user PATH, registry,
installation directory, or learner workspace was changed.

| Native platform | Shell / JDK | Result |
| --- | --- | --- |
| Windows | PowerShell/cmd; JDK 26 required | **UNVERIFIED**: no native Windows runner available. Windows root/PATH and registry argument decisions have automated tests using an injected boundary; these are not native evidence. |
| macOS 26.5.2 (build 25F84) | `/bin/zsh`; Temurin java/javac 26.0.1 | **PASS**: setup, installed launcher, sourced temporary profile command lookup, unchanged repeat setup, refusal without force, owned uninstall and repeated uninstall. |
| Linux | Bash/zsh; JDK 26 required | **UNVERIFIED**: no native Linux runner available. XDG/default roots and Bash/zsh ownership have filesystem tests on macOS; these are not native evidence. |

The three-platform acceptance criterion remains open until native Windows and
Linux runs are recorded. Never label a platform-name override as a native run.

## Executed macOS recipe

From the CLI source checkout, `mktemp -d /private/tmp/fruit-and-faults-task13.XXXXXX`
created `/private/tmp/fruit-and-faults-task13.0dQv9v`. The following arguments
were supplied to **every** setup/uninstall invocation:

```sh
-PcliUserHome="/private/tmp/fruit-and-faults-task13.0dQv9v/Дом пользователя"
-PcliInstallRoot="/private/tmp/fruit-and-faults-task13.0dQv9v/Install Дистрибутив"
-PcliProfile="/private/tmp/fruit-and-faults-task13.0dQv9v/Profile zsh"
-PcliLocalAppData="/private/tmp/fruit-and-faults-task13.0dQv9v/App Data"
-PcliXdgDataHome="/private/tmp/fruit-and-faults-task13.0dQv9v/XDG Data"
-PcliUserPathFile="/private/tmp/fruit-and-faults-task13.0dQv9v/User PATH"
-PcliCurrentPath=/usr/bin:/bin
-PcliShell=/bin/zsh
-PcliOsName="Mac OS X"
-PcliJavaHome=<explicit installed Temurin 26.0.1 directory>
```

The installed JDK was read/executed, never changed. Its commands reported
`openjdk version "26.0.1"`, Temurin `26.0.1+8`, and `javac 26.0.1`.

1. `./gradlew setupCli --offline --console=plain <all arguments above>`:
   exit 0, detected `java 26.0.1; javac 26.0.1`, installed the distribution,
   created the owned command link and marked zsh profile, and printed
   `fruit-and-faults 0.1.0` from the installed launcher.
2. `/bin/zsh -f -c 'source "$1"; command -v fruit-and-faults; fruit-and-faults --version' installation-smoke '<temporary Profile zsh>'`:
   exit 0; lookup resolved the temporary `Дом пользователя/.local/bin`
   command; version was `fruit-and-faults 0.1.0`. The PATH change existed only
   in this disposable child shell.
3. Repeat setup with identical arguments and no force flag: exit 0.
   SHA-256 of the installation marker and profile was identical before/after:
   marker `490dd56c174a2f75ca2d9d87b1eb5df22b687d483c7e225dfc3d90ed2b6a3160`;
   profile `1d3e2bece4839dc4a495194f6e522cbaa49a988682def7d60728118a05cf3c3b`.
4. `./gradlew uninstallCli --offline --console=plain <same arguments>`:
   expected failure requiring `-PcliForce=true`; existing state was retained.
5. `./gradlew uninstallCli -PcliForce=true --offline --console=plain <same arguments>`:
   exit 0; exact owned distribution and command marker/link were removed;
   the temporary profile became empty. The home and smoke root were retained.
6. Repeat forced uninstall with the same arguments: exit 0; no additional
   user state was removed. Temporary empty directories/profile remain for
   inspection; no broad cleanup was performed.

## Native Windows and Linux runs still required

On an explicitly documented native runner, record OS/build, shell, JDK
`java -version` and `javac -version`, exact argument list, exit codes, command
lookup/version, repeat setup, and repeat owned uninstall. Include spaces and
Cyrillic in the temporary home/root. Use all overrides consistently.

For Windows, choose a dedicated temporary home, temporary LOCALAPPDATA root,
and `-PcliUserPathFile=<temporary file>`. Seed that file with unrelated entries
and an upper/lower-case duplicate of the target bin; verify de-duplication and
preservation. Run the installed `.bat` launcher from PowerShell and cmd. This
file boundary protects the actual user registry. Real registry smoke, if later
desired, requires a disposable Windows account and separate authorization.

For Linux, choose a dedicated temporary home and XDG root, explicitly select
`-PcliShell=/bin/bash` or `/bin/zsh`, and override the matching temporary
profile. Run `setupCli`, source only that temporary profile in a child shell,
verify command lookup and `--version`, then run repeated forced uninstall.
Repeat with default data-root selection and the other supported shell. An
unknown shell must receive manual guidance and no guessed profile edit.

## Automated and distribution evidence

Installer tests use canonical temporary roots, real safe filesystem operations,
and in-memory/file/injected Windows PATH boundaries. They cover marker/version
ownership, foreign/modified files, symlink/path rejection, staged copy failure,
spaces/Unicode, repeated setup/removal, user PATH preservation, opaque profile
bytes/CRLF/permissions, exact marked blocks, and bounded verification output.
Owned Java fixtures verify process nonzero exit, truncation, timeout, and
interruption/termination without sleeps or network services.

`./gradlew :app:clean :app:installDist :app:distZip --offline` passed. The ZIP
contains `bin/fruit-and-faults`, `bin/fruit-and-faults.bat`, the app JAR with
course resources, and four runtime dependency JARs. A clean generated-output
build eliminated obsolete sample launcher artifacts from the existing cache.
Root `./gradlew check` includes the buildSrc installer tests through the bounded
`checkInstaller` task, plus app tests, Spotless, Checkstyle, Error Prone/NullAway,
and JaCoCo verification.

## Task 13 review-fix smoke

The 2026-10-04 fix round used a fresh dedicated root,
`/private/tmp/fruit-and-faults-task13-fix.WnjeF8`, with the exact same override
shape as the recipe above: `Дом пользователя`, `Install Дистрибутив`,
`Profile zsh`, `App Data`, `XDG Data`, and `User PATH` beneath that root,
explicit `/usr/bin:/bin`, `/bin/zsh`, `Mac OS X`, and the installed Temurin
26.0.1 JDK. No invocation used real user-state locations.

Setup, temporary-profile child-shell lookup/version, identical repeat setup,
forced uninstall, and repeated forced uninstall passed again. The actual
Gradle task exercised the bounded file worker, not only a test classpath.
The owned tree/link/marker were removed; temporary empty directories/profile
were retained. Native Windows/Linux remain UNVERIFIED.

Review regressions cover foreign empty/file/symlink targets appearing during
staging, exclusive publication, recovery after claimed-copy failure, unknown
claim-state retention, Windows delimiter rejection before mutations, relative
XDG fallback, conditional POSIX assertions, FIFO command/install/PATH markers,
redirected PATH reads/writes, opaque bytes, replacement identity, and an owned
blocked file-read child terminated at its deadline. All ownership-state reads
and inventory digest opens occur only in bounded workers; special files are rejected
before opening whenever observed. A race after inspection can block the child,
not the Gradle parent. State is capped at 1 MiB and inventory files at 512 MiB;
each worker has a five-second deadline and at most 2 MiB captured output.

## Task 13 copy-worker review fix

The next 2026-10-04 fix round used
`/private/tmp/fruit-and-faults-task13-copy.L5SaCL` with all ten explicit overrides
and the same named spaces/Cyrillic locations, zsh, and Temurin 26.0.1 inputs.
Native macOS setup, sourced temporary-profile lookup/version, unchanged repeat
setup, forced uninstall, and repeated uninstall passed. Native Windows/Linux
remain UNVERIFIED; making tests portable is not native smoke evidence.

Windows delimiter tests now separate representable semicolon paths from raw
quote/control parsing inside expected-failure assertions. The raw policy is
also tested without depending on the native path parser.

Every production source byte open and copy is now inside an owned bounded
worker. Copy uses no-follow source identity/size/digest binding, exclusive
destination creation anchored by a secure directory stream where supported,
checked directory/identity fallback otherwise, and parent digest/inventory
verification. Executable permissions and timestamps are retained. Actual
Unix FIFO fallback copy fixtures time out in the owned child, whose PID is
verified dead; initial installation and forced-update publication preserve
foreign source state and the previous installation. Staging cleanup uses
recorded original keys/hashes, never newly observed ownership. Additional
checks cover a foreign destination appearing after preflight and interruption
with process termination, flag preservation, and safe untouched-stage cleanup.
Uncertain partial files are retained, not broadly deleted. Worker limits remain
five seconds, 512 MiB input, and 2 MiB captured output. Root installer-suite
verification has a five-minute bound to allow slower native Windows process
startup; it still runs only with the verification task.
