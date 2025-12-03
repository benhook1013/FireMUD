# World Editing & Customization Tools

This document describes the tools provided by the **Game Design Service** for editing game worlds.

Game creators use these interfaces to craft rooms, items and NPCs without modifying FireMUD's source code.  All edits are versioned and tied to a tenant so multiple projects can coexist.

## Capabilities

- **Room & Region Editor** – create regions, zones and rooms with exits and environmental settings. The editor saves data through the Game Design Service, which calls the World Management Service’s design APIs to update versioned world records for the target `tenantId` and draft `version_id`.
- **Entity Designer** – define NPCs, items and equipment with validation rules. Entities are stored as versioned records in the Entity Management Service and associated with draft or published versions by `version_id` during design and publish workflows.
- **Import/Export** – designers can upload JSON files representing rooms or entities for bulk editing.  Exporting a version provides the same format for external tools.

## Workflow

1. Use the web UI to modify rooms, items or NPC definitions.
2. Each change is stored as a **revision** linked to the author's account.
3. Revisions are grouped into a **version** and published via the saga workflow described in [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md).
4. Domain services load their own versioned data strictly by `version_id` at runtime; the Game Design Service is not queried during gameplay.

## Related Documentation

- [Game Design Service Architecture](README.md)
- [System Architecture – Transactions](../../system-architecture-transactions.md)
- [User Journeys – World and Entity Design](../../user-journeys.md#3-world-and-entity-design)
