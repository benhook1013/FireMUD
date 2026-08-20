# World Editing & Customization Tools

This document describes the tools provided by the **Game Design Service** for editing game worlds.

Game creators use these interfaces to craft rooms, items and NPCs without modifying FireMUD's source code. All edits are versioned and tied to a tenant so multiple projects can coexist. The Game Design Service owns **history and metadata** (versions, branches, revisions) but does not store a separate, authoritative copy of full world or entity graphs; those remain in the owning domain services.

## Implementation Status

The current editor contract preserves revision ordering, approval, and local conflict reporting, but complete destructive-preview UI, plan-digest and reference-analysis proof, and identity-mapping proof remain incomplete. The current `SaveRevision` path also does not prove the durable multi-owner Draft coordination contract in [ADR 0129](../../decisions/adr-0129-durable-fenced-multi-owner-draft-commits.md).

## Capabilities

- **Room & Region Editor** – create regions, zones and rooms with exits and environmental settings. The editor saves data through the Game Design Service, which calls the World Management Service’s design APIs to update versioned world records for the target `tenantId` and draft `version_id`.
- **Entity Designer** – define NPCs, items and equipment with validation rules. Entities are stored as versioned records in the Entity Management Service and associated with draft or published versions by `version_id` during design and publish workflows.
- **Import/Export (deferred)** – bulk JSON import/export is not part of the canonical initial slice. Designers edit drafts through the web UI and service-owned design APIs; any future import/export contract must be specified explicitly before the docs present it as a supported capability.

Current item-authoring note:

- item templates now carry an explicit `stackable` authored capability;
- this is only the authoring seam for future fungible quantity behavior, not live runtime merge/split behavior;
- equipment, containers, and other stateful items should remain authored non-stackable unless a later slice deliberately proves they are safe to treat as fungible.

Current equipment-authoring model:

- equipment slots are authored per `(tenantId, versionId)` as stable slot keys such as `HEAD`, `BACK`, `TAIL_RING`, or any other game-defined term;
- slot definitions can carry an optional slot group key so templates can say "this item fits any slot in this group" without making the platform own universal humanoid anatomy;
- runtime body layouts are authored as sets of allowed slot keys, and characters carry a `bodyLayoutKey` that selects which slot set applies;
- item templates keep a default `equipmentSlot` for the first runtime command loop and may also declare an `equipmentSlotGroupKey` compatibility guard;
- if no slot/body-layout schema has been authored for a version, Entity Management allows the legacy direct slot string as a bootstrap fallback, but once a schema exists the runtime validates slot existence, item compatibility, and body-layout membership before equipment binds.

Illustrative generation/revision sequence:

1. Create or update a generation revision for a declared target scope.
2. Publish the revision under either `REPLACE_SCOPE` or `SEED_APPEND_ONLY`.
3. Apply later manual edits in Game Design revision history.
4. On replay or regeneration, later manual edits remain authoritative unless a later generation revision explicitly declared `REPLACE_SCOPE` for that same scope.

Example consequence:

- `SEED_APPEND_ONLY` may add scaffold content but must fail as `OUT_OF_SYNC` if replay would require deleting or rewriting later manual edits.
- `REPLACE_SCOPE` may overwrite prior authored topology inside its declared scope, so creators must treat it as an explicit destructive regeneration boundary rather than a passive refresh.

## Workflow

1. Use the web UI to modify rooms, items or NPC definitions.
2. Each change is stored as a **revision** linked to the author's account and
   associated with concrete domain objects (rooms, regions, NPCs, items) via
   stable identifiers defined by the owning domain services.
3. As revisions are committed, Game Design durably records the exact base commit, canonical request or proposal digest, complete affected aggregates/scopes and expected epochs, revision order, and per-owner apply status before coordinating idempotent owner writes. World Management and Entity Management remain authoritative for their Draft template graphs, while Game Design owns the fully synchronized commit fence.
4. Revisions are grouped into a **version** and published via the durable workflow
   described in [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md).
   At publish time, the workflow validates the Draft templates already stored in
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

### Draft Write Concurrency

Durable cross-owner application does not permit last-writer-wins mutation of Draft templates or make a partially applied commit accepted Draft truth. See [ADR 0129](../../decisions/adr-0129-durable-fenced-multi-owner-draft-commits.md).

Initial-slice concurrency contract:

- Each design-time mutation against a domain-owned Draft aggregate must carry:
  - stable `revisionId`;
  - containing `commitId`;
  - the exact commit/request digest;
  - the target `(tenantId, versionId)`;
  - an `expectedDraftRevisionEpoch` (or equivalent monotonic aggregate version) for the aggregate being edited.
- Each complete commit or proposal must also bind its exact `baseCommitId`, canonical revision order, every affected owner/aggregate/scope, and the expected epoch for every affected mutation.
- World topology edits and generation revisions that can touch more than one room use an explicit scope aggregate keyed by `(tenantId, versionId, scopeType, scopeId)`, for example `REGION_SUBTREE:<regionTemplateId>` or `ZONE_SUBTREE:<zoneTemplateId>`. Manual edits inside that scope and generation revisions targeting that scope must check and advance the same `draftScopeRevisionEpoch`.
- The scope epoch is the canonical conflict boundary for subtree generation. A room-level edit may still carry a room aggregate epoch for precise UI conflict messages, but it must also validate the containing scope epoch whenever the edit changes topology or publish-visible room semantics inside a generation-addressable scope.
- World Management and Entity Management design APIs must reject the write with a conflict error if the expected epoch does not match the current Draft aggregate state.
- Every owner must derive or validate the complete scope set required by its typed mutation and reject an omitted containing scope. Epoch comparison, local mutation, epoch advancement, and exact commit/digest ledger recording must occur in one owner-local storage transaction; a service-layer read followed by an unconditional update is not sufficient.
- Replays of the same `revisionId` are idempotent only when the complete enclosing binding matches exactly; a duplicate delivery with the same already-applied revision and exact binding may return `NO_OP_ALREADY_APPLIED`, but changed or omitted binding is rejected before owner-local apply.
- Reusing a commit, request, proposal, or revision identity with a different canonical input or mutation, `baseCommitId`, canonical revision order where applicable, complete affected owner/aggregate/scope set, complete expected epoch set, or digest is rejected rather than treated as a replay. Only a replay whose complete bindings match exactly is idempotent. An owner-local apply does not advance the shared Draft fence until every required owner reports the exact commit and digest.
- Game Design must surface the conflict to the editor as a Draft-write concurrency failure, not silently overwrite the newer state.
- Publish reconciliation replays commits in commit order and revision order within the commit. It must not reorder concurrent conflicting edits into a synthetic merged result.

Generation-specific conflict rules:

The canonical replay, replacement, preview, reference, identity, and no-merge contract is [Explicit Destructive Regeneration with Previewed Scope](../../decisions/adr-0101-explicit-destructive-regeneration-with-previewed-scope.md) and the [Procedural Generation](../../system-architecture-procedural-generation.md#request-bounded-replay-and-explicit-regeneration) system contract.

The generation preview, revision, and CAS rules below are target-state requirements, not claims of live behavior. Current World-side generation status remains summarized in [World Management Procedural Generation Control](../world-management-service/procedural-generation-control.md#implementation-status).

- The editor presents the exact `REPLACE_SCOPE` preview, including creates, retained objects, replacements, deletions, affected references, identity mappings, and blockers, and obtains creator approval before recording the generation revision.
- The revision payload records `generationRequestId`, immutable `generatorImplementationVersion`, the exact canonical generation inputs (including schema version and seed), canonical generated-output `outputDigest`, canonical plan digest, expected `draftScopeRevisionEpoch`, required mappings, and approved reference facts. Any request-identity, implementation, input, output-digest, scope-epoch, or relevant reference-fact drift makes the plan stale and requires a fresh preview and approval rather than replacing newer manual edits.
- `SEED_APPEND_ONLY` remains the editor’s non-destructive path, but World Management must reject it as `OUT_OF_SYNC` or a more specific generation conflict if deterministic replay would rewrite or delete authored rows.
- Historical replay preserves later manual revisions. A destructive regeneration is a new approved revision; the old revision does not authorize fresh deletion.
- Game Design forwards the approved typed `WORLD_GENERATION_SUBTREE` payload through `ApplyWorldDesignMutation`; generated subtree content is not stored only as opaque revision JSON. World Management includes `generationRequestId`, immutable `generatorImplementationVersion`, the exact canonical inputs, `outputDigest`, plan digest, scope epoch, and approved reference facts in its owner-local CAS, fails closed and leaves prior topology unchanged when any differ from the preview, while Game Design surfaces the conflict and records the apply outcome.
- A generation revision targeting a newly created empty container with no prior scope epoch initializes its scope epoch with the generated topology. An existing scope emptied by deletion or replacement preserves its monotonic epoch; later edits and generation revisions use that epoch.

The following examples are illustrative, non-admissible owner-local pseudocode fragments beneath the enclosing Game Design coordinator record; they cannot be submitted standalone. The coordinator binds the exact commit/request digest and complete affected owner/aggregate/scope set. `NO_OP_ALREADY_APPLIED` is valid only after exact complete-binding replay equality; the same identity with any changed or omitted binding is rejected as changed-request reuse before owner-local apply. These fragments do not define that complete multi-owner wire shape or add fields beyond the owner-local epoch/apply result.

Illustrative owner-local apply fragments:

```json
{
  "request": {
    "tenantId": "11111111-1111-4111-8111-111111111111",
    "versionId": "22222222-2222-4222-8222-222222222222",
    "commitId": "c901",
    "revisionId": "r-room-12",
    "aggregateType": "ROOM_TEMPLATE",
    "aggregateId": "roomTemplateId:inn-foyer",
    "expectedDraftRevisionEpoch": 7
  },
  "response": {
    "result": "APPLIED",
    "newDraftRevisionEpoch": 8
  }
}
```

```json
{
  "request": {
    "tenantId": "11111111-1111-4111-8111-111111111111",
    "versionId": "22222222-2222-4222-8222-222222222222",
    "commitId": "c901",
    "revisionId": "r-room-12",
    "aggregateType": "ROOM_TEMPLATE",
    "aggregateId": "roomTemplateId:inn-foyer",
    "expectedDraftRevisionEpoch": 7
  },
  "response": {
    "result": "NO_OP_ALREADY_APPLIED",
    "currentDraftRevisionEpoch": 8
  }
}
```

```json
{
  "request": {
    "tenantId": "11111111-1111-4111-8111-111111111111",
    "versionId": "22222222-2222-4222-8222-222222222222",
    "commitId": "c902",
    "revisionId": "r-room-13",
    "aggregateType": "ROOM_TEMPLATE",
    "aggregateId": "roomTemplateId:inn-foyer",
    "expectedDraftRevisionEpoch": 7
  },
  "error": {
    "code": "DRAFT_WRITE_CONFLICT",
    "message": "Draft aggregate has advanced to epoch 8.",
    "currentDraftRevisionEpoch": 8
  }
}
```

If a later implementation introduces multi-branch merging semantics, that workflow must be specified explicitly. Until then, the canonical first-slice rule is optimistic concurrency plus deterministic replay order, not implicit merge behavior.

When domain templates for a `(tenantId, versionId)` are temporarily out of sync
with the revision set recorded in the Game Design Service (for example due to
transient failures when calling design APIs), the version’s `designSyncStatus`
is marked `OUT_OF_SYNC` and a reconciliation process replays the canonical
revisions until domain services report a matching draft digest for that scope. Each participating domain service exposes a read-only `GetDraftDesignDigest` API with a typed scope selector (`oneof {versionId, scriptPatchVersion}`) and returns at minimum:

- `tenantId`, and exactly one scope key (`versionId` or `scriptPatchVersion`)
- `appliedCommitId`, meaning the highest commit whose full revision set has been durably applied for that scope
- `contentDigest` (a stable hash of the service’s Draft template graph relevant to publishing)
- `digestSchemaVersion` so hash semantics can evolve without ambiguity

Services may keep revision-granularity ledgers internally for replay and diagnostics, but publish gating and reconciler comparisons must use `appliedCommitId` only. A participant must not expose only `lastAppliedRevisionId` as its convergence token for a multi-revision commit.

`designSyncStatus` must transition to `OUT_OF_SYNC` whenever publish-affecting generation inputs for a target version change. Such inputs must be changed through Game Design-controlled Draft workflows and committed like any other design asset; mutable World Management operational defaults are not allowed to alter the effective Draft graph for a version.

Canonical digest RPC contract:

- Request: `GetDraftDesignDigestRequest { tenantId, scope: oneof { versionId, scriptPatchVersion } }`
- Response: `GetDraftDesignDigestResponse { tenantId, scope, appliedCommitId, contentDigest, digestSchemaVersion }`
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

- After all required digest participants pass and asset export has produced the final `manifestHash`, Game Design writes `published_release_bundle(tenantId, versionId, commitId, publishWorkflowId, participantDigests..., artifactDigests..., requiredManifestAssetKeys..., manifestHash, generationConfigRevision, publishedAt)`.
- This row is the canonical record proving what was actually published.
- Activation, repair, and rollback-preflight workflows must validate against this attestation instead of reconstructing release state from multiple service-local sources.
- Game Design must expose this attestation through a read-only API such as `GetPublishedReleaseBundle(tenantId, versionId)` so runtime and operator workflows never depend on direct table access.

For initial-slice releases that export derived world artifacts, the attestation must also carry typed `artifactDigests[]` entries for those payloads and `requiredManifestAssetKeys[]` for any stable manifest usage keys that are mandatory for launch/cutover validation, in addition to `participantDigests[]` and `manifestHash`.

For exported world bundles in the initial slice, these `artifactDigests[]` and `requiredManifestAssetKeys[]` entries are mandatory fields of the release attestation rather than optional extensions.

### Implementation Checklist

Use this checklist when wiring the first implementation slice for world and content authoring:

1. Apply design revisions only to Draft domain templates keyed by `(tenantId, versionId)`.
2. Enforce optimistic concurrency on Draft aggregate writes and no-op replay on duplicate `revisionId`.
3. Mark versions `OUT_OF_SYNC` or `UNRESOLVED_REFERENCE` instead of treating failed cross-service application as healthy Draft state.
4. Gate full publish on documented participant digests plus the Game Design control-plane digest.
5. Persist `published_release_bundle` before treating a version as publish-complete.
6. Resolve one immutable launch descriptor before any persistent instance rows are created.
7. Verify `GetTemplateReferencePhase == ENFORCED` and validate `GetPublishedReleaseBundle` before launch.
8. Drive world creation only from the resolved descriptor and attested `generationConfigRevision`.
9. Treat design-time generation as an explicit revision with declared scope and replacement policy.
10. Replay generation revisions and later manual edits in original order; fail Draft convergence rather than silently erasing authored changes.

Authoritative schema source rule:

- The concrete gRPC field names and enums for `GetDraftDesignDigest`, `GetDesignControlPlaneDigest`, `GetPublishedReleaseBundle`, `ResolveLaunchDescriptor`, and conflict/error surfaces are authoritative only once they exist in the service proto definitions.
- The JSON examples in this document define required semantics and minimum payload content, but implementers should treat the proto/OpenAPI files as the final schema source when wiring clients and servers.

When digest semantics evolve, the system must follow the explicit “Digest Schema Migration” workflow described in `design/architecture/microservices/game-design-service/version-control.md` so publish gating never compares incompatible hashes.

The Game Design Service reconciler records the per-service digest it observed for a given `commitId` when that commit was last applied successfully, and later compares current digests against those recorded values. Publish-time validation must require that all participating services report `appliedCommitId == commitId` and `contentDigest` equal to the recorded digest for that commit.

Participant selection is explicit by publish type and must follow the matrix in `design/architecture/microservices/game-design-service/version-control.md#digest-participants-by-publish-type`:

- Full `PublishVersion`: World Management, Entity Management, Game Logic, and Automation & Scripting must each pass `GetDraftDesignDigest`, and Game Design must pass `GetDesignControlPlaneDigest`.
- `PublishScriptPatchVersion`: Automation & Scripting must pass `GetDraftDesignDigest` for the patch graph and Game Design must pass `GetDesignControlPlaneDigest` for patch metadata/wiring; world/entity/game-logic template digests are not re-gated for that publish operation.

For end-to-end persistence review, every publish-gate participant must document:

- the version-scoped data it persists;
- the digest manifest that attests that data;
- whether it is a digest-only participant or also a workflow-step participant during full publish.

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

Illustrative unresolved-reference example:

```json
{
  "request": {
    "tenantId": "11111111-1111-4111-8111-111111111111",
    "versionId": "22222222-2222-4222-8222-222222222222",
    "commitId": "c903",
    "revisionId": "r-spawn-17",
    "bindingType": "WORLD_ENTITY_SPAWN_BINDING",
    "roomTemplateId": "roomTemplateId:graveyard-gate",
    "entityTemplateId": "entityTemplateId:skeleton-captain"
  },
  "validationResult": {
    "versionStatus": "UNRESOLVED_REFERENCE",
    "failures": [
      {
        "owningService": "entity-management",
        "referencedIdentifier": "entityTemplateId:skeleton-captain",
        "constraint": "ENTITY_TEMPLATE_NOT_FOUND_FOR_VERSION",
        "lastValidatedAt": "2026-03-19T09:15:00Z"
      }
    ]
  }
}
```

This version remains publish-blocked until the referenced entity template exists
for the same `(tenantId, versionId)` or the binding revision is replaced.

Where synchronous reference validation is available, Game Design should reject the commit before it becomes durable. Where synchronous validation is temporarily unavailable, the version must still enter an explicit invalid state rather than a generic Draft state.

## Cross-Service Reference Invariants

World and entity templates owned by domain services must reference each other using stable, normalized identifiers rather than ad hoc fields in opaque JSON blobs:

- World layouts refer to NPCs, items, equipment, and other entities only via stable identifiers exposed by the Entity Management Service (for example `entity_template_id` or equivalent), always scoped and versioned by `(tenantId, versionId)`.
- Population rules and other design-time bindings between world regions/rooms and entity templates are stored via normalized join tables (for example `world_entity_template` or generation binding tables) owned by the relevant domain services, not inferred from partial JSON in Game Design payloads.
- Scripts and automation hooks are likewise referenced via explicit identifiers or normalized relations defined by the Automation & Scripting Service, rather than embedded directly in Game Design configuration blobs as canonical data.
- Design-time population generation may create only declarative World-owned spawn/population binding rows under `(tenantId, versionId)`. Automation & Scripting may validate or later consume those bindings, but it must not persist template topology, spawn bindings, or live entities as a side effect of a design-time generation revision.
- Runtime population is separate: after a game instance is prepared or during later runtime instancing, Automation & Scripting may emit runtime commands through the canonical tick/workflow handoff. Those commands act on `RoomInstanceRef` and entity/runtime state, not on Draft template rows.

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
- [User Journeys – World and Entity Design](../../../product/user-journeys/creators.md#2-world-and-entity-design)
