# Version Control for Design Assets

Design assets are versioned to enable rollback and collaborative workflows. This document outlines how the Game Design Service integrates version control semantics.

## Approach

- Each asset revision already stores author and timestamp metadata.
- Publishing a version creates an immutable snapshot identified by `version_id`.
  Script-only fixes use a `scriptPatchVersion` tied to a `baseVersionId` so minor
  automation updates can go live without republishing all assets.
- To provide Git-style history, revisions are grouped under branches and commits stored in the database.
- The service exposes APIs to create branches and list commit history. Canonical multi-branch merge semantics are deferred until explicitly specified; the first-slice model is optimistic concurrency plus deterministic replay order.
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
- Idempotency and replay safety are mandatory:
  - Each design-time write into a domain service is keyed by a stable `revisionId` (and, where relevant, `commitId`) and must be safe to retry.
  - Domain services persist an “applied revisions” ledger (or equivalent) per `(tenantId, versionId)` so duplicate deliveries do not produce duplicate rows or conflicting mutations.
  - Publish-time validation must be based on durable digests and applied-revision state, not on transient in-memory caches.
- Draft-write concurrency is explicit:
  - design-time mutations of Draft aggregates must use optimistic concurrency with an `expectedDraftRevisionEpoch` (or equivalent monotonic aggregate version) supplied by Game Design;
  - stale writes must fail with a conflict result and must not silently overwrite newer Draft state;
  - replay of an already-applied `revisionId` remains a no-op rather than a conflict;
  - reconciler replay follows canonical commit order and revision order within a commit; it does not invent merge results for conflicting user edits.
- The Game Design Service tracks a derived `designSyncStatus` for each
  `(tenantId, versionId)` indicating whether the known revision set has been
  fully applied to all participating domain services.
- A periodic reconciler in the Game Design Service replays the canonical
  revision set into domain services until their Draft templates converge on the
  expected state. Convergence is validated using a domain-owned digest contract:
  each participating domain service exposes a read-only `GetDraftDesignDigest`
  API with typed scope (`oneof {versionId, scriptPatchVersion}`) returning
  `appliedCommitId` plus a stable `contentDigest` and `digestSchemaVersion`.
  `appliedCommitId` is the highest commit whose complete revision set has been
  durably applied for that scope. Services may keep revision-level ledgers
  internally, but publish gates and reconciler comparisons must use
  commit-level convergence only. The reconciler updates `designSyncStatus` back
  to `IN_SYNC` once all participating services report digests matching the
  commit being published.
- The `PublishVersion` workflow must verify that `designSyncStatus == IN_SYNC`
  before starting the publish Saga. Versions that are out of sync cannot be
  published until reconciliation succeeds.
- Reconciliation does not authorize silently broken drafts:
  - commits introducing unresolved cross-service references must move the version into explicit invalid state (`UNRESOLVED_REFERENCE` or equivalent) rather than leaving it as a normal Draft;
  - replay workers may retry delivery, but they must not invent identifier rewrites or downgrade hard validation failures into generic `OUT_OF_SYNC`.

In addition to domain-service digests, publish safety requires a Game Design control-plane digest:

- Game Design computes a canonical digest over normalized dependency rows that affect launchability and publish immutability (for example `game_template_*_ref`, `version_asset`, and related version wiring metadata).
- This control-plane digest is recorded per commit/version scope and validated in the same gating pass as domain-service digests.
- Control-plane digest mismatch is treated exactly like a domain digest mismatch (`OUT_OF_SYNC`, publish blocked).
- The control-plane digest surface should be exposed through a read-only API (for example `GetDesignControlPlaneDigest`) so publish tooling uses a uniform participant contract.
- `GetDesignControlPlaneDigest` should return at minimum `{tenantId, versionId or scriptPatchVersion scope, appliedCommitId, contentDigest, digestSchemaVersion}` so publish gates compare like-for-like payloads across all participants.
- After all digest gates and asset export succeed, Game Design must persist a single immutable `published_release_bundle` attestation for `(tenantId, versionId)` that captures the final participant digests plus `manifestHash` and `generationConfigRevision`.
- Game Design must expose the attestation through `GetPublishedReleaseBundle(tenantId, versionId)` with deterministic response fields at minimum:
  - `tenantId`, `versionId`, `commitId`, `publishWorkflowId`, `publishedAt`
  - `participantDigests[] { serviceName, appliedCommitId, contentDigest, digestSchemaVersion }`
  - `artifactDigests[] { artifactType, artifactPath, artifactDigest, artifactSchemaVersion }` for any exported derived artifacts; these entries are mandatory in the initial slice for world navmesh/path graph bundles
  - `manifestHash`, `manifestSchemaVersion`
  - `generationConfigRevision`
  - attestation schema/version fields for future evolution
  - Error semantics: `NOT_FOUND` means not publish-complete; `SCHEMA_VERSION_UNSUPPORTED` means fail closed until callers support the attestation schema.
  - Cache semantics: publish gates, activation, cutover preflight, and repair must read fresh attestation state rather than reusing stale cached payloads.

Digest comparison rules:

- The Game Design Service records the per-domain-service `{commitId, contentDigest, digestSchemaVersion}` it observed when a commit was last applied successfully.
- Reconciliation and publish-time checks compare the current digest reported by each service against the recorded digest for the target commit.
- If `digestSchemaVersion` differs, publish must fail fast and require an explicit migration of digest semantics (for example by bumping `digestSchemaVersion` and replaying commits to record new digests), rather than silently comparing incompatible hashes.
- For one publish attempt, every required participant must attest the same target commit scope. Publish must fail closed if required participants report different `appliedCommitId` values for the same requested publish target, even if no individual digest payload is malformed.
- Digest request/response payloads are canonical across participants:
  - `GetDraftDesignDigestRequest { tenantId, scope: oneof {versionId, scriptPatchVersion} }`
  - `GetDraftDesignDigestResponse { tenantId, scope, appliedCommitId, contentDigest, digestSchemaVersion }`
  - Unsupported scopes must fail with `UNSUPPORTED_SCOPE`; publish orchestration must treat this as a hard mismatch for required participants.

### Digest Participants by Publish Type

Publish workflows must use an explicit participant matrix so digest gating is deterministic:

| Publish Type | Required domain digests (`GetDraftDesignDigest`) | Required Game Design control-plane digest (`GetDesignControlPlaneDigest`) | Not part of digest gate |
| --- | --- | --- | --- |
| `PublishVersion` (full version) | World Management, Entity Management, Game Logic, Automation & Scripting (for version-scoped script/binding templates) | Required for normalized references and publish-critical metadata (for example `game_template_*_ref`, `version_asset`) | Asset export/object-store bytes (validated by `manifestHash` in publish saga), Game Design internal history/audit tables that do not affect launchability |
| `PublishScriptPatchVersion` (script-only) | Automation & Scripting (for the target `<tenantId, scriptPatchVersion>` design graph) | Required for patch metadata/wiring for the same base version scope | World Management, Entity Management, Game Logic template digests (must remain unchanged for base version) |

### Change Vehicle Selection Matrix

Use the smallest canonical change vehicle that matches the desired outcome:

| Desired change | Canonical vehicle | Must not be used for |
| --- | --- | --- |
| Change world/entity/assets or any publish-attested runtime design graph | `PublishVersion` | Script-only edits, plugin activation, template-default tweaks that do not change a published release |
| Change only script/runtime automation logic for one existing `baseVersionId` | `PublishScriptPatchVersion` | Asset changes, world/entity template changes, base-version changes |
| Publish a signed plugin bundle into immutable design-time history | `PublishPluginVersion` | Full design publish, runtime activation by itself |
| Activate/deactivate one already published plugin version for matching runtime instances | `SetPluginActiveVersion` / related plugin runtime controls | Publishing unsigned or non-`PUBLISHED` plugin versions |
| Change default launch wiring or operator defaults for future instance creation without changing an existing published release bundle | Game template update | Mutating already published release attestation or runtime state of existing launched instances |

Rules:

- The publish request type determines the participant set; do not infer participants dynamically from transient service availability.
- Participant roles are explicit per publish type:
  - `saga-step participant`: executes durable finalize/validation work inside the publish Saga.
  - `digest-gate participant`: supplies digest attestation used to block/allow publish but does not necessarily execute a Saga step.
  For full publishes, Game Logic is digest-gate only unless/until it owns explicit publish-time finalize steps.
- A service outside the matrix for the current publish type may be validated separately, but must not block digest gating for that publish type.
- Orchestrator routing is strict by publish type/scope:
  - Full publish requests (`scope.versionId`) call only participants in the full-publish matrix.
  - Script-only publish requests (`scope.scriptPatchVersion`) call only script-patch participants.
  - Services outside the active participant set are not called; `UNSUPPORTED_SCOPE` from an out-of-scope service is informational only.
  - `UNSUPPORTED_SCOPE` from an in-scope required participant is a hard gate failure.
- Changes to this matrix require an explicit doc + migration update in both `version-control.md` and `world-editing-tools.md`.
- Every service listed in this matrix must maintain a service-local digest input manifest documenting included/excluded objects, canonicalization, and `digestSchemaVersion` bump criteria. Publish gating should fail closed when a participant cannot attest a digest under its documented manifest for the reported schema version.
- Full publish additionally requires that the final immutable release attestation row be written successfully; a version is not publish-complete until `published_release_bundle` exists for the target `(tenantId, versionId)`, and version asset artifacts must not transition to terminal `PUBLISHED` state before that attestation write succeeds.
- The publish orchestrator must not expose a half-complete success state between "all digests matched" and "release attestation row exists". Until `published_release_bundle` commits successfully, the version remains non-launchable and consumers must observe it exactly as non-attested.
- Exact-bytes repair is not a publish-attestation rewrite. If a Published/Active release cannot reproduce the attested digest/manifests/artifact bytes exactly, normal repair must fail closed and require either publishing a new `versionId` or a future explicit re-attestation workflow with its own audit and immutability contract.
- Any future re-attestation workflow must be modeled as a distinct control-plane operation, not as a hidden side effect of `PublishVersion` retry, `ExportAssets` retry, or ordinary repair tooling.

### Digest Schema Migration

Digest semantics are part of the publish safety contract. Changing which rows/fields participate in a domain digest, or how they are canonicalized, requires an explicit migration plan:

1. **Introduce the new digest semantics**
   - Bump `digestSchemaVersion` in every participating domain service whose digest rules changed.
   - Deploy readers/reconcilers that understand both old and new schema versions before enabling new writers (follow the “readers first, writers second” rollout rule).
2. **Re-record digests for existing commits**
   - The Game Design Service must replay commits (or a designated “recompute digests” workflow) so it can record the `{commitId, contentDigest, digestSchemaVersion}` values observed under the new rules.
   - During this phase, `PublishVersion` must reject any attempt to compare digests across different schema versions.
3. **Enforce the new schema version**
   - Once all participating services and the Game Design Service have recorded new digests, publish gating switches to require the new `digestSchemaVersion` for all participating domains.

Any attempt to ship a digest semantics change without this workflow risks false “OUT_OF_SYNC” states or, worse, publishing versions whose Draft templates were not actually converged under a consistent digest definition.

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

### Script Patch Versions and Runtime Behavior

Script-only fixes are tracked as `scriptPatchVersion` values attached to a `baseVersionId`. Together, `(versionId, scriptPatchVersion)` define the effective script bundle for a game:

- The Game Session Service records the `scriptPatchVersion` that is currently pinned for each `gameInstanceId` and includes it in the context for every tick effect and script trigger.
- The Automation & Scripting Service owns the tenant readiness lifecycle (`PENDING_VALIDATION`, `ONLOAD_RUNNING`, `READY`, `FAILED`) of each `<tenantId, scriptPatchVersion>` as described in `system-architecture-scripting-dsl-reference-and-lifecycle.md`. A patch may only be pinned for an instance once Automation & Scripting has marked it `READY` for that tenant.
- Runtime services load and cache scripts based on the `(versionId, scriptPatchVersion)` that Game Session supplies for each effect; Automation & Scripting must not silently substitute a different patch if the supplied one is unknown or `FAILED`—such triggers are rejected and surfaced in audit logs and metrics.

Rollouts and rollbacks:

- Rolling out a new script patch for a game consists of publishing the patch in Game Design Service, allowing Automation & Scripting Service to validate and mark it `READY`, and then having Game Session update the pinned `scriptPatchVersion` for one or more `gameInstanceId` values. Existing effects remain tied to the `(versionId, scriptPatchVersion)` pair recorded when they were applied; future effects observe the new patch.
- Rolling back a script patch means pinning an earlier `scriptPatchVersion` for the same `baseVersionId` on the affected instances. This is an instance rollout/control-plane action, not a tenant lifecycle state transition. Historical records for prior effects remain associated with the `(versionId, scriptPatchVersion)` values that were in effect at the time, even after instances start using a different patch level.

Game Design Service owns the history and metadata for script revisions and patch versions; Automation & Scripting Service owns runtime readiness and execution; Game Session Service owns which `scriptPatchVersion` is pinned per instance and must record that choice alongside each effect for determinism and auditability.

Scope constraints:

- Script-only patches are limited to script definitions and related Automation & Scripting metadata. They must not introduce or modify assets, world layouts, entity templates, or other non-script configuration; any change that requires updating templates or `game_assets` / `version_asset` mappings must be delivered via a full `PublishVersion` that produces a new `versionId`.

## Benefits

- Designers can experiment on feature branches without affecting the main game line.
- Patch notes are automatically generated from commit messages.
- Downstream services continue to consume only published versions so runtime stability is preserved.

## Related Documentation

- [Game Design Service Architecture](README.md)
- [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md)
