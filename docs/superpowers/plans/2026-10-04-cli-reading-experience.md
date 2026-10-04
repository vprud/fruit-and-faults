# CLI Reading Experience Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the installed course CLI concise, consistently English, and easy to scan while keeping full instructions one command away.

**Architecture:** A read-only application use case selects the active installed lesson from validated progress. CLI parsing and orchestration expose `lesson`; `TextRenderer` owns the compact and detailed views, safe terminal styling, and English delivery copy. Course resources and saved-state formats remain unchanged.

**Tech Stack:** Java 26, Gradle, JUnit Jupiter, existing standard-library ANSI sequences and repository adapters.

**Spec:** `docs/superpowers/specs/2026-10-04-cli-reading-experience-design.md`

## Global Constraints

- Keep existing six command names, argument behavior, exit codes, and stdout/stderr routing; add `lesson` as read-only.
- Keep installed course resources, asset fingerprints, content version 1, and saved progress format unchanged.
- English for CLI-owned text; retain command names, IDs, paths, compiler output, and existing English help/version and missing/unknown command diagnostics.
- Bold lesson heading and `Next`, `Suggested commit`, `GitHub (optional)` labels; green success indicator; cyan next command only for interactive color-capable output.
- No ANSI with `--no-color` or redirected output; sanitize course text before adding style; no new dependency or full-screen UI.
- Use the Gradle Java 26 toolchain; after Java edits run narrow tests, `./gradlew spotlessApply`, inspect the diff, and run `./gradlew check`.
- Do not create a commit unless requested by the user.

## Review Focus

1. A malformed or future-version progress file must make `lesson` fail safely without revealing text. Task 1 tests this.
2. A completed route must show no active lesson, including when a future lesson is merely available. Task 1 tests this.
3. Course text containing terminal controls must not inject escapes into plain or styled output. Task 2 tests this.
4. Redirected output and `--no-color` must contain zero ANSI bytes while preserving every instruction line. Task 2 tests this.
5. Interactive prompts must accept English confirmation and preserve cancellation/retry behavior. Task 3 tests this.

---

## File structure

- Create `course/application/ShowLesson.java` and `LessonResult.java`: select the active lesson and represent active, complete, or unavailable outcomes.
- Modify `cli/Arguments.java`, `CommandParser.java`, `FruitAndFaults.java`, `ApplicationFactory.java`: add `lesson` routing and translate CLI-owned text.
- Modify `cli/TextRenderer.java`: compact summaries, full lesson view, selective styling, English rendering.
- Modify CLI journey, parser, renderer, and application tests and three transcript resources: verify the user-facing contract.
- Modify `docs/cli-contract.md` and `README.md` only where they enumerate commands or describe CLI language and output.

### Task 1: Read the active lesson safely

**Files:** Create `app/src/main/java/org/fruitandfaults/course/application/{ShowLesson,LessonResult}.java`; modify `ApplicationFactory.java` and `Arguments.java`; add `app/src/test/java/org/fruitandfaults/course/application/ShowLessonTest.java`.

**Interfaces:** `ShowLesson(CourseCatalog, ProgressRepository)` exposes `LessonResult execute(Path root)`. `LessonResult` has `Active(Lesson lesson)`, `CourseComplete`, and `Unavailable(FailureCategory category, Diagnostic diagnostic)`. `ApplicationFactory.Application` gains `Function<Path, LessonResult> lesson` wired to the use case. `Arguments.Command` gains `LESSON`.

- [ ] Write failing use-case tests: active lesson returns installed title/goal/instructions/commit; complete progress returns `CourseComplete`; malformed/future progress returns `Unavailable(WORKSPACE_CONFLICT)`; interruption returns `Unavailable(INTERRUPTED)` while retaining interrupt status. Use a temporary workspace and real progress repository where practical.
- [ ] Run `./gradlew :app:test --tests org.fruitandfaults.course.application.ShowLessonTest` and confirm failure is the missing feature.
- [ ] Implement the types and use case, using `ListLessons.requireCompatible` and selecting only the active ID. Wire it in `ApplicationFactory`; keep the use case read-only.
- [ ] Rerun the same test until green. Update existing `ApplicationFactory.Application` test fixtures for the new field.

### Task 2: Compact and styled lesson views

**Files:** Modify `app/src/main/java/org/fruitandfaults/cli/TextRenderer.java`; modify `app/src/test/java/org/fruitandfaults/cli/{TextRendererTest,CommandJourneyTest}.java`.

**Interfaces:** `TextRenderer.lesson(LessonResult result, boolean verbose): CommandResult`; `start` and `next` reuse a private summary renderer for their opened lesson. Presentation sanitizes each untrusted field before wrapping fixed labels with ANSI style.

- [ ] Write failing renderer/journey tests: created/resumed/advanced summaries omit raw instructions and repeated GitHub procedure; the `lesson` view contains all instruction paragraphs and suggested commit; complete and unavailable results route correctly. Assert the spec's exact plain layout for the fresh first lesson.
- [ ] Test interactive bold heading/labels, green outcome, cyan next command; test noninteractive and `--no-color` output for zero escapes; test embedded control and bidirectional characters in course text are removed without stripping renderer-owned style.
- [ ] Run `./gradlew :app:test --tests org.fruitandfaults.cli.TextRendererTest --tests org.fruitandfaults.cli.CommandJourneyTest` and confirm the new tests fail for the intended missing behavior.
- [ ] Implement the summary and detailed renderer, dropping only the redundant Markdown heading from the detailed view and preserving all other instructions. Avoid rendering the whole success body green.
- [ ] Rerun the same narrow tests until green.

### Task 3: English CLI and `lesson` command

**Files:** Modify `app/src/main/java/org/fruitandfaults/cli/{CommandParser,FruitAndFaults,TextRenderer,ApplicationFactory}.java`; modify corresponding tests and `app/src/test/resources/transcripts/*.txt`; update `docs/cli-contract.md` and `README.md` command documentation.

**Interfaces:** `fruit-and-faults lesson` accepts common flags, no positional arguments, and no `--yes` or `--answer`; routes to `Application.lesson()` and `TextRenderer.lesson()`. Its successful output uses stdout, unsafe-state diagnostics use stderr and existing exit codes.

- [ ] Write failing parser and injected journey tests for `lesson`, rejected extra/irrelevant arguments, completion, malformed state, English prompt/diagnostic copy, English confirmation (`yes`/`y`), declined confirmation, and no state mutation.
- [ ] Run `./gradlew :app:test --tests org.fruitandfaults.cli.CommandParserTest --tests org.fruitandfaults.cli.CommandJourneyTest --tests org.fruitandfaults.cli.FruitAndFaultsTest` and confirm the new tests fail for missing behavior.
- [ ] Add command routing and help. Translate all CLI-owned Russian strings, including truncation markers, parser errors, questions, preview/status/check/hint/list/next messages, confirmation/cancellation, and composition-root completion feedback. Preserve existing English missing/unknown command copy.
- [ ] Update old assertions/transcripts to the approved English contract; update `docs/cli-contract.md` and README command examples/overview. Re-run the narrow tests until green.

### Task 4: Verify complete quality gate

**Files:** No planned production additions; only fix findings in files touched above.

- [ ] Run the narrowest changed test classes after the final edit.
- [ ] Run `./gradlew spotlessApply`, then inspect `git diff --check`, `git diff --stat`, and the formatted Java diff.
- [ ] Run `./gradlew check` and confirm tests, Spotless, Checkstyle, Error Prone/NullAway, and JaCoCo pass.
- [ ] Inspect a noninteractive `start` and `lesson` transcript and confirm the intended English hierarchy and absence of ANSI. Report changes, commands run, and any remaining risk; do not commit.
