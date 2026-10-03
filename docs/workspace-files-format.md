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
`LEARNER_SCAFFOLD`. Duplicate normalized paths, unknown fields, duplicate JSON
fields, wrong field types, malformed facts, and trailing documents are rejected.
Unsupported format versions and invalid documents remain untouched. The document
limit is 1 MiB; individual inspected or disclosed assets are limited to 16 MiB.

New files are flushed to a temporary file in their destination directory, then
published by an exclusive hard link. If another file appears after preflight,
publication fails without replacing it. Filesystems without this primitive fail
safely; the adapter never substitutes an overwriting asset move. Tool-owned
manifest updates use atomic replacement where supported and a complete-file
replacement move otherwise. The fallback does not promise crash-atomic
replacement on every filesystem.

Temporary cleanup verifies the original file identity through an open secure
directory handle. It preserves foreign replacements and never deletes through a
replacement parent symlink. If the provider cannot verify cleanup safely, the
temporary file is retained. Multi-file crash recovery belongs to the disclosure
journal, which is implemented separately.

Directory identities are checked before publication and metadata replacement.
The Java filesystem API has no public directory-relative hard-link primitive;
an adversarial simultaneous parent rename between a pathname check and a
filesystem call remains a limitation. Do not concurrently rename workspace
directories while the CLI accesses them. This boundary is intended for local
learner experimentation, not isolation from a hostile concurrent process.
