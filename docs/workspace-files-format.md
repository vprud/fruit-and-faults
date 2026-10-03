# Workspace files and ownership manifest

Workspace destinations are logical slash-separated relative paths. They must
already be normalized: empty paths, empty segments, `.`, `..`, Unix absolute
paths, Windows drive/UNC paths, backslashes, colons, and control characters are
rejected. Spaces and Cyrillic names are supported.

Disclosure requires an existing real workspace directory; `start` safely creates
an absent destination or accepts an empty directory as described in
[workspace initialization and discovery](workspace-start-format.md). It rejects
every symlink within that workspace, including links pointing back inside it. A blocked path
must be moved aside or replaced with a real directory before disclosure. Case
and Unicode-normalization aliases are conservatively rejected on every host,
so a course remains portable between case-sensitive and case-insensitive
filesystems. A requested file cannot also be another asset's parent directory.

The complete disclosure is preflighted before any target or parent directory is
created. An existing unknown file conflicts even if its bytes match the course
asset. A file is already applied only when its recorded asset ID, lesson ID,
original SHA-256, exact policy, and current bytes all match. A pending journal can
also attribute matching bytes written before the manifest was published. The
manifest and journal never authorize restoring or overwriting learner changes.
Learner assets cannot target the reserved `.fruit-and-faults` metadata tree,
including case or Unicode aliases.

`.fruit-and-faults/managed-files.json` stores version-one ownership facts:

```json
{
  "formatVersion": 1,
  "files": [
    {
      "path": "src/main/java/org/fruitandfaults/game/Game.java",
      "assetId": "game-starter",
      "sha256": "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
      "lessonId": "first-run",
      "policy": "LEARNER_SCAFFOLD"
    }
  ]
}
```

The hash records the original raw disclosed bytes, not the current learner file.
Policies are exactly `IMMUTABLE_CHECK`, `EDITABLE_TEMPLATE`, or
`LEARNER_SCAFFOLD`. Duplicate normalized paths, case or Unicode aliases, unknown fields, duplicate JSON
fields, wrong field types, malformed facts, and trailing documents are rejected.
Unsupported format versions and invalid documents remain untouched. The document
limit is 1 MiB; individual inspected or disclosed assets are limited to 16 MiB.

`WorkspaceFiles.read` returns bounded bytes for one requested regular file,
or absence when a path segment is missing. It opens the file relative to a
verified secure directory handle, refuses symlinks, checks entry and parent
identity after reading, and closes the channel and handle. Validation reuses
this boundary for required artifacts and compiled main classes. See the
[lesson check contract](lesson-check-contract.md) for asset-policy feedback and
cumulative validation.

`WorkspaceFiles.writeNewSafely` reserves each learner destination with
directory-relative `CREATE_NEW` through its verified secure parent handle, then
writes and flushes that channel. A file appearing after preflight makes creation
fail without replacing it. Bytes become visible during writing: the operation
promises confinement and exclusivity, not atomic visibility. Failures and crashes
retain partial or ambiguous targets for disclosure-journal recovery: public Java
APIs cannot prove that the target name still identifies the opened channel after
a concurrent replacement. Such a target is a conflict, never permission to
overwrite or delete it.

Tool-owned manifest writes create and flush a same-directory temporary file
through the verified secure handle. For an absent manifest, initialization
reserves its name with directory-relative `CREATE_NEW`, then writes and flushes
the reserved channel. A manifest appearing after validation is preserved byte
for byte. Initial bytes become visible during writing. On initialization failure,
the target is retained: public Java APIs cannot prove that its directory-entry
identity still belongs to the opened channel after concurrent replacement.
Partial or ambiguous entries are preserved for explicit recovery, never
automatically deleted or overwritten. Invalid partial documents are rejected
on load or retry. Existing validated state
is replaced by an atomic move through that same handle. Unsupported atomic
replacement fails while preserving the previous manifest. There is no pathname
move or destructive replacement fallback.

Successful learner asset publication and initial manifest creation require the
target to contain exactly the requested asset bytes or encoded manifest bytes.
After writing and flushing, the target is read through the same secure directory
handle with symlink following disabled. This comparison reads at most the expected
length plus one byte, bounded by the document or asset size limit plus one byte.
Parent and entry identities are checked around the comparison. A mismatch reports
`PUBLICATION_FAILED` and preserves the target; a replacement with identical bytes
can succeed because the persisted content is the same.

Temporary cleanup verifies the original file identity through an open secure directory
handle. It preserves foreign replacements and never deletes through a replacement
parent symlink. Providers without secure handles, stable directory/regular-file
keys, or flushable channels report `UNSUPPORTED_PUBLICATION`. Regular-file key
support is checked with an owned temporary before reserving a learner destination.
If a missing key prevents safe cleanup, that temporary is retained.

Creation, writes, replacement, and cleanup use the captured parent directory
handle. Directory identities are checked before and after these operations.
Replacing the parent pathname cannot redirect an asset write or metadata move to
an outside directory; the operation reports the changed workspace as a failure.
This boundary is intended for local learner experimentation.

## Disclosure transactions and recovery

`.fruit-and-faults/transition.json` holds one immutable version-one plan before
the first learner asset is created. Publication order is journal, ordered assets,
ownership manifest, then progress. Progress is the transaction's commit marker.
After verifying the complete asset set, intended ownership, and intended progress,
the CLI removes the exact journal through a verified secure directory handle.

The journal contains exactly these fields:

- `formatVersion`: `1`;
- `fromLessonId`: the prior active lesson ID, or `null` for initial disclosure
  or an appended continuation after a completed historical route;
- `toLessonId`: the exact lesson being opened;
- `assets`: ordered manifest-style path, asset ID, SHA-256, lesson ID, and policy
  declarations matching the installed lesson;
- `expectedManifestVersion`: `1`;
- `expectedManaged`: the complete prior versioned manifest, or `null` when absent;
- `expectedProgress`: the complete prior versioned progress, or `null` when absent;
- `intendedProgress`: the complete versioned progress to commit last.

The intended manifest is derived by adding new asset declarations once to the
prior manifest. Journal content contains identities and state facts, never raw
learner source or asset content. Recovery loads bytes from the matching installed
course bundle rather than accepting bytes supplied by the journal. The whole
journal is limited to 1 MiB. Strict JSON validation rejects duplicate or unknown
fields, coercions, trailing documents, incompatible course content, and invalid
transitions. Malformed and future versions remain untouched and produce a typed
diagnostic. Repair or restore the journal, or install compatible course content,
before retrying; the CLI does not silently replace it.

An append-compatible transition can retain an older content version in
`expectedProgress` and commit the installed version in `intendedProgress`.
Both definitions must be explicitly supported trusted snapshots. Prior hints,
answers, and learner files remain unchanged, and the rebase is committed only
with successful disclosure. The [advance contract](lesson-advance-contract.md)
describes version resolution and continuation confirmation.

Recovery first requires progress and manifest to equal their exact prior or
intended snapshots. Committed progress requires the intended manifest. It then
preflights every target before creating any missing asset. A journal-attributed
regular file with the expected SHA-256 is already applied; an absent destination
is created exclusively; partial, different, foreign, or unsafe content stops
recovery and remains untouched. Move a conflicting file aside explicitly after
reviewing it, then retry recovery. An unresolved conflict retains the journal
and does not advance progress.

Assets and state are checked again before committing progress and removing the
journal. Repeating recovery or the same completed application cannot duplicate
ownership or progress. A crash after progress publication leaves a journal that
can be verified and removed without writing progress again. A failed initial
journal or manifest write may leave an invalid partial metadata document; it is
preserved for explicit repair rather than overwritten automatically.
