# Phase A Learning Cycle Design

## Purpose

Phase A delivers the first complete vertical slice of the interactive Java game course. A learner installs a local CLI, creates a separate Git-backed workspace, completes four cumulative lessons, interprets compiler and test diagnostics, answers short reflection questions, and records each valid step in a local commit. Publishing to a public GitHub repository is taught and encouraged but never required to run the course.

The design optimizes for a small, understandable teaching tool, safe learner experimentation, deterministic feedback, and a path from a successful Phase A pilot to later course phases without restarting the learner project.

## Scope

Phase A includes:

- a separately installed Java CLI;
- official installation support for Windows, macOS, and Linux;
- the six commands `start`, `status`, `check`, `hint`, `next`, and `list`;
- a separate learner workspace initialized as a Git repository;
- local, versioned, human-readable progress;
- safe and recoverable disclosure of course-owned materials;
- visible cumulative JUnit tests plus immutable behavioral validation;
- four lessons covering diagnostics, coordinates, field boundaries, and game state;
- short multiple-choice reflection questions in an interactive terminal flow;
- instructions for creating a public GitHub repository and pushing the learner project;
- compatibility metadata that lets a learner continue into later phases after updating the CLI;
- an early-pilot script and observable completion criteria.

Phase A does not include:

- Spring Boot or browser rendering;
- online accounts, telemetry, remote progress, or an online updater;
- automatic Git commits, pushes, remote creation, or GitHub authentication;
- watch mode or background checking;
- a full-screen terminal UI;
- a plugin system, external course marketplace, or authoring UI;
- automatic grading of free-form explanations;
- guaranteed support for shells other than PowerShell/cmd on Windows and Bash/zsh on Unix.

## Architectural Approach

The product is a capability-oriented modular monolith in the existing `app` Gradle module. Package boundaries provide separation without adding premature Gradle modules.

```text
CLI adapter
    |
    v
Application use cases
    |-- StartCourse
    |-- ShowStatus
    |-- CheckLesson
    |-- ShowHint
    |-- AdvanceLesson
    `-- ListLessons
    |
    v
Domain
    |-- Course / Lesson / Prerequisite
    |-- CompletionCriteria
    |-- ReflectionQuestion
    |-- Progress
    `-- LessonTransition
    |
    |---------- owned ports ----------|
    v                                 v
Workspace adapters              Validation adapters
    |-- filesystem                  |-- Gradle process
    |-- progress JSON               |-- visible JUnit results
    |-- Git inspection              `-- embedded behavior checks
    `-- course resources
```

Production packages are organized under `org.fruitandfaults` by capability:

```text
org.fruitandfaults
|-- cli
|-- course
|   |-- domain
|   |-- application
|   `-- infra
|-- lesson
|-- workspace
|-- progress
|-- validation
`-- git
```

Domain code has no dependency on terminal APIs, Spring, filesystem types, process types, Git, JSON, logging, clocks, or serialization. CLI parsing and terminal I/O remain thin adapters. Each application use case owns the narrow ports it needs. Installation logic belongs in typed Gradle task classes rather than production CLI packages.

The implementation begins in one module. A package is extracted into another Gradle module only after a real second consumer or a demonstrated dependency-control problem justifies it.

## Course Bundle and Domain Model

The learning path is data, not command conditionals. The installed CLI contains an immutable Phase A bundle:

```text
app/src/main/resources/course/
|-- course.properties
`-- lessons/
    |-- 01-first-run/
    |-- 02-coordinate-direction/
    |-- 03-field-valid-move/
    `-- 04-game-state/
```

Every lesson directory contains:

```text
lesson.properties
instructions.md
hints/01.md
hints/02.md
hints/03.md
question.properties
assets/
```

`course.properties` explicitly defines the lesson order, course identity, and content version. Directory names do not implicitly define the route. `lesson.properties` supplies the stable lesson ID, title, goals, prerequisites, expected artifacts, completion criteria, and recommended commit message. `question.properties` supplies a stable prompt ID, stable option IDs, the correct option ID, and targeted feedback.

Java loads this data into immutable domain records. Java `Properties` and ordinary resource APIs are sufficient for Phase A; no content parser dependency is added. Markdown is displayed as terminal-friendly text without introducing a rendering engine.

Progress and manifest files use JSON. Because the Java standard library has no JSON processor and the current dependencies do not provide one, the implementation adds Jackson through the version catalog and maps input through serialization DTOs into validated domain values. Unknown fields may be preserved for forward-compatible reading only where the schema explicitly permits them; unknown format versions always fail before domain construction.

## Learner Workspace

`start <path>` creates or safely continues a workspace separate from the CLI source repository:

```text
my-fruit-game/
|-- .git/
|-- .gitignore
|-- .fruit-and-faults/
|   |-- progress.json
|   |-- managed-files.json
|   `-- workspace.properties
|-- docs/
|   `-- publishing-to-github.md
|-- gradle/
|-- gradlew
|-- gradlew.bat
|-- settings.gradle.kts
|-- build.gradle.kts
`-- src/
    |-- main/java/...
    `-- test/java/...
```

The learner owns game source and every test or scaffold after it has been disclosed. The CLI never silently rewrites those files. The CLI owns progress, manifest, transaction, and workspace metadata, but committed versions contain no absolute paths, credentials, tokens, or machine-specific state.

`managed-files.json` uses a versioned schema and records, for each disclosed asset:

- normalized relative path;
- stable source asset ID;
- SHA-256 of the originally disclosed bytes;
- lesson that disclosed it;
- asset policy: immutable course check, learner-editable template, or learner-owned scaffold.

The manifest supports diagnostics, integrity checks, and idempotent recovery. It does not grant permission to restore or overwrite a file the learner changed.

`workspace.properties` contains only the stable course ID and workspace layout version. The workspace root is rediscovered on each invocation.

All target paths are resolved and normalized against the workspace root before access. Operations reject absolute asset paths, `..` escape, and any symlink traversal outside the workspace. A multi-file operation validates every target before its first learner-visible write. Unknown or conflicting files stop the operation and produce a precise path-level diagnostic.

## CLI Contract

The Phase A command surface is:

```text
fruit-and-faults start <workspace>
fruit-and-faults status
fruit-and-faults check [--verbose]
fruit-and-faults hint
fruit-and-faults next [--answer <option-id>] [--yes]
fruit-and-faults list
```

Common options are `--help`, `--version`, `--no-color`, and `--verbose`.

Stable exit codes are:

| Code | Meaning |
| ---: | --- |
| 0 | Command completed successfully |
| 1 | Expected learning work remains incomplete |
| 2 | Invalid arguments or invocation outside a workspace |
| 3 | Workspace conflict or unsafe workspace state |
| 4 | Compilation or test failure |
| 5 | Validation timeout or interruption |
| 10 | Internal course or CLI error |

Human-oriented output goes to stdout. Diagnostics go to stderr. Stack traces are hidden by default and available through `--verbose`. All failure messages use the structure: expected state, observed state, next useful action.

### `start`

`start`:

1. validates the destination and every planned path;
2. shows an exact preview;
3. requires confirmation in interactive mode and `--yes` in non-interactive mode;
4. creates a Git-ready workspace;
5. runs `git init`, but never stages or commits;
6. discloses lesson 1;
7. atomically writes the initial course state;
8. prints the lesson goal and the GitHub publishing instructions.

An absent destination or an existing empty directory is acceptable. An existing non-empty directory is rejected in Phase A. Repeating `start` for an already initialized compatible workspace safely resumes it.

### `status`

`status` reports:

- active lesson and current goal;
- inexpensive completion observations that do not run the full build;
- revealed hint level;
- expected artifacts;
- local Git commit and worktree state;
- presence of `origin` and an upstream branch;
- an available course continuation after a CLI update;
- exactly one recommended next command.

Remote and upstream findings are advisory. No network request is made.

### `check`

`check`:

1. validates required artifacts and visible-test integrity;
2. launches the workspace Gradle wrapper;
3. classifies compiler, test, missing-artifact, timeout, interruption, and internal failures;
4. runs immutable public-behavior checks after visible tests pass;
5. reports actionable feedback without prescribing the implementation.

The process command is an argument list, never a shell-concatenated string. Runtime and captured output are bounded. Timeout or interruption terminates the owned process tree. Java interrupt status is restored before control returns.

### `hint`

`hint` reveals one level at a time:

1. a reasoning question;
2. a concept or code location;
3. an algorithm idea without finished code.

The revealed level is atomically persisted. Repeating `hint` after level 3 repeats that level without disclosing a solution.

### `next`

`next` executes this ordered flow:

```text
validate current lesson
        |
        v
ask the multiple-choice reflection question
        |
        v
require a new local commit and a clean worktree
        |
        v
show non-blocking origin/upstream advice
        |
        v
build and display the next disclosure plan
        |
        v
confirm, apply, and atomically advance progress
```

An incorrect answer changes no state and produces targeted feedback. The exact commit message is recommended but not enforced. GitHub publication never blocks `next`. Repeating `next` after a completed transition does not duplicate files or progress.

The correct answer remains in memory until the Git checks and disclosure succeed. It is written to progress only as part of the final transition commit, so answering the question cannot itself make the worktree dirty before the clean-worktree check.

### `list`

`list` shows the route, lesson titles, and progress states. It does not reveal future tests, instructions, hints, reflection answers, or solution details.

## Reflection Interaction

Reflection uses a small numbered terminal prompt, not a full-screen TUI. The question, stable options, correct option, correct feedback, and per-wrong-option feedback are course data.

In an interactive terminal, `next` reads a numbered answer and offers retry after targeted feedback. In a non-interactive environment it never waits for input; `--answer <option-id>` and `--yes` are required. The stable option ID, not its display position, is persisted only when the whole lesson transition succeeds.

The quiz verifies recognition, not a full explanation. The early pilot therefore includes a human-observed follow-up question. The product does not claim to automatically assess the quality of thought.

## Git and GitHub Learning Contract

The workspace is initialized as a Git repository. The CLI requires a new local commit for the completed lesson and a clean worktree before `next` can disclose the following lesson. Progress records the Git revision that was current when each lesson was opened. For lesson 1, `next` requires that `HEAD` now exists; for later lessons, it requires `HEAD` to differ from the opening revision. It never runs `git add`, `git commit`, `git push`, branch mutation, remote mutation, or GitHub authentication.

`docs/publishing-to-github.md` teaches the primary flow through the GitHub website and ordinary Git commands:

1. create an empty public repository without generated README, license, or `.gitignore`;
2. inspect `git status` and `git diff`;
3. stage selected files and create a meaningful commit;
4. add the HTTPS `origin`;
5. establish `main` and push with upstream tracking.

The instructions explain that account passwords must not be pasted as Git credentials and direct the learner to GitHub's normal credential flow. The CLI never requests or stores a token. Missing `origin`, upstream, network, or push remains advisory so an installed course works offline.

`progress.json`, `managed-files.json`, and `workspace.properties` are committed. This lets a cloned learner repository carry its course state to another computer. Temporary transaction files, Gradle output, IDE files, and local caches are ignored.

## Validation Model

Phase A uses a hybrid model:

- visible cumulative JUnit tests teach Arrange-Act-Assert and belong to the learner after disclosure;
- immutable validators packaged with the CLI check only required artifacts and public observable behavior;
- the CLI detects missing or modified immutable visible tests and describes the mismatch explicitly;
- a learner-editable test template is expected to differ from its disclosed hash and to compile, while the embedded validator independently proves the associated behavior;
- validators do not require a particular internal implementation unless the structure is itself an explicit lesson objective.

This model avoids trusting an accidentally deleted test without turning the course into opaque hidden-test guessing.

## Phase A Lessons

### Lesson 1: First Run and Diagnostics

The starter contains one small intentional Java compilation error in learner-facing code, not in build infrastructure. The learner runs `check`, identifies the compilation category and useful source location, makes the minimal fix, reruns the check, and observes passing tests.

The reflection question distinguishes compilation failure from test failure. The learner then inspects `git status` and `git diff`, creates the first local commit, follows the website-based GitHub instructions, adds `origin`, and pushes when network access is available.

Completion requires successful compilation, a passing visible starter test, and an embedded check of the expected public result. GitHub publication is not a completion criterion.

### Lesson 2: Coordinate and Direction

The learner implements adjacent movement in four directions using this public contract:

```java
public record Coordinate(int x, int y) {
  public Coordinate move(Direction direction) { ... }
}

public enum Direction {
  UP, RIGHT, DOWN, LEFT
}
```

Visible tests demonstrate Arrange-Act-Assert. Completion checks all four directions and verifies that moving returns a new coordinate without mutating the original value. The reflection question checks understanding of immutable record values.

### Lesson 3: Board and Valid Movement

The lesson introduces:

```java
public record Board(int width, int height) {
  public boolean contains(Coordinate coordinate) { ... }
}

public record MoveResult(Coordinate coordinate, MoveStatus status) {}

public enum MoveStatus {
  MOVED, BLOCKED
}

public final class Movement {
  public static MoveResult move(
      Coordinate current, Direction direction, Board board) { ... }
}
```

An invalid movement keeps the original coordinate and returns `BLOCKED`. The learner reads provided boundary cases and completes one clearly marked analogous JUnit case. The CLI verifies that the editable test template differs from its disclosed bytes and compiles, then independently validates the associated public boundary behavior. It does not parse the test body or claim that any textual change proves understanding; the pilot observer checks the learner's explanation.

### Lesson 4: Game State

The lesson introduces:

```java
public record GameState(Coordinate player, int successfulMoves) {
  public GameState move(Direction direction, Board board) { ... }
}
```

A successful movement changes the player coordinate and increments `successfulMoves`. A blocked movement preserves both values. Earlier visible tests remain part of every check. New tests distinguish pure movement calculation from a game-state transition. The reflection question asks why a blocked command must not consume a successful move.

Every lesson supplies a goal, exact rule, before/after example, visible tests, three hints, one reflection question, targeted feedback, and a recommended Conventional Commit message.

## Persistence and Transition Recovery

`progress.json` is versioned and contains at least:

```json
{
  "formatVersion": 1,
  "courseId": "fruit-and-faults",
  "courseContentVersion": 1,
  "activeLessonId": "coordinate-direction",
  "activeLessonOpenedAtRevision": "4a1f0c...",
  "completedLessonIds": ["first-run"],
  "revealedHintLevels": {},
  "reflectionAnswers": {
    "first-run": "compile-before-tests"
  }
}
```

Unknown future versions fail safely and are never rewritten. Invalid JSON or invalid domain state produces a useful diagnostic while preserving the file.

Tool-owned state is written through a temporary file in the same directory, flushed, and atomically moved when supported. A multi-file lesson disclosure uses `.fruit-and-faults/transition.json` as an atomic journal:

1. validate the complete transition and every target;
2. atomically write the exact plan and expected hashes;
3. create each new asset through a same-directory temporary file and move;
4. atomically update the managed-file manifest;
5. atomically update progress as the final commit marker;
6. remove the transaction journal.

On recovery, a created file with the expected hash is accepted as already applied, a missing file can be safely created, and any unexpected or changed file stops recovery. Progress never advances until the full disclosure is valid. Recovery never deletes or overwrites learner content.

Advancing to a lesson intentionally leaves newly disclosed assets and updated tool-owned state as changes for that lesson's eventual commit. Completing the final lesson records the terminal course state after the lesson commit; `status` then explicitly recommends one final metadata commit so a remote clone also observes course completion.

## Cross-Platform CLI Installation

JDK 26 is an explicit prerequisite for the CLI and learner project. Installation validates both `java` and `javac`, prints the detected versions, and stops with platform-specific guidance when the requirement is unmet. The repository's Gradle wrapper and Java 26 toolchain remain authoritative for compilation and tests.

Gradle's Application plugin produces Unix and Windows launchers and the runtime dependency layout. Typed tasks expose:

```text
installCli
addCliToPath
setupCli
uninstallCli
```

`setupCli` runs `installDist`, installs the distribution, updates the user-level command path, and verifies `fruit-and-faults --version`. Installation paths are:

| Platform | Distribution | Command exposure |
| --- | --- | --- |
| Windows | `%LOCALAPPDATA%\Programs\FruitAndFaults` | User-level Windows `PATH` |
| macOS | `~/Library/Application Support/FruitAndFaults` | Symlink in `~/.local/bin`; managed zsh/Bash profile entry when needed |
| Linux | `~/.local/share/fruit-and-faults` | Symlink in `~/.local/bin`; managed Bash/zsh profile entry when needed |

All install, PATH, and uninstall operations are idempotent. An installation marker identifies tool-owned directories. Existing unmarked directories are conflicts. Profile edits are limited to a clearly marked block, preserve unrelated content, and are previewed before mutation. An unknown Unix shell receives a manual PATH instruction rather than a guessed profile edit. `uninstallCli` removes only a valid marked installation, its symlink, and its own PATH entry or profile block.

Installer logic accepts test overrides for home, local application data, install root, and profile files. Automated tests never modify a developer's actual environment.

## Course Evolution

CLI software version and course content version are independent:

- `cliVersion` identifies the executable behavior;
- `courseContentVersion` identifies the bundled route and assets;
- `courseId` binds a workspace to the intended course.

For the PoC development workflow, the learner updates the CLI source repository with `git pull` and reruns `setupCli`. After updating, the existing workspace remains the source of truth. If Phase B has been appended and lesson 4 is complete, ordinary `next` discloses lesson 5.

Compatibility rules are established in Phase A:

- existing lesson and asset IDs are stable;
- later lessons are append-only by default;
- updating the CLI never rewrites disclosed learner files;
- new validators do not retroactively invalidate completed lessons;
- Phase A public Java contracts remain supported by later phases;
- `status` reports an available continuation;
- incompatible CLI or course versions stop before mutation with upgrade guidance;
- persisted schema changes require a tested explicit migration and a backup of the prior state;
- unknown future formats remain untouched.

No online `update` command is added during the PoC. Signed release archives or platform installers can replace the source-based update workflow only after the pilot validates the product.

## Testing Strategy

### Domain Tests

Framework-free tests cover lesson ordering and prerequisites, valid and invalid transitions, repeated completion, hint progression, reflection outcomes, progress invariants, unknown formats, and result classification.

### Workspace Tests

Tests use temporary directories and real filesystem operations for empty and non-empty destinations, repeated start, every target conflict, traversal, absolute paths, symlink escape, Unicode and spaces, partial writes, recovery, changed managed files, invalid progress, and preservation of the last valid state.

### Process Tests

Substitute wrapper fixtures cover success, compiler failure, test failure, bounded output, timeout, interruption, child termination, missing wrapper, and an unknown non-zero result. Default tests use no real sleeps or network.

### Git Tests

Temporary real Git repositories cover initialization, no-commit state, a new lesson commit, dirty-worktree blocking, missing `origin`, missing upstream, and proof that the CLI never changes remotes or pushes.

### CLI Journey Tests

Captured stdin/stdout/stderr scenarios cover fresh start, intentional compilation failure and repair, wrong and correct reflection answers, local commit and advance, all four lessons, stop and resume, repeated commands, file conflicts, non-interactive flags, `--no-color`, stable exit codes, and hidden stack traces.

### Installer Tests

Temporary environment roots cover Windows user PATH, macOS profile blocks, Linux Bash and zsh, unknown shells, unmarked install conflicts, repeated installation and removal, missing or incorrect JDK, and paths containing spaces and Unicode. Platform CI or explicit manual smoke tests execute `setupCli` on Windows, macOS, and Linux; a successful macOS test is not evidence for Windows or Linux behavior.

## Phase A Acceptance Criteria

Phase A is ready for the early pilot when:

- a learner installs the CLI and completes all four lessons from a fresh workspace;
- compilation failures, test failures, missing artifacts, timeouts, and internal failures are distinguishable;
- cumulative visible tests and immutable public-behavior checks both work;
- each lesson requires a meaningful local commit and clean worktree before the next disclosure;
- GitHub publishing is understandable but remains offline-safe and non-blocking;
- stop, resume, repeated commands, interruption, and transition recovery preserve valid progress;
- conflicts, traversal, and symlink escape produce no partial learner-file damage;
- installation smoke tests pass on Windows, macOS, and Linux;
- the early-pilot observer can record lesson duration, hint level, repeated failures, manual interventions, reflection understanding, and resume success;
- `./gradlew spotlessApply` produces only intended formatting;
- `./gradlew check` passes, including tests, Spotless, Checkstyle, Error Prone with NullAway, and JaCoCo line coverage of at least 80%.

## Deliberate Deferrals

The following are deferred until pilot evidence justifies them:

- full-screen TUI and watch mode;
- automatic updates;
- bundled Java runtimes or native installers;
- alternative course bundles and plugins;
- remote analytics or accounts;
- automated GitHub repository creation;
- course-content authoring tools;
- Spring Boot and browser rendering.
