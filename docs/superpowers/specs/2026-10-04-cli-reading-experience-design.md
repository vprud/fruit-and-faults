# CLI reading experience

## Purpose

Make the installed course readable in a terminal. English is the single language for CLI-owned text and the already-English lesson bundle. A learner should identify the active lesson, the next useful command, and the optional GitHub guide without reading a duplicated wall of text. Full lesson instructions remain available on demand.

## Scope and decisions

The existing six commands retain their names, argument behavior, exit codes, and stdout/stderr routing. Add one read-only command, `fruit-and-faults lesson`, which displays the active lesson's complete installed title, goal, Markdown instructions, suggested commit, and optional publishing-guide reference when relevant. It discovers the workspace like `status`, validates saved state, does not reveal future lessons, and does not write progress or launch a build. A completed course reports that no lesson is active.

`start` after creation or resume, and `next` after opening a lesson, display a compact summary: a one-line outcome, the lesson title and stable ID, its goal, `Next: fruit-and-faults lesson`, and its suggested commit. Fresh lesson one also points to `docs/publishing-to-github.md` with an explicit optional label. The summary never prints raw Markdown or repeats the Git and publishing procedure. The existing validated preview still lists every affected path before confirmation.

`lesson` provides the full instructions, including the compilation-versus-test distinction and the first local commit steps. The installed course resources, asset fingerprints, content version, and saved progress format do not change. Rendering may remove the redundant Markdown heading from the terminal view, but must preserve the instructional text and code examples. A resumed workspace can retrieve its current instructions with `lesson` at any time.

Translate CLI-owned user-facing messages in the parser, command orchestration, renderer, and composition root into English. Preserve literal command names, option IDs, file paths, compiler output, and the published English help/version and missing/unknown-command diagnostics. Do not translate learner input or arbitrary subprocess output. Update the CLI contract and help for the new command and English copy. This is a documented human-text change; machine-readable identifiers, persisted content, and exit meanings stay stable.

## Terminal presentation

Use a small line-oriented layout with blank lines between outcome, lesson, and actions. In an interactive terminal with color enabled, make the lesson title and the labels `Next`, `Suggested commit`, and `GitHub (optional)` bold. Use green only for the success indicator, and cyan for the next command. Body text is unstyled. `--no-color` and non-interactive output emit the same words and spacing without ANSI sequences. Do not introduce a full-screen interface or dependency.

Example after a fresh start, without ANSI styling:

```text
Workspace created.

LESSON · First Run and Diagnostics [first-run]
Distinguish compilation failures from test failures and repair the starter.

Next:             fruit-and-faults lesson
Suggested commit: fix: repair starter compilation
GitHub (optional): docs/publishing-to-github.md
```

The renderer must sanitize course text before adding its own ANSI codes, so course content cannot inject terminal controls. Diagnostics retain bounded expected/observed/next-action fields and stay on stderr. Color selection continues to depend on actual or injected interactive terminal capability and `--no-color`.

## Implementation boundaries

Add a narrow application read use case or extend an existing read use case to obtain the active lesson from validated progress. The CLI adapter handles layout and ANSI styling; course and progress domain code stays free of terminal concepts. Avoid parsing arbitrary Markdown for decorative styling; a complete instruction view may print the trusted installed Markdown as plain text after sanitization. No network access or GitHub authentication is added.

## Verification

Test `start` and advancement summaries, resumed `start`, full `lesson` output, completed-course behavior, and malformed progress through the existing injected CLI journeys with temporary workspaces. Verify English copy, absence of duplicated publishing steps, stdout/stderr and exit codes, bold/cyan escape codes only in interactive color mode, and no escapes in redirected or `--no-color` output. Update transcript tests and command parser/help tests. Run narrow CLI tests, `./gradlew spotlessApply`, inspect the diff, then `./gradlew check` with the repository's Java 26 toolchain.
