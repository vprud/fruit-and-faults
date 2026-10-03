# Status, route, hints, and local Git observations

The application use cases back the Phase A `status`, `list`, and `hint` commands.
CLI parsing and rendering are wired separately. They do not run Gradle or contact
a remote repository.

`status` reports the active lesson identity, title, goal, and revealed hint level;
the required artifacts of opened lessons; local Git facts; publication advice;
available appended content; and exactly one recommended command. Artifact
presence means only that anchored metadata identifies a regular file. It does
not establish compilation, visible-test integrity, or behavior. Missing artifacts
recommend `check`; unsafe entries or invalid progress/ownership/repository state
produce an actionable diagnostic and preserve the workspace.

Git inspection first requires the selected workspace to contain its own safe,
non-bare `.git` repository. It rejects external storage redirects and local
configuration includes, external attribute files, and executable filter
configuration. Nested repositories and submodules are intentionally unsupported
in the Phase A workspace and produce a workspace diagnostic before Git runs.
Case-ambiguous Git metadata is rejected on filesystems where it aliases the
reserved names. Inspection disables global/system configuration and attributes,
fsmonitor hooks, optional locks, and lazy fetching, and allows no remote transport
protocols.
Every command is a literal argument list with a finite deadline and output cap.
Oversized, malformed, incomplete, or unsafe output fails conservatively. Results
contain change counts and presence flags, never filenames, remote URLs, or
credentials. CRLF and LF fact lines and NUL-delimited rename records are handled.

An unborn branch differs from an invalid or unresolved existing HEAD. For lesson
one, the local lesson gate requires HEAD to exist. For later lessons, HEAD must
differ from the revision recorded when the lesson opened. Pending tracked,
untracked, or submodule changes also block the gate. Missing origin or upstream
is advisory; publication never blocks a lesson. Inspection never stages, commits,
changes refs/remotes, or fetches/pushes.

`list` returns route identities, public titles, and completed/active/locked states.
When a validated completed older snapshot has a compatible appended installed
route, the first appended lesson is available. The view contains no future
instructions, tests, hints, reflection prompts/answers, or solutions. Snapshot
comparison does not migrate persisted progress or weaken the strict codec;
append-compatible persisted reading and transition recovery are later tasks.

`hint` selects only the active lesson's domain hint text, then persists its level
through the existing atomic progress repository before returning the text.
Levels advance one at a time from one through three. Repeating level three
returns the same text without rewriting progress. Loading or persistence failure
returns no hint and retains the previous valid progress. A completed route has
no active hint, including when appended lessons have not yet been opened.

After terminal completion, dirty Git state triggers advice to make a final
metadata commit so a clone observes completion. A clean completed workspace
recommends `list`; a compatible available continuation recommends `next`.
