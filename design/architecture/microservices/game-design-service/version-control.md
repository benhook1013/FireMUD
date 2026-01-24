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

### History and Provenance Across Services

The Game Design Service is the canonical history store for world and entity
content even though domain services own the runtime templates:

- Each revision and commit references concrete domain objects (rooms, regions,
  NPCs, items, templates) via stable identifiers.
- When a version is published, the service applies the corresponding revision
  set to downstream services such as World Management and Entity Management via
  idempotent design APIs so their template tables match the committed graphs.
- Domain services do not maintain their own commit histories; they expose only
  the current and historical versioned templates keyed by `(tenantId, versionId)`.

To audit the history of a room, NPC, or item, contributors query the Game
Design Service’s branches and commits and then correlate the resulting
revisions with the versioned templates stored in domain services.

## Benefits

- Designers can experiment on feature branches without affecting the main game line.
- Patch notes are automatically generated from commit messages.
- Downstream services continue to consume only published versions so runtime stability is preserved.

## Related Documentation

- [Game Design Service Architecture](README.md)
- [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md)
