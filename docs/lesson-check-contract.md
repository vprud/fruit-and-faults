# Lesson checks

`CheckLesson` checks the workspace without saving progress, hints, reflection
answers, or lesson completion. CLI composition and presentation are wired in a
later task.

Checks run in this order:

1. Required artifacts and the disclosed ownership manifest are checked through
   bounded reads anchored to verified workspace directories. Symlinks, unsafe
   paths, changed directory identities, and files over 16 MiB block validation.
2. The complete learner Gradle `test` task runs, including earlier visible tests,
   with a 90-second deadline and a 256 KiB aggregate output budget. Unix uses
   `sh ./gradlew`; Windows uses `cmd /d /c gradlew.bat`. Arguments remain separate.
   `--offline --no-daemon --console=plain` keeps validation local and predictable.
   The wrapper distribution and dependencies must already be available locally;
   cache or toolchain failures are infrastructure diagnostics.
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
excluded from this feedback. The parent accepts only fixed result tokens and
never copies worker output into learner diagnostics. Premature process exit
without a complete result is a public-contract failure. Nonterminating methods
time out; interruption retains the caller's cancellation status through the
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
