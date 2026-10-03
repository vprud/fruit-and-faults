# Course bundle format, version 1

`ClasspathCourseCatalog("course").load()` returns a validated immutable `Course`.
Content lives in classpath resources. The loader reads Java Properties and
strict UTF-8 Markdown; asset fingerprints use raw bytes, without text conversion.
Each resource is limited to 1 MiB, and each declared list to 100 entries.

`course.properties` requires `courseId`, `contentVersion`, `title`, and
`lessonOrder`. Version 1 is the supported format. `lessonOrder` is a comma-separated
list of stable lesson IDs. For every ID, `lesson.<id>.directory` specifies a
normalized path relative to the bundle root. Directory names never determine
lesson order.

Each lesson directory has `lesson.properties` with these required keys:

| Key | Meaning |
| --- | --- |
| `id`, `title`, `goal` | Stable identity, display title, and learning goal |
| `prerequisites` | Comma-separated IDs that must occur earlier in the route |
| `assets` | Comma-separated asset IDs to disclose |
| `expectedArtifacts` | Comma-separated normalized learner workspace paths |
| `completionCriteria` | Comma-separated stable embedded validator IDs |
| `criterion.<id>.description` | Observable completion rule for each criterion |
| `instructions` | Markdown resource relative to the lesson directory |
| `hints` | Exactly three Markdown resources, in disclosure order |
| `question` | Question Properties resource relative to the lesson directory |
| `recommendedCommitMessage` | Suggested local Conventional Commit message |

Empty lists are declared with an empty value. Completion criteria must be
nonempty. IDs use lowercase letters and digits with hyphen-separated segments.
Lists reject empty entries and duplicates. The course validates that every
declared lesson exists, no definitions are omitted, and prerequisites are
present, earlier, and acyclic.

Every asset requires `asset.<id>.path`, `asset.<id>.resource`, and
`asset.<id>.policy`. The destination path is relative to the learner workspace;
the resource path is relative to the lesson directory. Policies are
`IMMUTABLE_CHECK`, `EDITABLE_TEMPLATE`, and `LEARNER_SCAFFOLD`. The loader computes
the lowercase SHA-256 fingerprint. An optional `asset.<id>.sha256` is verified
against the raw resource bytes when present. The resulting `LessonAsset`
contains the full classpath resource name and the verified fingerprint.

Paths must already be normalized: absolute paths, drive prefixes, backslashes,
control characters, empty segments, and `.` or `..` segments are rejected.
Filesystem containment and symlink checks belong to the workspace adapter that
later consumes these relative paths.

`question.properties` requires `id`, `prompt`, `options`, and `correctOptionId`.
`options` lists at least two stable option IDs. Every option supplies
`option.<id>.text` and `option.<id>.feedback`. Exactly one listed ID is identified
as correct. Display text, feedback, instructions, and hints must be nonblank.

Unknown property keys, missing resources, malformed text, invalid values, and
unsupported versions fail before a course is returned. Errors identify the
affected resource or property and advise restoring the installed bundle.

The initial four-lesson metadata bundle intentionally declares no production
assets. Starter source, visible tests, build resources, and exact artifact
declarations are added by the subsequent learner asset task.
