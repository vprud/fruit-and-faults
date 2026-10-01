# Project guidance

## Purpose

This repository contains a Java CLI for an interactive course in which the
learner builds a game. The CLI guides lessons, applies or validates changes in
a learner workspace, tracks progress, and launches a local Spring Boot runtime
that renders the game.

Prefer a small, understandable teaching tool over a general-purpose course
platform. Optimize for safe learner experimentation, deterministic feedback,
and fast local iteration.

## How to interpret these rules

- **MUST / MUST NOT** define a project invariant. Do not violate one unless the
  user explicitly requests the exception and the change remains safe.
- **SHOULD / SHOULD NOT** define the default choice. A different choice is
  acceptable for a concrete technical reason; report that reason.
- **MAY** identifies an allowed option, not a preferred design.
- **IF / WHEN** limits a rule to the stated condition. Do not introduce a
  technology or abstraction merely to make a conditional rule applicable.
- Names introduced as examples are illustrative, not prescribed architecture.

## Resolving conflicts

Apply guidance in this order:

1. The user's explicit task and constraints.
2. Safety, learner data, and preservation of existing user changes.
3. Project invariants marked **MUST** or **MUST NOT**.
4. Rules for the subsystem being changed.
5. Defaults marked **SHOULD** or **SHOULD NOT**.
6. Non-normative examples.

If two applicable **MUST** rules conflict, preserve safe completed work,
explain the conflict, and ask for direction instead of silently choosing one.

## Product and learning model

- **MUST** treat the learning path as domain data, not as a chain of hard-coded
  CLI conditionals. A lesson should have an identity, instructions,
  prerequisites, completion criteria, and the assets or actions it needs.
- **MUST** distinguish course-owned starter assets from learner-owned files.
  Generated changes must be attributable and repeatable.
- **SHOULD** make feedback actionable: say what was expected, what was
  observed, and the learner's next useful action. Avoid exposing stack traces
  by default; retain diagnostics behind an explicit verbose/debug mode.
- **SHOULD** allow a learner to stop and resume without losing valid progress.
  Persist progress only after the corresponding state or checkpoint is valid.
- **SHOULD** prefer observable completion criteria over hidden implementation
  details. Validate behavior, public contracts, and required artifacts rather
  than prescribing one exact solution unless that constraint is pedagogical.
- **MUST NOT** silently solve an exercise that the learner is expected to
  complete. Scaffolding, hints, examples, and automated fixes must be clearly
  distinguished in the product contract.

## Architecture

- **SHOULD** organize production code by cohesive capability such as `course`,
  `lesson`, `workspace`, `progress`, `cli`, or `renderer`, not by repository-wide
  technical layers.
- **SHOULD** use subpackages such as `domain`, `application`, and `infra` inside
  a capability only when those boundaries carry real behavior. **SHOULD NOT**
  create empty layers in advance.
- **MUST** keep lesson rules, progress transitions, validation decisions, and
  game-state calculations independent of Spring, CLI, filesystem, process,
  clock, random, logging, and serialization types.
- Dependencies **MUST** point toward the domain. Framework and delivery code
  may depend on domain/application code; the reverse is forbidden.
- **MUST** treat the CLI and Spring Boot renderer as separate adapters. They may
  share application use cases and domain types, but must not invoke each
  other's framework entry points.
- **SHOULD** expose narrow ports owned by the use case for filesystem access,
  progress storage, process execution, rendering lifecycle, and time.
- **SHOULD NOT** introduce a distributed service, message broker, database, or
  plugin framework until a concrete product requirement justifies it.
- **SHOULD NOT** extract shared abstractions before at least two real consumers
  need the same stable concept. Similar code with different reasons to change
  should remain separate.
- For difficult abstractions, **SHOULD** examine three concrete use cases before
  generalizing and record the trade-off when the choice is not obvious.

## Java 26

- **MUST** compile and test with the Gradle Java 26 toolchain configured by the
  repository. Do not rely on a developer machine's default JDK.
- **MUST** format Java with Spotless and google-java-format. Run
  `./gradlew spotlessApply` after editing Java or Gradle build files and inspect
  the resulting diff.
- **MUST** keep Error Prone and NullAway findings at error severity. Production
  packages opt into NullAway through JSpecify `@NullMarked`; use `@Nullable` at
  genuine nullable boundaries and do not suppress findings without a documented
  reason.
- **MUST** satisfy Checkstyle for naming, imports, public API Javadoc, and source
  structure. Formatting and style checks are complementary; one does not
  replace the other.
- **SHOULD** prefer immutable records, sealed types, exhaustive switches, and
  small explicit domain types when they make states and transitions clearer.
- **SHOULD** use standard-library facilities before adding a dependency.
- **MUST NOT** enable preview features or use incubating APIs without an
  explicit project decision covering compilation, tests, packaging, and the
  learner's runtime.
- **SHOULD NOT** use `null` or booleans to collapse outcomes callers must handle
  differently. Model meaningful alternatives explicitly.
- **MUST** preserve interrupt status and cancellation semantics when executing
  or supervising background work.
- **MUST** bound executors, subprocesses, queues, retries, and waits. Every
  launched process must have an owner and a defined shutdown path.

## CLI contracts

- **MUST** keep command parsing and terminal I/O thin. Commands delegate to
  application use cases; they do not contain lesson or game rules.
- **MUST** keep command names, exit codes, stdout/stderr behavior, and persisted
  formats stable once documented. Breaking changes require an explicit product
  decision and migration or clear release note.
- **SHOULD** support non-interactive operation for deterministic tests and
  automation even when the primary experience is interactive.
- **SHOULD** print human-oriented output to stdout, diagnostics to stderr, and
  return non-zero exit codes for failed commands.
- **MUST** make destructive or overwriting actions explicit. Show the affected
  paths and require confirmation in interactive mode; require an explicit
  force flag in non-interactive mode.
- **MUST NOT** require network access for an already installed course unless a
  feature is explicitly documented as online.

## Learner workspace and process safety

- **MUST** treat learner files, saved progress, imported course content, and
  subprocess output as untrusted input.
- **MUST** resolve and normalize paths before access. Workspace operations must
  remain within the selected learner workspace unless the user explicitly
  chooses another location.
- **MUST NOT** follow a symlink to read, overwrite, or delete content outside
  the allowed workspace.
- **MUST** use atomic replacement for tool-owned state where practical and
  preserve the last valid state if a write fails.
- **MUST** avoid broad recursive deletion. Before removing generated content,
  verify both its exact path and its tool-owned identity or manifest.
- **MUST** pass subprocess arguments as an argument list, not through shell
  string concatenation. Bound runtime and captured output, and report timeout,
  exit code, and useful diagnostics.
- **MUST NOT** log secrets, environment dumps, full learner source trees, or
  sensitive local paths unnecessarily.
- **SHOULD** offer a dry-run or preview for multi-file edits and migrations.

## Spring Boot renderer

- **MUST** keep Spring annotations, HTTP types, and configuration outside the
  game, course, and lesson domain models.
- **SHOULD** run the renderer as a local, replaceable adapter behind a narrow
  lifecycle/API boundary. The course engine must remain testable without
  starting a Spring context or opening a port.
- **MUST** bind locally by default. Binding to a non-loopback interface requires
  an explicit user choice and appropriate access controls.
- **MUST** validate all request input and bound payload size. Do not expose
  arbitrary filesystem access or command execution through renderer endpoints.
- **SHOULD** use deterministic game state and injectable time/randomness where
  lesson validation or replay depends on them.
- **SHOULD** use Spring slice or context tests only for framework wiring;
  behavior belongs in framework-free tests.

## Persistence and formats

- **SHOULD** begin with a versioned, local, human-inspectable progress format
  unless concurrent access or query needs justify a database.
- **MUST** write a format version and validate input before constructing domain
  state. Unknown future versions must fail safely with a useful message.
- **MUST** make progress updates idempotent. Re-running a successful lesson or
  recovery step must not corrupt or duplicate state.
- **WHEN** a persisted schema changes, provide a tested migration or retain
  backward-compatible reading for supported versions.

## Tests

- **SHOULD** structure tests as Arrange–Act–Assert and name them after observable
  behavior.
- **SHOULD** prioritize tests of complete learner journeys and application use
  cases over one mock-heavy unit test per class.
- **SHOULD** unit-test pure lesson rules, parsers, validators, state machines,
  and edge-case-heavy game logic with table-driven or parameterized tests.
- **SHOULD** use temporary directories and real filesystem operations for
  workspace behavior. Tests must not touch a developer's actual project or
  home directory.
- **MUST** test path traversal, symlink escape, partial writes, repeated runs,
  malformed progress, subprocess failure, timeout, and interruption when the
  affected feature handles those cases.
- **MUST NOT** use real sleeps, live network services, or a browser in the
  default test suite. Inject time and process/rendering boundaries.
- A bug fix **MUST** include a regression test that fails for the defect and
  passes for the fix when such a test is technically feasible.
- **SHOULD NOT** assert incidental call order or implementation details unless
  they are part of the user-visible contract.
- **MUST** keep JaCoCo line coverage at or above 80%. Coverage is a regression
  guard, not a substitute for meaningful assertions; do not add assertion-free
  tests merely to increase the number.

## Agent workflow

- **MUST** inspect relevant code, tests, build configuration, and local guidance
  before editing. Preserve unrelated user changes and avoid broad refactors
  without an explicit reason.
- **MUST** use CodeGraph before text search or broad file reading when a
  `.codegraph/` directory exists and the task requires locating or
  understanding code.
- Before adding a dependency or abstraction, **MUST** check whether the Java
  standard library or an existing project dependency already solves the need.
- **SHOULD** keep changes vertical: update the use case, adapter, tests, and
  relevant documentation together.
- **MUST NOT** modify learner-facing behavior without updating the corresponding
  help, lesson text, or contract documentation when it exists.

## Definition of Done

- After changing Java or build code, **MUST** run the narrowest relevant tests
  first, run `./gradlew spotlessApply`, inspect the formatting diff, and run
  `./gradlew check`.
- `./gradlew check` **MUST** remain the complete quality gate. It includes tests,
  `spotlessCheck`, Checkstyle, Error Prone with NullAway during compilation, and
  JaCoCo coverage verification.
- Documentation-only changes do not require Gradle checks unless they change
  executable examples or build instructions.
- **WHEN** dependencies change, **MUST** update the version catalog, locks, or
  verification metadata used by the repository and keep that diff focused.
- **MUST NOT** create a commit unless the user requests one.
- **WHEN** the user requests a commit, use Conventional Commits syntax with an
  English subject: `<type>(<optional-scope>): <description>`.
- Before finishing, **MUST** report what changed, which checks ran, and any
  remaining risk or unverified assumption.
- **MUST NOT** claim successful completion when a required check fails because
  of the current changes.
