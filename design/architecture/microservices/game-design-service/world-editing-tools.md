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
revisions until domain services report a matching draft digest for that scope. Each participating domain service exposes a read-only `GetDraftDesignDigest` API with a typed scope selector (`oneof {versionId, scriptPatchVersion}`) and returns at minimum:

- `tenantId`, and exactly one scope key (`versionId` or `scriptPatchVersion`)
- `appliedCommitId` (or `lastAppliedRevisionId` if the service applies at revision granularity)
- `contentDigest` (a stable hash of the service’s Draft template graph relevant to publishing)
- `digestSchemaVersion` so hash semantics can evolve without ambiguity

`designSyncStatus` must transition to `OUT_OF_SYNC` whenever publish-affecting generation inputs for a target version change. Such inputs must be changed through Game Design-controlled Draft workflows and committed like any other design asset; mutable World Management operational defaults are not allowed to alter the effective Draft graph for a version.

Canonical digest RPC contract:

- Request: `GetDraftDesignDigestRequest { tenantId, scope: oneof { versionId, scriptPatchVersion } }`
- Response: `GetDraftDesignDigestResponse { tenantId, scope, appliedCommitId or lastAppliedRevisionId, contentDigest, digestSchemaVersion }`
- Services that do not support a scope (for example `scriptPatchVersion` for World/Entity) must return `UNSUPPORTED_SCOPE`; they must not silently reinterpret scope fields.

Game Design also computes a control-plane digest over normalized publish-critical metadata (for example `game_template_*_ref` and `version_asset`) and validates it in the same `designSyncStatus` gate using a dedicated read-only API (`GetDesignControlPlaneDigest`).

Digest semantics must be explicitly stable and testable:

- `contentDigest` covers all publish-scoped **template** and **binding** rows that participate in publish for the service’s reported scope (`(tenantId, versionId)` or `<tenantId, scriptPatchVersion>`). It must not include runtime/instance rows keyed by `gameInstanceId`, and it must not include audit/history tables.
- The digest input must be generated from a canonical, deterministic representation (for example, a stable JSON/Protobuf export) with:
  - Stable ordering (table/type ordering, then primary key ordering),
  - Stable field selection and encoding, and
  - Explicit exclusion of non-semantic fields such as `created_at`, `updated_at`, and other write-time metadata that should not block publish.
- `digestSchemaVersion` increments only when the canonicalization rules or included domain objects change, and publish tooling must refuse to compare digests computed under different schema versions.

Each participating domain service must publish a service-local **digest input manifest** (in its architecture docs) that explicitly lists:

- Included tables/relations and key fields that contribute to `contentDigest`.
- Explicit exclusions (timestamps, audit/history rows, runtime/instance tables).
- Canonical ordering and serialization rules.
- Conditions that require bumping `digestSchemaVersion`.

Publish gating should be treated as invalid if a service cannot provide a digest payload consistent with its documented manifest for the active `digestSchemaVersion`.

Publish completion must also persist an immutable release attestation in Game Design:

- After all required digest participants pass and asset export has produced the final `manifestHash`, Game Design writes `published_release_bundle(tenantId, versionId, commitId, publishWorkflowId, participantDigests..., manifestHash, generationConfigRevision, publishedAt)`.
- This row is the canonical record proving what was actually published.
- Activation, repair, and rollback-preflight workflows must validate against this attestation instead of reconstructing release state from multiple service-local sources.
- Game Design must expose this attestation through a read-only API such as `GetPublishedReleaseBundle(tenantId, versionId)` so runtime and operator workflows never depend on direct table access.

When digest semantics evolve, the system must follow the explicit “Digest Schema Migration” workflow described in `design/architecture/microservices/game-design-service/version-control.md` so publish gating never compares incompatible hashes.

The Game Design Service reconciler records the per-service digest it observed for a given `commitId` when that commit was last applied successfully, and later compares current digests against those recorded values. Publish-time validation must require that all participating services report `appliedCommitId == commitId` and `contentDigest` equal to the recorded digest for that commit.

Participant selection is explicit by publish type and must follow the matrix in `design/architecture/microservices/game-design-service/version-control.md#digest-participants-by-publish-type`:

- Full `PublishVersion`: World Management, Entity Management, Game Logic, and Automation & Scripting must each pass `GetDraftDesignDigest`, and Game Design must pass `GetDesignControlPlaneDigest`.
- `PublishScriptPatchVersion`: Automation & Scripting must pass `GetDraftDesignDigest` for the patch graph and Game Design must pass `GetDesignControlPlaneDigest` for patch metadata/wiring; world/entity/game-logic template digests are not re-gated for that publish operation.

For end-to-end persistence review, every publish-gate participant must document:

- the version-scoped data it persists;
- the digest manifest that attests that data;
- whether it is a digest-only participant or also a saga-step participant during full publish.

Publish tooling must fail closed if a required participant lacks this documented persistence contract.

Routing contract for typed digest scopes:

- Full publish orchestration must issue digest requests with `scope.versionId` only to participants in the full-publish matrix.
- Script-only publish orchestration must issue digest requests with `scope.scriptPatchVersion` only to participants in the script-patch matrix.
- `UNSUPPORTED_SCOPE` is tolerated only for services outside the active participant set; for required participants it is a publish-blocking error.

Versions
must be `IN_SYNC` before they can be published.

## Draft Reference Validation

Eventually consistent design application is not permission to persist unresolved cross-service references as healthy Draft data.

- A draft write that introduces references to missing or schema-incompatible world/entity/script objects must be recorded as `UNRESOLVED_REFERENCE` or equivalent invalid-draft state, not as a normal in-sync Draft.
- Designers must see the unresolved dependency set explicitly (referenced identifier, owning service, failing constraint, last validation time).
- Publish is blocked for any version with unresolved references, even if replay/reconciliation later succeeds for unrelated services.
- Reconciliation may retry delivery of valid revisions, but it must not silently normalize or auto-rewrite broken identifiers.

Where synchronous reference validation is available, Game Design should reject the commit before it becomes durable. Where synchronous validation is temporarily unavailable, the version must still enter an explicit invalid state rather than a generic Draft state.

## Cross-Service Reference Invariants

World and entity templates owned by domain services must reference each other using stable, normalized identifiers rather than ad hoc fields in opaque JSON blobs:

- World layouts refer to NPCs, items, equipment, and other entities only via stable identifiers exposed by the Entity Management Service (for example `entity_template_id` or equivalent), always scoped and versioned by `(tenantId, versionId)`.
- Population rules and other design-time bindings between world regions/rooms and entity templates are stored via normalized join tables (for example `world_entity_template` or generation binding tables) owned by the relevant domain services, not inferred from partial JSON in Game Design payloads.
- Scripts and automation hooks are likewise referenced via explicit identifiers or normalized relations defined by the Automation & Scripting Service, rather than embedded directly in Game Design configuration blobs as canonical data.

Game Design Service may carry references to these identifiers in its revision history, branches, and commits, but canonical ownership of the underlying world, entity, and script schemas and identifiers always remains with the corresponding domain services.

At a high level, ownership for common cross-cutting templates is:

- **World Management Service** – owns world topology templates (regions, zones, rooms) and declarative population bindings such as spawn rules keyed by `(tenantId, versionId, regionTemplateId/roomTemplateId, entityTemplateId)`. These records define *where* and *under what conditions* entities can appear.
- **Entity Management Service** – owns entity templates (items, NPCs, equipment, loot-table definitions) keyed by `(tenantId, versionId, entityTemplateId or lootTableId)` and any normalized mappings between loot tables and item templates.
- **Automation & Scripting Service** – owns script and automation definitions keyed by `(tenantId, versionId, scriptId)` and exposes identifiers that world and entity templates can reference for triggers and behaviors.
- **Game Design Service** – orchestrates design-time editing of all of the above via design APIs, but does not define or store competing schema copies; it stores only revision and version metadata plus configuration payloads that reference domain-owned identifiers.

Schema changes or identifier migrations that affect these shared templates must follow the version-aware rules in [Database Migrations](../../system-architecture-database-migrations.md), using new template rows and migration workflows instead of repurposing existing identifiers while non-Retired versions still reference them.

## Related Documentation

- [Game Design Service Architecture](README.md)
- [System Architecture – Transactions](../../system-architecture-transactions.md)
- [User Journeys – World and Entity Design](../../user-journeys-creators.md#2-world-and-entity-design)
