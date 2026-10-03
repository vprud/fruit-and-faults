# Phase A Learning Cycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a safe, cross-platform Java CLI and four cumulative lessons that let a learner complete, validate, commit, publish, stop, resume, and later extend the Phase A game project.

**Architecture:** Keep one Gradle application module and organize production code by course, progress, workspace, validation, Git, and CLI capabilities. Load an immutable declarative course bundle into framework-free domain records, operate on a separate learner workspace through narrow ports, and make every persisted transition recoverable and idempotent.

**Tech Stack:** Java 26, Gradle 9.7.1 Kotlin DSL, JUnit Jupiter 6, Jackson JSON, JSpecify/NullAway, Error Prone, Checkstyle, Spotless, JaCoCo, standard `ProcessBuilder`, Gradle Application distributions.

**Spec:** `docs/superpowers/specs/2026-10-03-phase-a-learning-cycle-design.md`

## Global Constraints

- The Java 26 Gradle toolchain is mandatory; do not use preview or incubating APIs.
- Keep the six-command contract: `start`, `status`, `check`, `hint`, `next`, and `list`.
- Keep CLI parsing and terminal I/O outside lesson, progress, validation, and game rules.
- Treat course content, workspace files, subprocess output, progress, and Git output as untrusted.
- Normalize every workspace path, reject traversal and symlink escape, and never overwrite learner-owned content.
- Persist versioned state atomically and preserve the last valid state after failure.
- Pass process arguments as lists; bound runtime, output, and process ownership; preserve interruption status.
- Require local commits but never stage, commit, push, mutate branches/remotes, authenticate to GitHub, or require network access.
- Keep Windows, macOS, and Linux installation idempotent and test it against redirected user directories.
- Use visible cumulative JUnit tests plus immutable validators that check only public behavior.
- Run the narrowest test first for every change, then `./gradlew spotlessApply`, inspect its diff, and run `./gradlew check` before completion.
- Maintain JaCoCo line coverage at or above 80% without assertion-free coverage tests.
- No commits are authorized by this plan. Stop at each review checkpoint; commit only after a separate explicit user request.

## Review Focus

- A workspace path containing spaces, Cyrillic, and a symlinked ancestor must either remain inside the selected root or fail before any write; Task 4 pins this with real-filesystem tests.
- A process interrupted after one lesson asset is moved but before progress is updated must resume without overwrite or duplicate progress; Task 5 pins this with transition recovery tests.
- A learner who edits an immutable test or changes only whitespace in an editable test template must receive distinct, honest feedback; Tasks 2 and 9 pin asset-policy and validation behavior.
- A correct reflection answer followed by a dirty Git worktree must not persist the answer or advance progress; Task 11 pins the ordering and rollback behavior.
- Reinstalling or uninstalling on a machine with an existing unmarked directory or unrelated PATH/profile entries must leave foreign data unchanged; Task 13 pins platform installer ownership.

---

## Planned File Structure

Production responsibilities are split as follows:

```text
app/src/main/java/org/fruitandfaults/
|-- cli/                 command parsing, terminal rendering, prompts
|-- course/domain/       immutable course and lesson values
|-- course/application/  load/list course use cases and owned ports
|-- course/infra/        classpath course bundle loader
|-- progress/domain/     progress state and transitions
|-- progress/application progress repository port
|-- progress/infra/      JSON DTOs, validation, atomic storage
|-- workspace/domain/    relative asset paths, plans, ownership policies
|-- workspace/application start/disclose/recover use cases and ports
|-- workspace/infra/     safe NIO filesystem implementation
|-- validation/domain/   check outcomes and diagnostics
|-- validation/application check use case and process/validator ports
|-- validation/infra/    Gradle process runner and reflection validators
|-- git/application/     Git repository port and lesson commit policy
`-- git/infra/           bounded read-only Git process adapter
```

Course data lives under `app/src/main/resources/course/`. Installer task types live under `buildSrc/src/main/kotlin/org/fruitandfaults/build/`. Tests mirror production packages under `app/src/test/java/`; fixture course bundles, fake wrapper scripts, and expected CLI transcripts live under `app/src/test/resources/`.

### Task 1: Establish the Application, Dependencies, and CLI Result Contract

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `settings.gradle.kts`
- Delete: `app/src/main/java/org/example/App.java`
- Delete: `app/src/main/java/org/example/package-info.java`
- Delete: `app/src/test/java/org/example/AppTest.java`
- Create: `app/src/main/java/org/fruitandfaults/package-info.java`
- Create: `app/src/main/java/org/fruitandfaults/cli/package-info.java`
- Create: `app/src/main/java/org/fruitandfaults/cli/ExitCode.java`
- Create: `app/src/main/java/org/fruitandfaults/cli/CommandResult.java`
- Create: `app/src/main/java/org/fruitandfaults/cli/FruitAndFaults.java`
- Create: `app/src/test/java/org/fruitandfaults/cli/FruitAndFaultsTest.java`

**Interfaces:**
- Consumes: process arguments, input/output/error streams, and a CLI version supplied from the build.
- Produces: `FruitAndFaults.run(String[] args, InputStream in, PrintStream out, PrintStream err) -> int`, `CommandResult(ExitCode, String stdout, String stderr)`, and stable `ExitCode` numeric values `0, 1, 2, 3, 4, 5, 10`.

- [ ] **Step 1: Add the failing entry-point tests**

  Test `--version`, `--help`, an unknown command, and no command. Assert exact exit codes, stdout/stderr routing, and that no stack trace is printed.

- [ ] **Step 2: Run the focused test and verify failure**

  Run: `./gradlew :app:test --tests org.fruitandfaults.cli.FruitAndFaultsTest`

  Expected: FAIL because the new CLI types do not exist.

- [ ] **Step 3: Replace the generated sample with the minimal CLI shell**

  Set `application.mainClass = "org.fruitandfaults.cli.FruitAndFaults"` and `application.applicationName = "fruit-and-faults"`. Implement only version/help/unknown-command behavior; command handlers remain explicit unsupported placeholders that return internal error until their tasks implement them.

- [ ] **Step 4: Update the version catalog and dependency baseline**

  Remove unused Guava. Add Jackson BOM or aligned `jackson-databind` and `jackson-datatype-jdk8` aliases through the version catalog. Keep JSpecify compile-only and existing quality plugins.

- [ ] **Step 5: Run focused tests and baseline quality checks**

  Run: `./gradlew :app:test --tests org.fruitandfaults.cli.FruitAndFaultsTest`

  Expected: PASS.

  Run: `./gradlew :app:compileJava :app:checkstyleMain`

  Expected: PASS with Java 26, Error Prone, NullAway, and Checkstyle.

- [ ] **Step 6: Review checkpoint**

  Inspect `git diff`. Do not commit without explicit user authorization.

### Task 2: Model and Load the Declarative Course Bundle

**Files:**
- Create: `app/src/main/java/org/fruitandfaults/course/domain/package-info.java`
- Create: `app/src/main/java/org/fruitandfaults/course/domain/CourseId.java`
- Create: `app/src/main/java/org/fruitandfaults/course/domain/LessonId.java`
- Create: `app/src/main/java/org/fruitandfaults/course/domain/AssetId.java`
- Create: `app/src/main/java/org/fruitandfaults/course/domain/Course.java`
- Create: `app/src/main/java/org/fruitandfaults/course/domain/Lesson.java`
- Create: `app/src/main/java/org/fruitandfaults/course/domain/LessonAsset.java`
- Create: `app/src/main/java/org/fruitandfaults/course/domain/AssetPolicy.java`
- Create: `app/src/main/java/org/fruitandfaults/course/domain/ReflectionQuestion.java`
- Create: `app/src/main/java/org/fruitandfaults/course/domain/ReflectionOption.java`
- Create: `app/src/main/java/org/fruitandfaults/course/domain/CompletionCriterion.java`
- Create: `app/src/main/java/org/fruitandfaults/course/application/package-info.java`
- Create: `app/src/main/java/org/fruitandfaults/course/application/CourseCatalog.java`
- Create: `app/src/main/java/org/fruitandfaults/course/infra/package-info.java`
- Create: `app/src/main/java/org/fruitandfaults/course/infra/ClasspathCourseCatalog.java`
- Create: `app/src/main/resources/course/course.properties`
- Create: `app/src/main/resources/course/lessons/01-first-run/lesson.properties`
- Create: `app/src/main/resources/course/lessons/01-first-run/instructions.md`
- Create: `app/src/main/resources/course/lessons/01-first-run/hints/01.md`
- Create: `app/src/main/resources/course/lessons/01-first-run/hints/02.md`
- Create: `app/src/main/resources/course/lessons/01-first-run/hints/03.md`
- Create: `app/src/main/resources/course/lessons/01-first-run/question.properties`
- Create corresponding metadata, instructions, three hints, and question resources for `02-coordinate-direction`, `03-field-valid-move`, and `04-game-state`
- Create: `app/src/test/java/org/fruitandfaults/course/domain/CourseTest.java`
- Create: `app/src/test/java/org/fruitandfaults/course/infra/ClasspathCourseCatalogTest.java`
- Create: `app/src/test/resources/course-invalid/**`

**Interfaces:**
- Consumes: classpath root string and Java `Properties`/UTF-8 Markdown resources.
- Produces: `CourseCatalog.load() -> Course`; `Course` exposes ordered lessons, stable IDs, content version, prerequisites, assets, criteria, hints, and reflection data without filesystem or serialization types.

- [ ] **Step 1: Write domain invariant tests**

  Cover duplicate lesson IDs, missing prerequisite, cyclic or out-of-order prerequisite, duplicate option IDs, absent correct option, exactly three hints, invalid asset path, and missing lesson in the declared order.

- [ ] **Step 2: Run the domain test and verify failure**

  Run: `./gradlew :app:test --tests org.fruitandfaults.course.domain.CourseTest`

  Expected: FAIL because the domain records are absent.

- [ ] **Step 3: Implement immutable domain values and validation factories**

  Use records where invariants remain explicit. Give every public type and method required Checkstyle Javadoc. `LessonAsset` includes `AssetId`, normalized relative path text, resource path, SHA-256, and `AssetPolicy` values `IMMUTABLE_CHECK`, `EDITABLE_TEMPLATE`, `LEARNER_SCAFFOLD`.

- [ ] **Step 4: Write loader tests against valid and invalid fixture bundles**

  Assert the exact four-lesson order, content version `1`, stable IDs, questions, hints, asset policies, and actionable failures for missing resources or invalid values.

- [ ] **Step 5: Run the loader test and verify failure**

  Run: `./gradlew :app:test --tests org.fruitandfaults.course.infra.ClasspathCourseCatalogTest`

  Expected: FAIL because no loader or bundle exists.

- [ ] **Step 6: Implement `ClasspathCourseCatalog` and the lesson metadata bundle**

  Parse only explicit property keys, load UTF-8 text, calculate asset hashes from raw bytes, and construct the domain only after all input is validated. Do not discover order by directory sorting.

- [ ] **Step 7: Run focused course tests**

  Run: `./gradlew :app:test --tests 'org.fruitandfaults.course.*'`

  Expected: PASS.

- [ ] **Step 8: Review checkpoint**

  Inspect the lesson copy for accidental solution disclosure and inspect `git diff`. Do not commit without explicit user authorization.

### Task 3: Define Versioned Progress and Strict JSON Mapping

**Files:**
- Create: `app/src/main/java/org/fruitandfaults/progress/domain/package-info.java`
- Create: `app/src/main/java/org/fruitandfaults/progress/domain/CourseProgress.java`
- Create: `app/src/main/java/org/fruitandfaults/progress/domain/ProgressFormatVersion.java`
- Create: `app/src/main/java/org/fruitandfaults/progress/domain/LessonProgress.java`
- Create: `app/src/main/java/org/fruitandfaults/progress/domain/ProgressTransition.java`
- Create: `app/src/main/java/org/fruitandfaults/progress/application/package-info.java`
- Create: `app/src/main/java/org/fruitandfaults/progress/application/ProgressRepository.java`
- Create: `app/src/main/java/org/fruitandfaults/progress/infra/package-info.java`
- Create: `app/src/main/java/org/fruitandfaults/progress/infra/ProgressDocument.java`
- Create: `app/src/main/java/org/fruitandfaults/progress/infra/JacksonProgressCodec.java`
- Create: `app/src/main/java/org/fruitandfaults/progress/infra/AtomicProgressRepository.java`
- Create: `app/src/test/java/org/fruitandfaults/progress/domain/CourseProgressTest.java`
- Create: `app/src/test/java/org/fruitandfaults/progress/infra/JacksonProgressCodecTest.java`
- Create: `app/src/test/java/org/fruitandfaults/progress/infra/AtomicProgressRepositoryTest.java`
- Create: `app/src/test/resources/progress/*.json`

**Interfaces:**
- Consumes: validated `Course`, current `CourseProgress`, lesson/result facts, and a repository-owned state file.
- Produces: `CourseProgress.opening(Course, @Nullable String revision)`, `CourseProgress.revealHint(LessonId)`, `CourseProgress.advance(LessonId, String optionId, @Nullable String nextRevision)`, and `ProgressRepository.load(Path workspaceRoot) / save(Path workspaceRoot, CourseProgress)`.

- [ ] **Step 1: Write progress transition tests**

  Assert initial lesson, one-level hint increments, maximum hint level, prerequisite enforcement, idempotent repeated transitions, stable option IDs, active-lesson opening revision, final completion, and rejection of a lesson absent from the course.

- [ ] **Step 2: Run and verify domain-test failure**

  Run: `./gradlew :app:test --tests org.fruitandfaults.progress.domain.CourseProgressTest`

  Expected: FAIL because progress types do not exist.

- [ ] **Step 3: Implement framework-free progress values and transitions**

  Avoid booleans for transition outcomes; use a sealed result that distinguishes advanced, already applied, invalid prerequisite, and course complete.

- [ ] **Step 4: Write JSON boundary tests**

  Cover exact format version `1`, valid round trip, malformed JSON, missing fields, duplicate lesson IDs, unknown lesson IDs, invalid hint levels, unknown current version, and future format version. Assert that DTOs are validated before creating `CourseProgress`.

- [ ] **Step 5: Implement Jackson DTO mapping**

  Keep Jackson annotations and `ObjectMapper` in `progress.infra`. Do not expose Jackson types from domain or application packages.

- [ ] **Step 6: Write atomic repository tests**

  Use a temporary directory to prove same-directory temporary write, preservation after injected write/move failure, cleanup of owned temporary files, and no mutation of a future-version file.

- [ ] **Step 7: Implement atomic progress storage**

  Flush file bytes before `ATOMIC_MOVE`; when atomic move is unsupported, use a documented replace move that still leaves the old file until the new file is fully written.

- [ ] **Step 8: Run focused progress tests**

  Run: `./gradlew :app:test --tests 'org.fruitandfaults.progress.*'`

  Expected: PASS.

- [ ] **Step 9: Review checkpoint**

  Inspect serialized fixtures for machine-local data and inspect `git diff`. Do not commit without explicit user authorization.

### Task 4: Build Workspace Path Safety and Ownership Manifest

**Files:**
- Create: `app/src/main/java/org/fruitandfaults/workspace/domain/package-info.java`
- Create: `app/src/main/java/org/fruitandfaults/workspace/domain/WorkspacePath.java`
- Create: `app/src/main/java/org/fruitandfaults/workspace/domain/ManagedFile.java`
- Create: `app/src/main/java/org/fruitandfaults/workspace/domain/ManagedFiles.java`
- Create: `app/src/main/java/org/fruitandfaults/workspace/domain/DisclosurePlan.java`
- Create: `app/src/main/java/org/fruitandfaults/workspace/domain/DisclosureConflict.java`
- Create: `app/src/main/java/org/fruitandfaults/workspace/application/package-info.java`
- Create: `app/src/main/java/org/fruitandfaults/workspace/application/WorkspaceFiles.java`
- Create: `app/src/main/java/org/fruitandfaults/workspace/application/ManagedFilesRepository.java`
- Create: `app/src/main/java/org/fruitandfaults/workspace/infra/package-info.java`
- Create: `app/src/main/java/org/fruitandfaults/workspace/infra/SafeWorkspaceFiles.java`
- Create: `app/src/main/java/org/fruitandfaults/workspace/infra/JacksonManagedFilesRepository.java`
- Create: `app/src/test/java/org/fruitandfaults/workspace/domain/DisclosurePlanTest.java`
- Create: `app/src/test/java/org/fruitandfaults/workspace/infra/SafeWorkspaceFilesTest.java`
- Create: `app/src/test/java/org/fruitandfaults/workspace/infra/JacksonManagedFilesRepositoryTest.java`

**Interfaces:**
- Consumes: selected root, course assets, raw asset bytes, and versioned manifest JSON.
- Produces: `WorkspacePath.parse(String)`, `WorkspaceFiles.inspect(Path root, WorkspacePath)`, `WorkspaceFiles.writeNewAtomically(...)`, and a `DisclosurePlan` that is either applicable or contains exact conflicts before mutation.

- [ ] **Step 1: Write path and plan tests**

  Cover empty path, `.`, `..`, absolute Unix and Windows paths, duplicate normalized targets, existing target, matching already-applied target, case collision where the filesystem exposes one, and distinct asset policies.

- [ ] **Step 2: Run and verify domain-test failure**

  Run: `./gradlew :app:test --tests org.fruitandfaults.workspace.domain.DisclosurePlanTest`

  Expected: FAIL because workspace values do not exist.

- [ ] **Step 3: Implement path and plan domain types**

  Store only slash-separated relative logical paths in domain values. Return explicit conflict alternatives rather than nullable values.

- [ ] **Step 4: Write real-filesystem safety tests**

  Use temporary roots to cover spaces, Cyrillic, traversal, symlinked file, symlinked parent, symlink escape, existing learner file, same-root symlink behavior, and injected atomic-move failure. Assert no target is written when preflight reports any conflict.

- [ ] **Step 5: Implement `SafeWorkspaceFiles`**

  Resolve targets from the verified real root, inspect every existing path segment without following an external symlink, create only verified directories, and write through same-directory temporary files.

- [ ] **Step 6: Write and implement manifest JSON tests**

  Cover format version, normalized paths, exact asset policies, duplicate path rejection, malformed/future versions, and atomic-write preservation using the same storage discipline as progress.

- [ ] **Step 7: Run focused workspace tests**

  Run: `./gradlew :app:test --tests 'org.fruitandfaults.workspace.*'`

  Expected: PASS.

- [ ] **Step 8: Review checkpoint**

  Inspect all filesystem mutations and `git diff`. Do not commit without explicit user authorization.

### Task 5: Add Recoverable Multi-File Disclosure Transactions

**Files:**
- Create: `app/src/main/java/org/fruitandfaults/workspace/domain/TransitionJournal.java`
- Create: `app/src/main/java/org/fruitandfaults/workspace/domain/TransitionStatus.java`
- Create: `app/src/main/java/org/fruitandfaults/workspace/application/TransitionJournalRepository.java`
- Create: `app/src/main/java/org/fruitandfaults/workspace/application/DiscloseLesson.java`
- Create: `app/src/main/java/org/fruitandfaults/workspace/application/DisclosureResult.java`
- Create: `app/src/main/java/org/fruitandfaults/workspace/infra/JacksonTransitionJournalRepository.java`
- Create: `app/src/test/java/org/fruitandfaults/workspace/application/DiscloseLessonTest.java`
- Create: `app/src/test/java/org/fruitandfaults/workspace/infra/DisclosureRecoveryTest.java`

**Interfaces:**
- Consumes: `Lesson`, current `ManagedFiles`, current `CourseProgress`, and workspace/progress/journal repositories.
- Produces: `DiscloseLesson.plan(...) -> DisclosurePlan`, `DiscloseLesson.apply(...) -> DisclosureResult`, and `DiscloseLesson.recover(...) -> DisclosureResult` with outcomes applied, recovered, conflict, or already applied.

- [ ] **Step 1: Write application-level transaction ordering tests**

  Use fakes to assert journal first, assets next, manifest next, progress last, journal deletion last. Assert no call after any injected failure and unchanged progress until all assets and manifest succeed.

- [ ] **Step 2: Run and verify failure**

  Run: `./gradlew :app:test --tests org.fruitandfaults.workspace.application.DiscloseLessonTest`

  Expected: FAIL because the disclosure use case is absent.

- [ ] **Step 3: Implement disclosure orchestration**

  Keep all IO behind ports. The use case must be idempotent when asset hashes and target lesson match the journal.

- [ ] **Step 4: Write crash-recovery filesystem tests**

  Interrupt after journal write, after the first asset, after all assets, after manifest, and after progress. Assert recovery completes only matching plans, rejects a learner edit, produces no duplicate progress, and preserves the journal on unresolved conflict.

- [ ] **Step 5: Implement JSON journal storage and recovery**

  Include format version, from/to lesson IDs, ordered assets, expected hashes, expected manifest version, and intended progress version. Never put raw learner content in the journal.

- [ ] **Step 6: Run focused recovery tests**

  Run: `./gradlew :app:test --tests 'org.fruitandfaults.workspace.*Disclosure*'`

  Expected: PASS.

- [ ] **Step 7: Review checkpoint**

  Inspect failure paths and `git diff`. Do not commit without explicit user authorization.

### Task 6: Author the Learner Starter and Four Cumulative Lesson Assets

**Files:**
- Create under `app/src/main/resources/course/lessons/01-first-run/assets/`: learner `settings.gradle.kts`, `build.gradle.kts`, wrapper metadata/scripts, `.gitignore`, `docs/publishing-to-github.md`, starter source with one intentional compile error, and the immutable starter test
- Create under `app/src/main/resources/course/lessons/02-coordinate-direction/assets/`: `Direction.java`, editable `Coordinate.java`, and immutable `CoordinateTest.java`
- Create under `app/src/main/resources/course/lessons/03-field-valid-move/assets/`: `Board.java`, `MoveStatus.java`, `MoveResult.java`, `Movement.java`, immutable boundary tests, and one editable analogous-test template
- Create under `app/src/main/resources/course/lessons/04-game-state/assets/`: `GameState.java` and immutable cumulative `GameStateTest.java`
- Modify: all four `lesson.properties` files with exact asset declarations, policies, hashes, expected artifacts, criteria, and recommended commit messages
- Create: `app/src/test/java/org/fruitandfaults/course/infra/LearnerBundleTest.java`
- Create: `app/src/test/java/org/fruitandfaults/course/infra/LearnerJourneyFixture.java`

**Interfaces:**
- Consumes: the bundle schema from Task 2.
- Produces: one self-contained Java 26 learner project whose stable public package is `org.fruitandfaults.game` and whose disclosed tests remain cumulative.

- [ ] **Step 1: Write bundle contract tests**

  Assert every declared resource exists, hashes match, Gradle wrapper files are complete, immutable/editable policies are correct, future assets do not appear in earlier lessons, and no solution source is bundled.

- [ ] **Step 2: Run and verify failure**

  Run: `./gradlew :app:test --tests org.fruitandfaults.course.infra.LearnerBundleTest`

  Expected: FAIL because the assets are absent.

- [ ] **Step 3: Add lesson 1 assets and prove the intended diagnostic**

  Materialize the lesson 1 fixture in a temporary directory and run its wrapper. Assert compilation fails at the documented learner-facing line, not in Gradle infrastructure. Apply the documented minimal fix in the fixture and assert its starter test passes.

- [ ] **Step 4: Add lesson 2 assets and fixture solution only in test resources**

  Keep the learner scaffold incomplete. Store any passing fixture implementation under `app/src/test/resources/journeys/`, never inside the production course bundle. Assert all four direction cases and immutability.

- [ ] **Step 5: Add lesson 3 assets and editable test template**

  Mark only the intended template `EDITABLE_TEMPLATE`. Fixture tests cover all four edges, valid moves, blocked moves, and invalid board dimensions if the contract rejects them.

- [ ] **Step 6: Add lesson 4 assets and cumulative tests**

  Fixture tests cover successful count increments, blocked count preservation, original state immutability, and compatibility with all earlier tests.

- [ ] **Step 7: Run bundle and fixture journey tests**

  Run: `./gradlew :app:test --tests 'org.fruitandfaults.course.infra.Learner*'`

  Expected: PASS; the intentional lesson 1 failure is asserted as expected test behavior.

- [ ] **Step 8: Review checkpoint**

  Manually inspect every learner-facing instruction, hint, quiz, and asset for accidental answers. Do not commit without explicit user authorization.

### Task 7: Implement Workspace Creation and `start`

**Files:**
- Create: `app/src/main/java/org/fruitandfaults/workspace/domain/WorkspaceMetadata.java`
- Create: `app/src/main/java/org/fruitandfaults/workspace/application/StartCourse.java`
- Create: `app/src/main/java/org/fruitandfaults/workspace/application/StartRequest.java`
- Create: `app/src/main/java/org/fruitandfaults/workspace/application/StartResult.java`
- Create: `app/src/main/java/org/fruitandfaults/workspace/application/WorkspaceLocator.java`
- Create: `app/src/main/java/org/fruitandfaults/workspace/infra/WalkingWorkspaceLocator.java`
- Create: `app/src/main/java/org/fruitandfaults/git/application/package-info.java`
- Create: `app/src/main/java/org/fruitandfaults/git/application/GitRepository.java`
- Create: `app/src/main/java/org/fruitandfaults/git/infra/package-info.java`
- Create: `app/src/main/java/org/fruitandfaults/git/infra/ProcessGitRepository.java`
- Create: `app/src/test/java/org/fruitandfaults/workspace/application/StartCourseTest.java`
- Create: `app/src/test/java/org/fruitandfaults/workspace/infra/WalkingWorkspaceLocatorTest.java`
- Create: `app/src/test/java/org/fruitandfaults/git/infra/ProcessGitRepositoryTest.java`

**Interfaces:**
- Consumes: `StartRequest(Path target, boolean confirmed)`, lesson 1, safe workspace/disclosure ports, and `GitRepository.initialize(Path)`.
- Produces: preview-required, created, resumed, conflict, or internal-error `StartResult`; later commands use `WorkspaceLocator.locate(Path current) -> WorkspaceRoot`.

- [ ] **Step 1: Write `StartCourse` behavior tests**

  Cover missing destination, empty directory, non-empty directory, confirmation required, repeated compatible start, incompatible course/layout, Git init failure, and no partial state after disclosure failure.

- [ ] **Step 2: Run and verify failure**

  Run: `./gradlew :app:test --tests org.fruitandfaults.workspace.application.StartCourseTest`

  Expected: FAIL because the start use case is absent.

- [ ] **Step 3: Implement start orchestration**

  Preflight and preview before mutation. Create lesson 1 through the transaction machinery, then initialize Git at the selected root without staging or committing. If Git initialization fails, report the exact state and leave recoverable course files; do not broadly delete the directory.

- [ ] **Step 4: Write workspace discovery tests**

  Cover root invocation, nested invocation, no workspace, two nested markers, symlinked current directory, incompatible metadata, and paths with spaces/Cyrillic.

- [ ] **Step 5: Implement upward workspace discovery**

  Stop at filesystem root. Resolve the selected marker safely and reject ambiguity rather than choosing silently.

- [ ] **Step 6: Write real Git adapter tests**

  Use temporary repositories. Assert only `git init` mutates, arguments are separate, bounded output is used, and error diagnostics do not contain environment dumps.

- [ ] **Step 7: Run focused start tests**

  Run: `./gradlew :app:test --tests 'org.fruitandfaults.workspace.*Start*' --tests 'org.fruitandfaults.git.infra.ProcessGitRepositoryTest'`

  Expected: PASS.

- [ ] **Step 8: Review checkpoint**

  Inspect created workspace fixtures and `git diff`. Do not commit without explicit user authorization.

### Task 8: Add Bounded Process Execution and Gradle Result Classification

**Files:**
- Create: `app/src/main/java/org/fruitandfaults/validation/domain/package-info.java`
- Create: `app/src/main/java/org/fruitandfaults/validation/domain/CheckOutcome.java`
- Create: `app/src/main/java/org/fruitandfaults/validation/domain/Diagnostic.java`
- Create: `app/src/main/java/org/fruitandfaults/validation/domain/FailureCategory.java`
- Create: `app/src/main/java/org/fruitandfaults/validation/application/package-info.java`
- Create: `app/src/main/java/org/fruitandfaults/validation/application/ProcessRunner.java`
- Create: `app/src/main/java/org/fruitandfaults/validation/application/ProcessRequest.java`
- Create: `app/src/main/java/org/fruitandfaults/validation/application/ProcessResult.java`
- Create: `app/src/main/java/org/fruitandfaults/validation/infra/package-info.java`
- Create: `app/src/main/java/org/fruitandfaults/validation/infra/BoundedProcessRunner.java`
- Create: `app/src/main/java/org/fruitandfaults/validation/infra/GradleCheckClassifier.java`
- Create: `app/src/test/java/org/fruitandfaults/validation/infra/BoundedProcessRunnerTest.java`
- Create: `app/src/test/java/org/fruitandfaults/validation/infra/GradleCheckClassifierTest.java`
- Create: `app/src/test/resources/process-fixtures/**`

**Interfaces:**
- Consumes: `ProcessRequest(List<String> arguments, Path workingDirectory, Duration timeout, int maxCapturedBytes)`.
- Produces: a sealed `ProcessResult` for exited, timed out, or interrupted; `GradleCheckClassifier.classify(ProcessResult) -> CheckOutcome`.

- [ ] **Step 1: Write process-runner tests**

  Cover success, stderr, non-zero exit, output truncation with retained useful tail, timeout with child termination, interruption with restored interrupt flag, and paths/arguments containing spaces and Unicode.

- [ ] **Step 2: Run and verify failure**

  Run: `./gradlew :app:test --tests org.fruitandfaults.validation.infra.BoundedProcessRunnerTest`

  Expected: FAIL because the runner is absent.

- [ ] **Step 3: Implement bounded execution**

  Use `ProcessBuilder(List<String>)`, bounded stream collectors, a bounded executor owned by the runner, a deterministic shutdown path, `ProcessHandle.descendants()` for cleanup where supported, and no shell.

- [ ] **Step 4: Write classifier table tests**

  Fixture outputs cover Java compilation error, JUnit assertion failure, Gradle configuration/internal failure, missing wrapper, timeout, interruption, and unknown non-zero exit. Assert expected/observed/next-action diagnostics.

- [ ] **Step 5: Implement conservative Gradle classification**

  Prefer structured markers and exit state over fragile full-log matching. Unrecognized infrastructure failure is `INTERNAL_ERROR`, never blamed on the learner.

- [ ] **Step 6: Run focused validation infrastructure tests**

  Run: `./gradlew :app:test --tests 'org.fruitandfaults.validation.infra.*'`

  Expected: PASS.

- [ ] **Step 7: Review checkpoint**

  Inspect executor shutdown and interrupt handling. Do not commit without explicit user authorization.

### Task 9: Implement Visible-Test Integrity, Embedded Validators, and `check`

**Files:**
- Create: `app/src/main/java/org/fruitandfaults/validation/application/ArtifactInspector.java`
- Create: `app/src/main/java/org/fruitandfaults/validation/application/BehaviorValidator.java`
- Create: `app/src/main/java/org/fruitandfaults/validation/application/CheckLesson.java`
- Create: `app/src/main/java/org/fruitandfaults/validation/application/CheckRequest.java`
- Create: `app/src/main/java/org/fruitandfaults/validation/infra/ManifestArtifactInspector.java`
- Create: `app/src/main/java/org/fruitandfaults/validation/infra/CompiledGameLoader.java`
- Create: `app/src/main/java/org/fruitandfaults/validation/infra/FirstRunValidator.java`
- Create: `app/src/main/java/org/fruitandfaults/validation/infra/CoordinateDirectionValidator.java`
- Create: `app/src/main/java/org/fruitandfaults/validation/infra/FieldMovementValidator.java`
- Create: `app/src/main/java/org/fruitandfaults/validation/infra/GameStateValidator.java`
- Create: `app/src/test/java/org/fruitandfaults/validation/application/CheckLessonTest.java`
- Create: `app/src/test/java/org/fruitandfaults/validation/infra/ManifestArtifactInspectorTest.java`
- Create: `app/src/test/java/org/fruitandfaults/validation/infra/BehaviorValidatorsTest.java`

**Interfaces:**
- Consumes: active `Lesson`, workspace root, manifest, process runner, artifact inspector, and a validator selected by stable criterion ID.
- Produces: `CheckLesson.execute(CheckRequest) -> CheckOutcome`; validators load only `build/classes/java/main` in a closeable isolated classloader and return typed diagnostics.

- [ ] **Step 1: Write artifact-policy tests**

  Assert immutable checks require exact disclosed hash, editable templates require a different hash and later compilation, learner scaffolds may change freely, missing files are distinct from modified files, and whitespace-only editable changes are reported as changed but never praised as meaningful.

- [ ] **Step 2: Run and verify failure**

  Run: `./gradlew :app:test --tests org.fruitandfaults.validation.infra.ManifestArtifactInspectorTest`

  Expected: FAIL because no inspector exists.

- [ ] **Step 3: Implement manifest-backed artifact inspection**

  Reuse safe workspace reads and hashes. Do not follow external symlinks or read unrelated source trees.

- [ ] **Step 4: Write behavior-validator tests**

  Compile journey fixtures, then assert exact public behavior for lesson 1, all four coordinate directions and immutability, field edges and move status, and successful/blocked game-state transitions. Include missing class, wrong signature, constructor exception, and static initializer failure as learner-facing public-contract diagnostics rather than CLI crashes.

- [ ] **Step 5: Implement isolated compiled-class validators**

  Use reflection only in the infra package. Close classloaders, translate reflection failures into typed observations, and avoid inspecting private members or implementation structure.

- [ ] **Step 6: Write `CheckLesson` orchestration tests**

  Assert artifact checks precede Gradle, Gradle precedes embedded validation, prior lesson tests remain selected, timeout/interruption propagates, and failed checks never update progress.

- [ ] **Step 7: Implement `CheckLesson`**

  Select `gradlew.bat` on Windows and `./gradlew` on Unix, run the learner project's `test` task, then run every completion criterion opened through the active lesson.

- [ ] **Step 8: Run focused check tests**

  Run: `./gradlew :app:test --tests 'org.fruitandfaults.validation.*'`

  Expected: PASS.

- [ ] **Step 9: Review checkpoint**

  Inspect reflection boundaries and learner-facing messages. Do not commit without explicit user authorization.

### Task 10: Implement Read-Only Git Status, `status`, `list`, and `hint`

**Files:**
- Create: `app/src/main/java/org/fruitandfaults/git/application/GitStatus.java`
- Create: `app/src/main/java/org/fruitandfaults/git/application/GitLessonGate.java`
- Modify: `app/src/main/java/org/fruitandfaults/git/infra/ProcessGitRepository.java`
- Create: `app/src/main/java/org/fruitandfaults/course/application/ShowStatus.java`
- Create: `app/src/main/java/org/fruitandfaults/course/application/CourseStatus.java`
- Create: `app/src/main/java/org/fruitandfaults/course/application/ListLessons.java`
- Create: `app/src/main/java/org/fruitandfaults/course/application/LessonSummary.java`
- Create: `app/src/main/java/org/fruitandfaults/course/application/ShowHint.java`
- Create: `app/src/test/java/org/fruitandfaults/git/application/GitLessonGateTest.java`
- Create: `app/src/test/java/org/fruitandfaults/git/infra/ProcessGitStatusTest.java`
- Create: `app/src/test/java/org/fruitandfaults/course/application/ShowStatusTest.java`
- Create: `app/src/test/java/org/fruitandfaults/course/application/ListLessonsTest.java`
- Create: `app/src/test/java/org/fruitandfaults/course/application/ShowHintTest.java`

**Interfaces:**
- Consumes: workspace progress/course/manifest and read-only Git facts.
- Produces: `GitRepository.status(Path) -> GitStatus`, `GitLessonGate.evaluate(GitStatus, @Nullable String openedAtRevision)`, `ShowStatus.execute(...) -> CourseStatus`, ordered `LessonSummary` values, and `ShowHint.execute(...) -> HintResult`.

- [ ] **Step 1: Write Git status and lesson-gate tests**

  Cover no commits for lesson 1, first commit, unchanged HEAD for later lesson, changed HEAD, dirty tracked/untracked files, no origin, origin without upstream, and origin/upstream present. Assert only dirty state or missing required local commit blocks.

- [ ] **Step 2: Implement read-only Git inspection**

  Use explicit `git rev-parse`, `git status --porcelain=v1`, `git remote get-url origin`, and upstream inspection through bounded process calls. Never invoke network operations.

- [ ] **Step 3: Write `status` and `list` tests**

  Assert current goal, cheap artifact observations, hint level, Git advice, available content update, one next command, route statuses, and no future lesson details.

- [ ] **Step 4: Implement status/list use cases**

  Do not run Gradle from `status`. Treat missing remote/upstream as advice. Treat malformed state as an explicit internal/workspace diagnostic.

- [ ] **Step 5: Write hint tests**

  Cover levels 1/2/3, repeated level 3, atomic persistence failure, and absence of future hints or solution text.

- [ ] **Step 6: Implement `ShowHint`**

  Advance and persist only after the selected hint text is successfully loaded; reuse progress atomic storage.

- [ ] **Step 7: Run focused tests**

  Run: `./gradlew :app:test --tests 'org.fruitandfaults.git.*' --tests 'org.fruitandfaults.course.application.*'`

  Expected: PASS.

- [ ] **Step 8: Review checkpoint**

  Inspect that Git commands are read-only except the Task 7 initializer. Do not commit without explicit user authorization.

### Task 11: Implement Reflection and the Transactional `next` Flow

**Files:**
- Create: `app/src/main/java/org/fruitandfaults/lesson/package-info.java`
- Create: `app/src/main/java/org/fruitandfaults/lesson/ReflectionAnswer.java`
- Create: `app/src/main/java/org/fruitandfaults/lesson/ReflectionResult.java`
- Create: `app/src/main/java/org/fruitandfaults/lesson/EvaluateReflection.java`
- Create: `app/src/main/java/org/fruitandfaults/course/application/AdvanceLesson.java`
- Create: `app/src/main/java/org/fruitandfaults/course/application/AdvanceRequest.java`
- Create: `app/src/main/java/org/fruitandfaults/course/application/AdvanceResult.java`
- Create: `app/src/test/java/org/fruitandfaults/lesson/EvaluateReflectionTest.java`
- Create: `app/src/test/java/org/fruitandfaults/course/application/AdvanceLessonTest.java`
- Create: `app/src/test/java/org/fruitandfaults/course/application/AdvanceLessonRecoveryTest.java`

**Interfaces:**
- Consumes: current course/progress, stable option ID, `CheckLesson`, `GitLessonGate`, disclosure service, and confirmation flag.
- Produces: `EvaluateReflection.evaluate(ReflectionQuestion, ReflectionAnswer) -> ReflectionResult`; `AdvanceLesson.execute(AdvanceRequest) -> AdvanceResult` with needs-answer, incorrect, check-failed, Git-blocked, preview-required, advanced, recovered, course-complete, or conflict outcomes.

- [ ] **Step 1: Write reflection tests**

  Cover every option, unknown stable ID, targeted wrong feedback, correct feedback, and display-order independence.

- [ ] **Step 2: Implement pure reflection evaluation**

  Do not persist or perform IO. Return typed outcomes with feedback.

- [ ] **Step 3: Write `AdvanceLesson` ordering tests**

  Assert: check current lesson; evaluate answer; require new commit; require clean worktree; build preview; require confirmation; disclose/recover; persist answer only with successful transition. Specifically test correct answer plus dirty worktree leaves progress byte-for-byte unchanged.

- [ ] **Step 4: Run and verify application-test failure**

  Run: `./gradlew :app:test --tests org.fruitandfaults.course.application.AdvanceLessonTest`

  Expected: FAIL because advance orchestration is absent.

- [ ] **Step 5: Implement transactional advance**

  Keep the correct option ID in memory until disclosure commits progress. For lesson 1 require `HEAD`; for later lessons require `HEAD != activeLessonOpenedAtRevision`. Set the next lesson opening revision to the current `HEAD` before creating its assets.

- [ ] **Step 6: Add final-lesson behavior**

  Final completion uses the same check/reflection/Git gate, writes the terminal progress state atomically, and returns advice to make one final metadata commit so a remote clone observes completion.

- [ ] **Step 7: Write recovery tests**

  Cover repeated `next`, journal from each crash point, changed partially disclosed file, answer mismatch with journal, and course content updated with an appended lesson.

- [ ] **Step 8: Run focused advance tests**

  Run: `./gradlew :app:test --tests 'org.fruitandfaults.course.application.AdvanceLesson*' --tests 'org.fruitandfaults.lesson.*'`

  Expected: PASS.

- [ ] **Step 9: Review checkpoint**

  Inspect ordering and all persistence calls. Do not commit without explicit user authorization.

### Task 12: Wire All CLI Commands and Interactive/Non-Interactive UX

**Files:**
- Create: `app/src/main/java/org/fruitandfaults/cli/Arguments.java`
- Create: `app/src/main/java/org/fruitandfaults/cli/CommandParser.java`
- Create: `app/src/main/java/org/fruitandfaults/cli/Terminal.java`
- Create: `app/src/main/java/org/fruitandfaults/cli/ConsoleTerminal.java`
- Create: `app/src/main/java/org/fruitandfaults/cli/TextRenderer.java`
- Create: `app/src/main/java/org/fruitandfaults/cli/ApplicationFactory.java`
- Modify: `app/src/main/java/org/fruitandfaults/cli/FruitAndFaults.java`
- Create: `app/src/test/java/org/fruitandfaults/cli/CommandParserTest.java`
- Create: `app/src/test/java/org/fruitandfaults/cli/TextRendererTest.java`
- Create: `app/src/test/java/org/fruitandfaults/cli/CommandJourneyTest.java`
- Create: `app/src/test/resources/transcripts/**`

**Interfaces:**
- Consumes: CLI arguments, stdin/TTY capability, use-case results, `--verbose`, `--no-color`, `--answer`, and `--yes`.
- Produces: exact stdout/stderr messages and stable numeric exit codes; `CommandParser.parse(String[]) -> Arguments`; `Terminal` exposes prompt/confirm/interactive capability without leaking console types into use cases.

- [ ] **Step 1: Write table-driven parser tests**

  Cover every command, common flags before/after command, missing/duplicate values, unknown flags, `start` target paths with spaces, non-interactive requirements, help, and version.

- [ ] **Step 2: Run and verify failure**

  Run: `./gradlew :app:test --tests org.fruitandfaults.cli.CommandParserTest`

  Expected: FAIL because parsing types do not exist.

- [ ] **Step 3: Implement standard-library parsing**

  Do not add a CLI dependency for six commands. Return typed parse failures; never call `System.exit` below `main`.

- [ ] **Step 4: Write renderer and prompt tests**

  Assert expected/observed/next-action layout, stdout/stderr split, color/no-color, numbered reflection choices, retry prompt, preview confirmation, no stack trace normally, and diagnostic cause with `--verbose`.

- [ ] **Step 5: Implement terminal adapters and renderers**

  Treat absent console/TTY as non-interactive. Never block waiting for input when required flags are absent.

- [ ] **Step 6: Write command journey tests**

  Exercise `start`, `status`, failing/passing `check`, sequential `hint`, wrong/correct `next`, dirty Git block, `list`, repeated commands, and final completion through injected use-case ports. Assert all exit codes.

- [ ] **Step 7: Wire `ApplicationFactory` and real adapters**

  Construct the course catalog, repositories, safe filesystem, bounded process runner, Git adapter, validators, and use cases at the composition root only.

- [ ] **Step 8: Run all CLI tests**

  Run: `./gradlew :app:test --tests 'org.fruitandfaults.cli.*'`

  Expected: PASS.

- [ ] **Step 9: Review checkpoint**

  Read the transcripts as a learner and inspect `git diff`. Do not commit without explicit user authorization.

### Task 13: Add Windows, macOS, and Linux CLI Installation Tasks

**Files:**
- Create: `buildSrc/build.gradle.kts`
- Create: `buildSrc/src/main/kotlin/org/fruitandfaults/build/CliPlatform.kt`
- Create: `buildSrc/src/main/kotlin/org/fruitandfaults/build/VerifyJdkTask.kt`
- Create: `buildSrc/src/main/kotlin/org/fruitandfaults/build/InstallCliTask.kt`
- Create: `buildSrc/src/main/kotlin/org/fruitandfaults/build/UpdateCliPathTask.kt`
- Create: `buildSrc/src/main/kotlin/org/fruitandfaults/build/VerifyCliTask.kt`
- Create: `buildSrc/src/main/kotlin/org/fruitandfaults/build/UninstallCliTask.kt`
- Create: `buildSrc/src/test/kotlin/org/fruitandfaults/build/CliInstallerTest.kt`
- Modify: `app/build.gradle.kts`
- Modify: `README.md`

**Interfaces:**
- Consumes: `installDist` output, OS name, shell, user home, local app data, install-root/profile overrides, JDK executables, and explicit Gradle task invocation.
- Produces: `installCli`, `addCliToPath`, `setupCli`, and `uninstallCli`; a versioned installation marker; platform install roots; `fruit-and-faults` command exposure.

- [ ] **Step 1: Write installer tests with redirected user state**

  Cover Windows install root and case-insensitive PATH de-duplication, macOS application-support root and marked zprofile block, Linux XDG/default root and Bash/zsh blocks, unknown shell manual guidance, Unicode/spaces, repeated setup, unmarked-directory conflict, unrelated PATH/profile preservation, and owned uninstall.

- [ ] **Step 2: Run and verify failure**

  Run: `./gradlew -p buildSrc test`

  Expected: FAIL because installer task types do not exist.

- [ ] **Step 3: Implement platform detection and JDK 26 verification**

  Validate both `java` and `javac`. Return actionable platform-specific guidance. Do not download or install a JDK.

- [ ] **Step 4: Implement marked distribution installation**

  Windows root: `%LOCALAPPDATA%\Programs\FruitAndFaults`. macOS root: `~/Library/Application Support/FruitAndFaults`. Linux root: `$XDG_DATA_HOME/fruit-and-faults` or `~/.local/share/fruit-and-faults`. Refuse to replace an unmarked directory.

- [ ] **Step 5: Implement PATH/profile ownership**

  Update only user-level Windows PATH. On macOS/Linux create `~/.local/bin/fruit-and-faults` and add/remove only the marked profile block when necessary. Unknown shells print a manual command and do not guess a file.

- [ ] **Step 6: Implement setup verification and uninstall**

  Make `setupCli` depend on verify-JDK, install, PATH, and verify-command tasks. `uninstallCli` removes only a matching marker, owned command link, and owned PATH/profile entry.

- [ ] **Step 7: Run installer tests and build distributions**

  Run: `./gradlew -p buildSrc test`

  Expected: PASS.

  Run: `./gradlew :app:installDist :app:distZip`

  Expected: PASS and distributions contain both Unix and Windows launchers.

- [ ] **Step 8: Perform platform smoke tests**

  Run `setupCli`, `fruit-and-faults --version`, and `uninstallCli` on Windows, macOS, and Linux CI runners or explicitly documented machines. Record OS, shell, JDK version, commands, and results in `docs/testing/phase-a-installation-smoke.md`.

- [ ] **Step 9: Review checkpoint**

  Inspect every external user-directory mutation. Do not commit without explicit user authorization.

### Task 14: Verify the Complete Learner Journey and Pilot Materials

**Files:**
- Create: `app/src/test/java/org/fruitandfaults/journey/PhaseAJourneyTest.java`
- Create: `app/src/test/java/org/fruitandfaults/journey/PhaseARecoveryJourneyTest.java`
- Create: `app/src/test/java/org/fruitandfaults/journey/PhaseAErrorJourneyTest.java`
- Create: `app/src/test/resources/journeys/phase-a/**`
- Create: `docs/pilot/phase-a-observer-guide.md`
- Create: `docs/pilot/phase-a-intentional-errors.md`
- Create: `docs/testing/phase-a-installation-smoke.md`
- Modify: `README.md`
- Modify: `docs/poc-product-roadmap.md`

**Interfaces:**
- Consumes: the installed CLI composition root, temporary learner repositories, deterministic fixture edits/commits, and the approved Phase A spec.
- Produces: executable proof of the four-lesson journey, recovery/error scenarios, installation evidence, and a pilot script that records observations without remote telemetry.

- [ ] **Step 1: Write the happy-path journey test**

  In a temporary directory: start; assert intentional compilation failure; apply lesson 1 fix; check; commit; answer; advance; implement and commit lessons 2–4; assert cumulative tests; complete course; assert final metadata-commit advice. Use local Git only and no network.

- [ ] **Step 2: Write recovery and idempotency journeys**

  Cover stop/resume from nested directories, repeated start/check/next, every injected transaction interruption, restored progress from a cloned local repository, and an updated bundle that appends lesson 5 without rewriting Phase A files.

- [ ] **Step 3: Write intentional-error journeys**

  Cover compile error, failed visible test, deleted immutable test, whitespace-only editable-template change, missing artifact, dirty worktree, file-name conflict, malformed/future progress, timeout, interruption, and internal validator failure. Assert no default stack trace.

- [ ] **Step 4: Run journey tests**

  Run: `./gradlew :app:test --tests 'org.fruitandfaults.journey.*'`

  Expected: PASS without network, browser, or real sleeps.

- [ ] **Step 5: Write pilot and installation evidence documents**

  Observer guide records per-lesson duration, hint levels, repeated failures, manual interventions, reflection explanation, Git/GitHub friction, stop/resume success, and whether the learner can identify the next action. Intentional-error guide gives setup and expected category without exposing solutions.

- [ ] **Step 6: Update learner/developer documentation**

  Document JDK 26, `setupCli` per OS, `start`, the six commands, offline behavior, public GitHub flow, and how a later CLI/course update continues the same workspace.

- [ ] **Step 7: Apply formatting and inspect the formatting diff**

  Run: `./gradlew spotlessApply`

  Expected: PASS. Inspect `git diff` and confirm Spotless changed only intended Java/Kotlin/Gradle files.

- [ ] **Step 8: Run the complete quality gate**

  Run: `./gradlew check`

  Expected: PASS for tests, Spotless, Checkstyle, Error Prone with NullAway, and JaCoCo line coverage at or above 80%.

- [ ] **Step 9: Run final distribution verification**

  Run: `./gradlew :app:installDist :app:distZip`

  Expected: PASS; inspect the distribution for course resources, dependencies, and both launcher types.

- [ ] **Step 10: Final review checkpoint**

  Report changed files, focused tests, `spotlessApply`, `check`, platform smoke-test evidence, remaining platform assumptions, and any pilot-only risks. Do not commit without explicit user authorization.
