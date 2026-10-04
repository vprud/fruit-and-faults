# Lesson checks

`CheckLesson` checks the workspace without saving progress, hints, reflection
answers, or lesson completion. The [CLI contract](cli-contract.md) defines
composition, presentation, and stable exit codes.

Checks run in this order:

1. Required artifacts and the disclosed ownership manifest are checked through
   reads anchored to verified workspace directories in owned workers. Each
   artifact read has a 10-second deadline, 128 MiB heap limit, and fixed-size
   authenticated fingerprints; source bytes never enter parent diagnostics.
   Symlinks, unsafe paths, changed identities, files over 16 MiB, and blocking
   special-file replacement races block validation and terminate the worker.
2. The complete learner Gradle `test` task runs, including earlier visible tests,
   with a 90-second deadline and a 256 KiB aggregate output budget. Unix uses
   `sh ./gradlew`; Windows uses `cmd /d /c gradlew.bat`. Arguments remain separate.
   Before launch, a deadline-bound inspect-only worker parses the exact wrapper
   distribution URL and verifies its URL-derived cache key, `.ok` marker, and
   unambiguous regular Gradle payload. A cold, malformed, unsupported, or
   symlinked cache stops before the wrapper: `--offline` alone does not prevent
   wrapper bootstrap downloads. The verified Gradle user home is pinned as a
   separate `--gradle-user-home` argument. Selection uses `gradle.user.home`,
   then `GRADLE_USER_HOME`, then native `user.home/.gradle`; relative overrides
   resolve inside the selected workspace. Cache verification is read-only and
   never downloads or repairs anything. Dependencies must also already exist;
   `--offline --no-daemon --console=plain` prevents dependency resolution online.
3. Every completion criterion through the active lesson runs against freshly
   compiled main classes. Future lesson criteria are not selected.

Missing files are reported separately from modified immutable visible checks.
Immutable checks must retain their original disclosed SHA-256. Learner scaffolds
may change freely. An editable test template must differ from its disclosed
bytes and then compile; independent public-behavior checks verify the associated
game rules. Whitespace-only changes count as changed bytes and are described as
such. Textual edits do not prove meaningful test work or understanding: review
the assertions and explain the test to the pilot observer.

Manifest IDs, policies, lesson IDs, and original hashes must match the installed
asset declarations. Editing the manifest cannot authorize a modified immutable
check. Inspection reads only required artifacts from the opened lessons; it does
not walk unrelated source trees or extra manifest destinations. No check restores
or overwrites learner files.

Validators execute in an owned Java 26 worker process with a 10-second deadline,
128 MiB maximum heap, 64 MiB maximum metaspace, and 16 KiB captured output.
The worker loads only `build/classes/java/main` through a fresh closeable isolated
classloader whose parent is the Java platform loader. Validators call public
constructors and public methods and inspect their declared signatures. They do
not inspect private members, require a particular internal implementation, load
test classes or application dependencies, or start Spring. Missing classes,
signature mismatches, initialization/linkage failures, constructor failures,
thrown methods, and unexpected results become bounded actionable observations.
Learner exception messages, object strings, local paths, and stack traces are
excluded from this feedback. A one-time 32-byte secret is delivered on private
process stdin, consumed and closed before learner code executes, and never
included in arguments, environment, stdout, or request diagnostic text. The
parent accepts only HMAC-authenticated fixed result frames emitted after the
validator returns; learner stdout is not authoritative and is never copied
into diagnostics. Forged tokens, copied command arguments, and premature exit
without authenticated completion are public-contract failures. Nonterminating
methods time out; interruption retains the caller's cancellation status through the
bounded process runner. Each worker and its observed descendants have the
runner's shutdown path, including a two-second cleanup deadline.

The worker protects the CLI JVM from learner `System.exit`, threads, and runtime
state changes. It is not an OS security sandbox: learner Gradle scripts and
worker game methods execute with the learner's operating-system permissions.
As with Gradle process supervision, descendants that detach before observation
cannot be portably discovered by Java's process API.

Failure categories distinguish `INCOMPLETE_WORK`, `WORKSPACE_CONFLICT`,
`MISSING_ARTIFACT`, `COMPILATION_ERROR`, `TEST_FAILURE`, `TIMEOUT`, `INTERRUPTED`,
and `INTERNAL_ERROR`. Timeout and interruption retain their process-runner
meaning. Failed checks never advance progress.

`check --verbose` adds failure categories and bounded cause identities; it does
not print retained Gradle stdout/stderr, learner exception messages, or stack
traces. To inspect full build output, run the local workspace wrapper's `test`
task with `--offline --no-daemon --console=plain` and an explicit
`--gradle-user-home` pointing to the same prepared cache used by the CLI. Verify
that the exact wrapper distribution is already cached before running it:
`--offline` alone does not prevent wrapper bootstrap downloads. Local wrapper
output and reports can contain learner source or local paths; review them locally
and redact sensitive details before sharing diagnostics.
