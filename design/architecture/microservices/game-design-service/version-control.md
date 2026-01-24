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
  NPCs, items, templates) via stable identifiers maintained by the owning domain services.
- During authoring, design tools apply revisions incrementally to domain
  services’ **Draft** template rows via idempotent design APIs keyed by
  `(tenantId, versionId)`. Draft template graphs in World Management, Entity
  Management, and related services are therefore the authoritative snapshots of
  world and entity data for each version.
- When a version is published, the service coordinates a Saga that validates and
  finalizes the existing Draft data in each domain service and transitions the
  version to Published as described in
  [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md).
  No separate design database is copied into domain services at publish time.
- These design APIs accept writes only for Draft versions. Once a version is
  marked Published, the corresponding template rows in domain services are
  treated as immutable for that `(tenantId, versionId)`; further edits require
  creating a new Draft version and publishing it.
- Domain services do not maintain their own commit histories; they expose only
  the current and historical versioned templates keyed by `(tenantId, versionId)`.

To audit the history of a room, NPC, or item, contributors query the Game
Design Service’s branches and commits and then correlate the resulting
revisions with the versioned templates stored in domain services.

### Design-Time Synchronization

Because design changes often span multiple domain services (for example World
Management and Entity Management), the system treats the Game Design Service as
the source of truth for which revisions belong to a version, and the domain
services as the source of truth for the current Draft template graphs:

- Applying a commit is **eventually consistent** across services:
  - Revisions are written to the Game Design Service first.
  - Design-time workers or APIs apply those revisions to the owning domain
    services’ Draft templates via idempotent design APIs.
- The Game Design Service tracks a derived `designSyncStatus` for each
  `(tenantId, versionId)` indicating whether the known revision set has been
  fully applied to all participating domain services.
- A periodic reconciler in the Game Design Service replays the canonical
  revision set into domain services until their Draft templates converge on the
  expected state. This reconciler updates `designSyncStatus` back to `IN_SYNC`
  once all domain services acknowledge the expected template shape.
- The `PublishVersion` workflow must verify that `designSyncStatus == IN_SYNC`
  before starting the publish Saga. Versions that are out of sync cannot be
  published until reconciliation succeeds.

Future implementations may replace the reconciler with a dedicated design-time
Saga or outbox-driven workflow that provides stronger atomicity for commits
that touch multiple services. Regardless of implementation, the contract is
that publish-time validation observes a consistent set of Draft templates
matching the Game Design Service’s revision history.

### Asset References in History

Revisions and branches can reference uploaded assets (icons, audio, etc.) that
are managed by the Game Design Service:

- All references from revisions, commits, or branches to assets must be stored
  via a normalized join table such as `revision_asset` keyed by
  `(revision_id, asset_id)`.
- References from published versions to assets must go through the
  `version_asset` mapping described in
  [Asset Storage Setup](./asset-storage.md); domain services and runbooks must
  not infer asset reachability from ad hoc JSON fields.
- Asset retention and purge rules rely on `version_asset` plus `revision_asset`
  when determining whether a given `game_assets` row is still reachable from
  either a non-Retired version or any non-deleted revision/branch.

This normalized reference model ensures that cleanup jobs and operational
runbooks can safely determine which assets are still in use without scanning
opaque JSON blobs or service-specific payloads.

## Benefits

- Designers can experiment on feature branches without affecting the main game line.
- Patch notes are automatically generated from commit messages.
- Downstream services continue to consume only published versions so runtime stability is preserved.

## Related Documentation

- [Game Design Service Architecture](README.md)
- [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md)
