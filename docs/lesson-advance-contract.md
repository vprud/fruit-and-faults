# Reflection and lesson advancement

`AdvanceLesson` backs `next --answer <stable-option-id> --yes` for an active
lesson and `next --yes` for a completed saved route or an appended continuation.
CLI parsing and terminal prompts follow the [CLI contract](cli-contract.md).
Reflection uses declared option identities,
independent of their displayed order. Wrong selections receive that option's
targeted feedback; unknown selections receive safe guidance without echoing
untrusted input or revealing the accepted answer. Feedback strips terminal and
bidirectional control characters. Evaluation performs no IO and persists nothing.

A normal transition loads validated course, progress, ownership, and pending
recovery state. A missing answer returns the active question before checking
the lesson or inspecting Git. With an answer, it checks the active lesson before
evaluating that selection. After
recognition succeeds it requires a local commit and clean tracked/untracked state.
The first lesson requires HEAD to exist; later lessons require HEAD to differ
from the active lesson's opening revision. Missing origin or upstream produces
advice and never blocks advancement. No network operation, staging, commit,
branch change, or remote change is performed.

The complete next disclosure is preflighted and returned as an exact ordered
preview. An explicit confirmation is required before any journal, learner asset,
manifest, or progress write. HEAD and metadata are inspected again before writes.
The next opening revision is the validated current HEAD, captured before its
assets appear. The accepted answer stays out of persisted progress until the
existing disclosure transaction commits progress last. After confirmation the
recovery journal records the intended answer, without treating it as completed
progress. Failed checking, recognition, Git gating,
preview, confirmation, asset publication, or pre-commit persistence preserves
the prior progress bytes. A pending journal may remain after an interrupted
disclosure; its existing [recovery semantics](workspace-files-format.md) apply.

The final lesson has a metadata-only preview and the same check, recognition,
Git, and confirmation requirements. Its terminal progress is saved atomically
through the progress repository. The result advises making one final metadata
commit so a clone also sees completion. Repeating `next` on a completed route
performs no further progress write. Repeating after opening a lesson checks that
current lesson; it cannot complete it using the prior lesson's answer.

## Recovery

A pending journal is handled before current-lesson checks and the normal clean
worktree gate: disclosed files and progress can already account for dirty state.
It requires exact prior or intended progress/ownership and verifies that prior
ownership describes all opened historical assets. A supplied answer must match
the journal's accepted source-lesson answer. Without
that source answer, an ordinary transition returns its source question before
checks, Git inspection, preview, or mutation, even if the target progress has
already committed. Continuation journals have no new reflection answer: they
accept an absent answer at every durable boundary and reject a supplied answer.
Once ready, recovery requires the same opening HEAD recorded in the intended
snapshot, and its exact preview still requires confirmation. Readiness uses this
validated application state, not `ShowStatus`, whose ordinary ownership/current
lesson view does not describe an in-flight continuation.

Recovery accepts matching journal-attributed bytes, creates only missing targets,
and rejects changed or partial files without overwriting them. It resumes crashes
after journal creation, any asset, manifest publication, progress publication, or
journal cleanup. A crash after progress commits can be verified and cleaned up
without rewriting progress. Unknown formats, incompatible snapshots, modified
targets, a changed HEAD, or an answer mismatch preserve pending state.

## Supported course evolution

Progress and journals retain format version 1. Older content versions are read
only against an explicitly shipped trusted historical `Course` definition.
Compatibility requires the same course ID and a preserved route prefix,
prerequisites, asset IDs/paths/hashes/policies, required artifacts, criterion
IDs/contracts, question ID, options, and accepted option. Historical resource
locations can differ, allowing a later release to retain prior assets separately.
Lesson titles, goals, instructions, and hints can evolve. Question/option text
and feedback are conservatively part of the retained grading contract.

`JacksonProgressCodec(Course)` remains exact-only. Use
`JacksonProgressCodec(CourseCatalog)` or the constructor accepting an explicit
list of supported historical courses for evolution, and give the same codec to
`JacksonTransitionJournalRepository`. Missing history, unknown future content,
unknown referenced IDs, and incompatible history fail before mutation. Reading
never rebases or rewrites a document. A hint on an older active route retains its
older version; a successful disclosure or terminal transition alone can commit
the installed version.

The completed historical route remains complete until confirmation discloses
the first appended lesson. `status` can report this available continuation from
real persisted state and recommends `next`. Continuation requires a local HEAD
and clean worktree but does not repeat reflection or validation of previously
completed work. Existing learner implementations remain untouched.

Each future release that supports old workspaces must ship their historical
contracts and compatible public validators. Code cannot infer those contracts
from the IDs in version-one progress. The catalog's optional compatibility
manifest is documented in the [bundle format](course-bundle-format.md).

Concurrent changes observed during checks, preview, or the last Git inspection
stop the transition. The existing ports do not provide a cross-Git/filesystem
transaction lock: an external change after the last observation remains a
conservative local-snapshot limitation. Disclosure rechecks metadata and assets
at its durable boundaries and never overwrites learner files. Progress retains
the existing atomic-write/provider limitations; no rollback of a successfully
committed state or deletion of learner content is attempted.
