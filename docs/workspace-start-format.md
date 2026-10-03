# Workspace initialization and discovery

The `StartCourse` application use case accepts an absent destination or an
existing empty real directory. A nonempty directory without a valid workspace
marker is rejected before mutation. A compatible initialized workspace resumes
its current progress, preserving learner edits and revealed hints. New nested
workspaces are rejected because upward discovery cannot choose between nested
markers safely.

Workspace identity alone does not authorize resume or recovery. Before either
operation, start requires a real non-bare Git repository whose Git directory is
exactly the selected workspace's `.git` and whose worktree root is exactly the
workspace. Parsed Git paths are compared against safely canonicalized filesystem
identities, so equivalent casing aliases on supporting filesystems are accepted.
A missing `.git`, Git pointer file, symlink, empty or invalid
repository, redirected worktree, or external common/object storage is rejected
without disclosing files or updating progress. Start never runs `git init` to
repair a workspace that already carries a marker.

Repository validation is read-only and uses the same process deadline/output
cap as initialization. Its filesystem inspection does not follow symlinks and
is limited to 10,000 entries and 32 directory levels. Local Git configuration
is limited to 64 KiB per file, cannot include external configuration files, and
must not begin with a UTF-8 BOM. This policy applies to both `config` and
`config.worktree` before any Git process is launched.
Repository redirection through `.git/commondir` or object alternates is rejected.
These limits preserve a self-contained learner repository; incompatible state
must be inspected explicitly rather than silently repaired. Confirmed recovery
revalidates the repository immediately before invoking the disclosure transaction.

An unconfirmed request returns a read-only preview. For a fresh workspace it
lists the root, `.git` directory, `.fruit-and-faults/workspace.properties`,
the temporary disclosure journal, every exact lesson-one asset destination,
the ownership manifest, and progress. Git owns the contents of the listed
`.git` directory. Interactive confirmation and non-interactive `--yes` are
delivery concerns; the application applies changes only when `confirmed` is
true. Resuming committed state needs no confirmation because it writes nothing.
Recovering pending disclosure requires a preview and confirmation.

Initialization order is destination creation, local `git init`, exclusive
workspace marker creation, then the existing lesson disclosure transaction.
Git initialization never stages, commits, changes remotes, or contacts the
network. It has a ten-second deadline and a 64 KiB capture limit. Its arguments
are passed separately, repository-redirection environment variables are removed,
and external Git templates are disabled. Cancellation terminates the owned
process and preserves the calling thread's interrupt flag. Default failure
messages include the failure category or exit status without captured output;
bounded output is available through the Git failure only for explicit debugging.
`StartResult.Failed` now retains a typed failure category for CLI mapping:
Git timeout/interruption map to exit 5, initialization infrastructure failures
map to 10, and safe filesystem publication failures map to 3. Its original
three-argument constructor and accessors remain available. Read-only resume
inspection also preserves Git timeout/interruption categories; diagnostics are
never parsed to determine an exit code. See the [CLI contract](cli-contract.md).

The committed marker contains exactly two fields:

```properties
courseId=fruit-and-faults
layoutVersion=1
```

The course ID must match the installed course and layout version must be `1`.
No absolute root path is persisted. Marker reads are UTF-8, limited to 4 KiB,
and reject duplicate or unknown fields, malformed values, unsafe paths, and
symlinks. Marker creation uses the existing anchored exclusive writer: a file
appearing after preflight is preserved, and partial or ambiguous writes remain
for inspection rather than being overwritten or deleted.

If Git initialization fails, no course-owned state has been written; the
directory and any Git files remain. If disclosure fails, the Git repository
and marker remain, and the journal attributes any completed asset writes.
Progress is committed only after the full disclosure and manifest are valid.
Repeating a confirmed start recovers a matching pending journal. Malformed
markers, changed learner files, or state conflicts remain untouched and require
explicit inspection. An uninitialized directory containing only Git files is
still nonempty and rejected; initialization failure does not authorize cleanup.

`WorkspaceLocator` walks from the current real directory to the filesystem
root on every invocation. It returns the one compatible marker or an explicit
not-found, incompatible, ambiguous, or unsafe result. It rejects symlinked
current directories, symlink ancestors, symlinked metadata trees or markers,
and multiple nested markers. Directory names containing spaces or Cyrillic are
supported.
