# Workspace files and ownership manifest

Workspace destinations are logical slash-separated relative paths. They must
already be normalized: empty paths, empty segments, `.`, `..`, Unix absolute
paths, Windows drive/UNC paths, backslashes, colons, and control characters are
rejected. Spaces and Cyrillic names are supported.

Phase A requires an existing real workspace directory. It rejects every symlink
within that workspace, including links pointing back inside it. A blocked path
must be moved aside or replaced with a real directory before disclosure. Case
and Unicode-normalization aliases are conservatively rejected on every host,
so a course remains portable between case-sensitive and case-insensitive
filesystems. A requested file cannot also be another asset's parent directory.

The complete disclosure is preflighted before any target or parent directory is
created. An existing unknown file conflicts even if its bytes match the course
asset. A file is already applied only when its recorded asset ID, lesson ID,
original SHA-256, exact policy, and current bytes all match. The manifest never
authorizes restoring or overwriting learner changes.

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

`WorkspaceFiles.writeNewSafely` reserves each learner destination with
directory-relative `CREATE_NEW` through its verified secure parent handle, then
writes and flushes that channel. A file appearing after preflight makes creation
fail without replacing it. Bytes become visible during writing: the operation
promises confinement and exclusivity, not atomic visibility. An ordinary failure
removes only the identified created entry; a crash may leave a partial target
for disclosure-journal recovery. Such a target is a conflict, never permission
to overwrite it.

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

Cleanup verifies the original file identity through an open secure directory
handle. It preserves foreign replacements and never deletes through a replacement
parent symlink. Providers without secure handles, stable directory/regular-file
keys, or flushable channels report `UNSUPPORTED_PUBLICATION`. Regular-file key
support is checked with an owned temporary before reserving a learner destination.
If a missing key prevents safe cleanup, that temporary is retained. Multi-file
crash recovery belongs to the disclosure journal, which is implemented separately.

Creation, writes, replacement, and cleanup use the captured parent directory
handle. Directory identities are checked before and after these operations.
Replacing the parent pathname cannot redirect an asset write or metadata move to
an outside directory; the operation reports the changed workspace as a failure.
This boundary is intended for local learner experimentation.
