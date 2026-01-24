# World Editing & Customization Tools

This document describes the tools provided by the **Game Design Service** for editing game worlds.

Game creators use these interfaces to craft rooms, items and NPCs without modifying FireMUD's source code. All edits are versioned and tied to a tenant so multiple projects can coexist. The Game Design Service owns **history and metadata** (versions, branches, revisions) but does not store a separate, authoritative copy of full world or entity graphs; those remain in the owning domain services.

## Capabilities

- **Room & Region Editor** – create regions, zones and rooms with exits and environmental settings. The editor saves data through the Game Design Service, which calls the World Management Service’s design APIs to update versioned world records for the target `tenantId` and draft `version_id`.
- **Entity Designer** – define NPCs, items and equipment with validation rules. Entities are stored as versioned records in the Entity Management Service and associated with draft or published versions by `version_id` during design and publish workflows.
- **Import/Export** – designers can upload JSON files representing rooms or entities for bulk editing.  Exporting a version provides the same format for external tools.

## Workflow

1. Use the web UI to modify rooms, items or NPC definitions.
2. Each change is stored as a **revision** linked to the author's account and
   associated with concrete domain objects (rooms, regions, NPCs, items) via
   stable identifiers defined by the owning domain services.
3. As revisions are committed, the Game Design Service applies them
   incrementally to domain services’ **Draft** template rows via idempotent
   design APIs keyed by `(tenantId, versionId)`. Draft templates in World
   Management and Entity Management are therefore the authoritative snapshots of
   world and entity data for each version.
4. Revisions are grouped into a **version** and published via the Saga workflow
   described in [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md).
   At publish time, the Saga validates the Draft templates already stored in
   the domain services and transitions the version to Published; it does not
   copy a separate design database into those services.
5. Domain services accept design-time writes only for **Draft** versions. When
   a version transitions to the Published state, their template tables for that
   `(tenantId, versionId)` become read-only; further changes must target a new
   Draft version.
6. Domain services load their own versioned data strictly by `version_id` at
   runtime; the Game Design Service is not queried during gameplay. Commit and
   revision history remains anchored in the Game Design Service even though
   domain services store the versioned templates.

When domain templates for a `(tenantId, versionId)` are temporarily out of sync
with the revision set recorded in the Game Design Service (for example due to
transient failures when calling design APIs), the version’s `designSyncStatus`
is marked `OUT_OF_SYNC` and a reconciliation process replays the canonical
revisions until domain services report the expected template shape. Versions
must be `IN_SYNC` before they can be published.

## Related Documentation

- [Game Design Service Architecture](README.md)
- [System Architecture – Transactions](../../system-architecture-transactions.md)
- [User Journeys – World and Entity Design](../../user-journeys-creators.md#2-world-and-entity-design)
