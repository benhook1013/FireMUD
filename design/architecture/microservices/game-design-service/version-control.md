# Version Control for Design Assets

Design assets are versioned to enable rollback and collaborative workflows. This document outlines how the Game Design Service integrates version control semantics.

## Approach

- Each asset revision already stores author and timestamp metadata.
- Publishing a version creates an immutable snapshot identified by `version_id`.
  Script-only fixes use a `scriptPatchVersion` tied to a `baseVersionId` so minor
  automation updates can go live without republishing all assets.
- To provide Git-style history, revisions are grouped under branches and commits stored in the database.
- The service exposes APIs to create branches, merge changes and list commit history.
- External Git repositories can be synchronized using webhook triggers for advanced workflows.

## Benefits

- Designers can experiment on feature branches without affecting the main game line.
- Patch notes are automatically generated from commit messages.
- Downstream services continue to consume only published versions so runtime stability is preserved.

## Related Design

- [Game Design Service Architecture](README.md)
- [Versioning & Runtime Configuration](../system-architecture-versioning-runtime.md)
