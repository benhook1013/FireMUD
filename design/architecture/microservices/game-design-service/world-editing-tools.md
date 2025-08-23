# World Editing & Customization Tools

This document describes the tools provided by the **Game Design Service** for editing game worlds.

Game creators use these interfaces to craft rooms, items and NPCs without modifying FireMUD's source code.  All edits are versioned and tied to a tenant so multiple projects can coexist.

## Capabilities

- **Room & Region Editor** – create regions, zones and rooms with exits and environmental settings.  The editor saves data through the Game Design Service which then propagates it to the World Management Service when a version is published.
- **Entity Designer** – define NPCs, items and equipment with validation rules.  Entities are stored as design-time records and copied to runtime services by `version_id` during publishing.
- **Import/Export** – designers can upload JSON files representing rooms or entities for bulk editing.  Exporting a version provides the same format for external tools.

## Workflow

1. Use the web UI to modify rooms, items or NPC definitions.
2. Each change is stored as a **revision** linked to the author's account.
3. Revisions are grouped into a **version** and published via the saga workflow described in [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md).
4. Downstream services load the data strictly by `version_id` so the design database is never queried at runtime.

## 📚 Related Documentation

- [Game Design Service Architecture](README.md)
- [System Architecture – Transactions](../../system-architecture-transactions.md)
- [User Journeys – World and Entity Design](../../user-journeys.md#3-world-and-entity-design)
