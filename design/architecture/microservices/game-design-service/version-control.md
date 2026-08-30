# Version Control for Design Assets

## Normative Target Contract

Game Design is the canonical authored-history, publication-coordination, release-descriptor, and final-attestation authority. Owner APIs remain the write path for domain-owned Draft and Published participant data; immutable versions, script patches, release bundles, participant digests, and provenance must be validated and correlated through those APIs. Optimistic concurrency and deterministic replay preserve revision order and conflict safety, while any external Git integration is ingestion only and must not become a second content authority.

## Implementation Status

External Git synchronization and canonical multi-branch merge semantics are not implemented. Current authoring uses Game Design-owned revision APIs with optimistic validation and ordered mutation calls, but `SaveRevision` does not provide deterministic replay: it lacks durable request/revision deduplication and can leave a World mutation committed without its corresponding Game Design revision when the later local save fails. ADR 0129's durable multi-owner coordinator, owner-local compare-and-swap application, synchronized commit fence, and proof that partially applied owner rows remain invisible to ordinary Draft reads are also not implemented. Current release-bundle schema and plugin publication/activation code incorrectly reuse Automation & Scripting's aggregate participant `contentDigest` where the dedicated Game Logic-owned `abilitySchemaDigest` is required; exact ability-schema compatibility therefore remains an implementation and proof gap. Plugin publication currently uses signed-only intake; [ADR 0111](../../decisions/adr-0111-unified-dsl-with-distinct-embedded-script-and-plugin-lifecycles.md)'s approved unsigned provenance remains target-only. The plugin publication path also lacks terminal-state rejection: an uploaded `SUPERSEDED` or `REVOKED_DESIGN` row can be republished under the same `pluginVersionId`; target rollback uses a new immutable plugin version and leaves the prior terminal row unchanged. Duplicate signed plugin `assetId` values are likewise not rejected before export and can produce ambiguous distribution-manifest keys; target intake rejects them deterministically before publication/export. The live World Management design handoff adds a separate lifecycle race: it checks Game Design's authoritative remote version state (`GetVersionState(...).versionState=DRAFT`) before its own transaction, but has no durable owner-local publication freeze or exact Game Design version-state epoch/fence in the mutation request. That remote state check is not proof that a WMS mutation cannot commit after publication; current status and evidence remain in [World Management Runtime and Data](../world-management-service/runtime-and-data.md#implementation-status).

Current cross-seam security status is also blocked: Account's runtime membership and entitlement gRPC methods are configured as public-method bypasses and their handlers accept caller-supplied account/tenant identifiers without the target exact Game Session or World Management workload policy. Participant digest authorization and tenant-identity gaps are recorded below and remain unproved.

## Approach

- Each asset revision already stores author and timestamp metadata.
- Publishing a version creates an immutable snapshot identified by `version_id`.
  Script-only fixes use a `scriptPatchVersion` tied to a `baseVersionId` so minor
  automation updates can go live without republishing all assets.
- **Target state:** To provide Git-style history, revisions will be grouped under explicit branches and commits stored in a durable Game Design history model. No branch/commit tables or current APIs exist in the first slice; current history is limited to service-owned revision/version rows and publish metadata.
- **Target state:** A future service contract may expose branch creation and commit-history reads; no such branch/commit APIs are currently exposed by the Game Design proto/service. Canonical multi-branch merge semantics require an explicit validated conflict contract.
- Any future external Git webhook or repository integration must submit changes through Game Design-owned revision APIs and must not become a second content authority.

### History and Provenance Across Services

The Game Design Service is the canonical history store for world and entity
content even though domain services own the runtime templates:

- Each revision and commit references concrete domain objects (rooms, regions,
  NPCs, items, templates) via stable identifiers maintained by the owning domain services.
- **Target state:** During authoring, design tools apply revisions incrementally to domain services’ **Draft** template rows via idempotent design APIs bound to the complete commit/revision identity and affected-epoch set. Draft template graphs in World Management, Entity Management, and related services are then the authoritative snapshots of world and entity data for each version. In this document, a **remote `Draft` state** means the authoritative Game Design `GetVersionState` response for the target `(tenantId, versionId)` reports `versionState=DRAFT`; it does not mean that a remote service-layer read proves a WMS transaction is serialized with publication.
- **Current first slice:** `SaveRevision` optionally calls World Management to apply a mutation and only afterward unconditionally inserts the Game Design revision; Game Design has no matching deduplication lookup or uniqueness constraint. This ordering is therefore not idempotent shared-Draft proof; the current partial-application and retry gap is recorded in Implementation Status.
- **Target state:** When a version is published, the service coordinates the durable Temporal `publish` workflow that validates and finalizes the existing Draft data in each domain service and transitions the version to Published as described in [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md). No separate design database is copied into domain services at publish time. **Current implementation:** the Temporal-disabled synchronous fallback remains active and does not establish that durability contract; the Temporal-enabled path is currently blocked by the Automation & Scripting digest participant's `PERMISSION_DENIED` response.
- These design APIs accept writes only for Draft versions. Once a version is
  marked Published, the corresponding template rows in domain services are
  treated as immutable for that `(tenantId, versionId)`; further edits require
  creating a new Draft version and publishing it.
- Domain services do not maintain their own commit histories; they expose only
  the current and historical versioned templates keyed by `(tenantId, versionId)`.

To audit current history for a room, NPC, or item, contributors make a best-effort audit by querying the Game Design Service’s revision/version rows and correlating those revisions with the owning domain’s versioned templates and, where available, its applied-revision ledger (currently World Management’s ledger; Entity Management’s ledger remains future work). This cross-service view is not a canonical branch/commit audit because synchronized branch/commit read APIs do not exist yet. **Target state:** once the durable branch/commit model and synchronized read APIs exist, those surfaces will provide the canonical branch/commit audit view.

### Design-Time Synchronization

The exact-base, digest-bound, multi-owner commit contract is owned by [ADR 0129](../../decisions/adr-0129-durable-fenced-multi-owner-draft-commits.md); this section records the Game Design local history, coordination, and visibility consequences.

Game Design owns the durable, creator-visible coordination record for every shared-Draft commit and isolated proposal. Each record binds the target `tenantId` and `versionId`, exact `baseCommitId`, stable request or proposal identity, canonical digest of the complete input, canonical revision order, the complete affected `(tenantId, versionId, owner, aggregateId, scopeId, epoch)` set, and durable per-owner application status.

- External AI/tool clients use ordinary scoped public creator APIs. A first-party agent uses the trusted scoped tool broker to build an isolated, reviewable Draft proposal; proposal acceptance creates or selects one exact commit application and does not grant a separate write or merge path. Both flows use the same exact tenant/version, base, complete-diff, affected-epoch, revision-order, and digest contract.
- Reusing one request, proposal, or commit identity returns the recorded result only when the complete binding matches. Reusing it with changed or omitted tenant/version, input, base, revision order, affected set, or digest is rejected.
- Each owner applies its portion through one storage-level atomic compare-and-swap transaction. That transaction checks every expected epoch required by the typed mutation for every fencing tuple in the complete subset owned by that service, while validating the complete affected set required by the typed mutation; it applies the local mutation, advances those epochs, and records the exact commit/digest result. A service-layer read followed by an unconditional update is not sufficient.
- The typed mutation determines its complete affected scope. An owner rejects an omitted required containing scope rather than allowing the caller to evade a scope conflict.
- Aggregate and scope epochs remain narrow conflict boundaries. The exact proposal base remains immutable provenance, while a newer synchronized commit that changed only disjoint scopes does not invalidate unchanged expected epochs.
- Cross-owner application is a durable coordinated workflow, not one distributed transaction. Game Design retries with the same identities and retains per-owner status across restarts.
- Owner-local application is not accepted shared Draft truth. Normal creator reads and subsequent edits use the durable snapshot/version at the last fully synchronized commit fence; rows applied under a later partial owner commit remain invisible to those reads. Each owner must preserve the ability to serve that fenced snapshot while later partial work exists.
- Game Design advances the synchronized fence only after every required owner durably reports the same exact commit and digest. Partial application remains diagnostic workflow state and cannot satisfy `IN_SYNC` or become a publish target.
- Conflict assistance may construct a new proposed diff, but it must produce a new digest and exact base/epoch binding for creator review. Reconciliation never silently merges a stale proposal.

#### Publication freeze and owner handoff (Target State)

The synchronized Draft fence is also the lifecycle handoff into publication. Before collecting the World Management participant digest, reading the final release state, or committing `published_release_bundle` and `PUBLISHED` metadata, Game Design must obtain and retain the exact WMS owner-freeze acknowledgement, then complete or abort that same owner operation after reconciling its publication outcome. The canonical WMS operations, fields, phase transitions, exact replay/conflict behavior, and current implementation/proof boundary are defined in [World Management API Contracts](../world-management-service/api-contracts.md#publication-freeze-and-terminal-handoff-target-state). This is one owner-local freeze protocol, not a distributed transaction: the freeze serializes WMS Draft writes, while Game Design remains the lifecycle and final-attestation authority. Focused proof must interleave a WMS mutation with freeze acquisition, publication, retry, abort, and lost-response reconciliation and prove that no post-freeze Draft row or digest can become part of a Published release.

**Current implementation gap:** World Management currently lacks the durable owner-local publication freeze and exact Game Design version-state epoch/fence enforcement required by this target contract. Its live handoff checks the authoritative remote Game Design `DRAFT` state before its own transaction, but that check does not prove that a WMS mutation cannot commit after publication; current status and evidence remain in [World Management Runtime and Data](../world-management-service/runtime-and-data.md#implementation-status).

The Game Design Service tracks a derived `designSyncStatus` for each `(tenantId, versionId)`. It is `IN_SYNC` only when the durable commit record, every required owner result, and the synchronized commit fence agree on the exact commit and digest. The `PublishVersion` workflow must verify this state before starting durable publication. The coordinator may use the canonical durable workflow substrate and transactional outbox delivery where appropriate, but it must not claim cross-database atomicity or expose partial application as accepted Draft state.

### Draft Digest and Reconciliation Details (Target State)

Game Design is the canonical owner of publication coordination, release descriptors, and the final release attestation. Domain services remain the canonical owners of their versioned participant data and participant digests. This owner split implements [ADR 0093](../../decisions/adr-0093-game-design-coordinated-digest-attested-content-publication.md); service-local documents link here for their participant and persistence consequences instead of copying the publication contract.

The following eventual-consistency ordering and replay rules are ADR 0129 target-state requirements, not current live ordering. Current `SaveRevision` ordering and its missing durable-coordinator/deduplication gap are recorded in [Game Design API implementation status](api-contracts.md#implementation-status); this section does not repeat those details.

Because design changes often span multiple domain services (for example World
Management and Entity Management), the system treats the Game Design Service as
the source of truth for which revisions belong to a version, and the domain
services as the source of truth for the current Draft template graphs:

- Applying a commit is **eventually consistent** across services:
  - The durable commit/proposal record and its revisions are written to the Game Design Service first.
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
  API with typed scope (`versionId` for full publish, or the complete
  `{baseVersionId, scriptPatchVersion}` pair for script-only publish) returning
  `appliedCommitId` plus a stable `contentDigest` and `digestSchemaVersion`.
  `appliedCommitId` is the highest commit whose complete revision set has been
  durably applied for that scope. Services may keep revision-level ledgers
  internally, but publish gates and reconciler comparisons must use
  commit-level convergence only. The reconciler updates `designSyncStatus` back
  to `IN_SYNC` only when the durable commit record and synchronized commit fence
  identify the same commit, every required durable per-owner outcome is
  `APPLIED` (or an equivalent successful terminal state) for and matches that
  exact commit and digest, and every participating service reports the matching
  exact digest for that commit.
- The `PublishVersion` workflow must verify that `designSyncStatus == IN_SYNC`
  before starting the durable `publish` workflow. Versions that are out of sync cannot be
  published until reconciliation succeeds.
  - Reconciliation does not authorize silently broken drafts:
    - commits introducing unresolved cross-service references must move the version into explicit invalid state (`UNRESOLVED_REFERENCE` or equivalent) rather than leaving it as a normal Draft;
    - replay workers may retry delivery, but they must not invent identifier rewrites or downgrade hard validation failures into generic `OUT_OF_SYNC`.

#### Owner-to-owner digest authorization and tenant identity

`GetDraftDesignDigest` is a read-only owner-to-owner publication read across World Management, Entity Management, Game Logic, and Automation & Scripting. Every receiver uses the shared [Security Architecture](../../system-architecture-security.md) and [Authentication & Authorization](../../system-architecture-authentication.md) workload and method-allowlist rules: only the authenticated Game Design workload identity may call the publication method, and other internal workloads plus user/admin JWTs are denied. The invocation binds exact `{tenantId, scope}` (`versionId` for full publish or the complete `{baseVersionId, scriptPatchVersion}` pair for script patch), stable `publishRequestId`, derived workflow identity, and canonical `requestDigest`; missing, changed, or omitted binding evidence fails closed before a digest is returned. This context is owner-authentication and replay evidence, not an end-user role and not part of the content digest. Temporal activities never propagate the human operator JWT; operator authority ends when Game Design starts or attaches to the workflow. Each response's tenant and complete scope tuple must be exact-compared by Game Design before recording or gating the attestation; Game Design remains publication coordinator and each participant remains owner of its digest.

**Implementation status:** `TemporalVersionPublishActivitiesImpl` invokes reconciliation outside the incoming gRPC `SessionContext`, so Game Design's outbound client emits an internal-service JWT with empty global roles. Automation's current `GetDraftDesignDigest` handler invokes `AdminRoleGuard`, which requires a global privileged role, so the full-publish Automation participant returns `PERMISSION_DENIED` whenever Temporal orchestration is enabled. WMS, Entity, and Game Logic digest handlers have no equivalent owner/method authorization and accept any bearer that passes shared JWT parsing, creating the opposite unauthorized-reader drift. In addition, Game Design's live participant DTO/client/gate omit and do not compare response `tenantId`; Game Logic accepts a blank tenant and hashes a tenant/version-independent empty manifest. The current digest proto/client also carries only `scriptPatchVersion` for the patch scope and omits its required `baseVersionId`; the complete base-version/patch binding is target-state and is not current proof. These gaps block the target tenant-bound attestation and require endpoint, wrong-workload, cross-tenant, exact-scope, and exact-identity negative proof. The Automation remediation remains a narrowly allowlisted Game Design workload path, not a restoration of admin-role authorization.

#### Launch and attestation read authorization (target state)

Launch and release-attestation reads are owner-to-owner control-plane APIs, not a generic internal-service read class. Each RPC must have an explicit authenticated workload allowlist and bind the caller's authorized tenant/scope to the request before reading data; a bearer that merely declares `isInternalService` is insufficient. The target launch/read allowlist is: `ResolveLaunchDescriptor` — Game Session only; `GetTemplateReferencePhase` — Game Session for launch preflight and the explicitly named Game Design/retirement-control-plane workload for cutover tooling; `GetPublishedReleaseBundle` — Game Session, World Management, and Automation & Scripting; `GetVersionState` and `GetVersionAssetArtifactState` — Game Session and World Management for launch/activation reads. `GetVersionAssetArtifactState` also has a separate operator-diagnostic use: Logging & Admin may broker an authenticated read for operators, and CI/runbook automation may use only a dedicated named operator-read workload identity (or the Logging & Admin operator surface), never a generic internal-service bearer. That operator path is diagnostic evidence and does not grant launch or mutation authority. Each request must exact-bind its tenant and version/template/request scope (including source/target versions where present), and the owner must reject a wrong workload, cross-tenant scope, omitted scope, or changed replay identity before returning a descriptor, attestation, lifecycle state, or artifact state. Focused proof must exercise every allowlisted caller and a denied internal workload plus cross-tenant requests for each RPC, including the separate operator-read path where it is implemented.

In addition to domain-service digests, publish safety requires a Game Design control-plane digest:

- Game Design computes a canonical digest over normalized dependency rows that affect launchability and publish immutability (for example `game_template_*_ref`, `version_asset`, and related version wiring metadata).
- This control-plane digest is recorded per commit/version scope and validated in the same gating pass as domain-service digests.
- Control-plane digest mismatch is treated exactly like a domain digest mismatch (`OUT_OF_SYNC`, publish blocked).
- The control-plane digest surface should be exposed through a read-only API (for example `GetDesignControlPlaneDigest`) so publish tooling uses a uniform participant contract.
- `GetDesignControlPlaneDigest` should return at minimum `{tenantId, versionId or scriptPatchVersion scope, appliedCommitId, contentDigest, digestSchemaVersion}` so publish gates compare like-for-like payloads across all participants.
- After all digest gates and verified private-candidate asset export succeed, Game Design must persist a single immutable `published_release_bundle` attestation for `(tenantId, versionId)` that captures the final participant digests, the manifest digest, the complete required artifact set, and `generationConfigRevision`.
- Game Design must expose the attestation through `GetPublishedReleaseBundle(tenantId, versionId)` with deterministic response fields at minimum:
  - `tenantId`, `versionId`, `commitId`, `publishWorkflowId`, `publishedAt`
  - `participantDigests[] { serviceName, appliedCommitId, contentDigest, digestSchemaVersion }`
  - `artifactDigests[] { usageKey, artifactKind, immutableObjectKey, contentDigest, contentType, artifactSchemaVersion }` for every exported binary or derived artifact; these entries are mandatory in the initial slice for world navmesh/path graph bundles
  - `requiredManifestAssetKeys[]` listing the stable manifest usage keys required for launch or cutover validation of that release
  - `publishedReleaseBundleRef` — target-state owner-generated opaque identity for the immutable release bundle; the current implementation may expose only its internal bundle row identifier, so this field is not yet live
  - `manifestHash`, `manifestSchemaVersion`
  - `abilitySchemaDigest` — the dedicated Game Logic-owned ability-schema digest for the same target commit, carried with the existing Game Logic participant `digestSchemaVersion` and canonicalization evidence; aggregate participant digests are not substitutes
  - `generationConfigRevision`
  - attestation schema/version fields for future evolution
  - Error semantics: `NOT_FOUND` means not publish-complete; `SCHEMA_VERSION_UNSUPPORTED` means fail closed until callers support the attestation schema.
  - Cache semantics: publish gates, activation, cutover preflight, and repair must read fresh attestation state rather than reusing stale cached payloads.

Digest comparison rules:

- The Game Design Service records the per-domain-service `{commitId, contentDigest, digestSchemaVersion}` it observed when a commit was last applied successfully.
- Reconciliation and publish-time checks compare the current digest reported by each service against the recorded digest for the target commit.
- If `digestSchemaVersion` differs, publish must fail fast and require an explicit migration of digest semantics (for example by bumping `digestSchemaVersion` and replaying commits to record new digests), rather than silently comparing incompatible hashes.
- For one publish attempt, every required participant must attest the same target commit scope. Publish must fail closed if required participants report different `appliedCommitId` values for the same requested publish target, even if no individual digest payload is malformed.
- The Automation full-version participant must select only the script definitions and event bindings mapped to the requested `(tenantId, versionId)`. Including `versionId` only as a field in a digest over tenant-wide rows is not version-scoped attestation. If the live data model cannot map the requested version to an exact script/binding set, the participant must fail closed rather than return a tenant-wide digest. Proof must show two versions with disjoint script/binding inputs produce isolated digests and that a cross-version row cannot satisfy either publish gate.
- The dedicated `abilitySchemaDigest` is compared exactly with the Game Logic-owned digest for the same target commit and its existing participant `digestSchemaVersion`/canonicalization contract; missing, unsupported, stale, or mismatched evidence fails closed.
- Digest request/response payloads are canonical across participants:
  - `GetDraftDesignDigestRequest { tenantId, scope: oneof {versionId, scriptPatchVersion}, baseVersionId (required when scriptPatchVersion is selected), publishRequestId, derivedWorkflowIdentity, requestDigest }`; the binding fields may instead be carried in authenticated owner metadata only when the receiver validates them as part of the same typed request contract.
  - `GetDraftDesignDigestResponse { tenantId, scope, baseVersionId (when scriptPatchVersion is selected), appliedCommitId, contentDigest, digestSchemaVersion }`
  - Unsupported scopes must fail with `UNSUPPORTED_SCOPE`; publish orchestration must treat this as a hard mismatch for required participants.

### Digest Participants by Publish Type

Publish workflows must use an explicit participant matrix so digest gating is deterministic:

| Publish Type | Required domain digests (`GetDraftDesignDigest`) | Required Game Design control-plane digest (`GetDesignControlPlaneDigest`) | Not part of digest gate |
| --- | --- | --- | --- |
| `PublishVersion` (full version) | World Management, Entity Management, Game Logic, Automation & Scripting (for version-scoped script/binding templates) | Required for normalized references and publish-critical metadata (for example `game_template_*_ref`, `version_asset`) | Asset export/object-store bytes (validated by `manifestHash` in the durable publish workflow), Game Design internal history/audit tables that do not affect launchability |
| `PublishScriptPatchVersion` (script-only) | Automation & Scripting (for the target `<tenantId, baseVersionId, scriptPatchVersion>` design graph) | Required for patch metadata/wiring for the same base-version/patch scope | World Management, Entity Management, Game Logic template digests (must remain unchanged for base version) |

Asset bytes are intentionally outside participant design digests, but they remain mandatory publication gates through private candidate verification, the attested manifest digest, and every actual-byte artifact digest in the release bundle.

The full-version release bundle also carries a separately named `abilitySchemaDigest` produced from the Game Logic-owned ability-schema inputs under Game Logic's existing participant digest manifest, `digestSchemaVersion`, and canonicalization rules for the same target commit. Game Logic's broader aggregate participant `contentDigest` and Automation & Scripting's participant digest cannot substitute for this field. Plugin and script-patch compatibility checks compare against this dedicated release-attested value exactly; missing ability-schema evidence fails closed.

### Change Vehicle Selection Matrix

Use the smallest canonical change vehicle that matches the desired outcome:

| Desired change | Canonical vehicle | Must not be used for |
| --- | --- | --- |
| Change world/entity/assets or any publish-attested runtime design graph | `PublishVersion` | Script-only edits, plugin activation, template-default tweaks that do not change a published release |
| Change only script/runtime automation logic for one existing `baseVersionId` | `PublishScriptPatchVersion` | Asset changes, world/entity template changes, base-version changes |
| Publish a plugin bundle into immutable design-time history | `PublishPluginVersion` | Full design publish, runtime activation by itself, or bypassing intake validation/approval/attestation |
| Activate/deactivate one already published plugin version for matching runtime instances | `SetPluginActiveVersion` / related plugin runtime controls | Publishing an unvalidated or non-`PUBLISHED` plugin version |
| Change default launch wiring or operator defaults for future instance creation without changing an existing published release bundle | Game template update | Mutating already published release attestation or runtime state of existing launched instances |

Rules:

- The publish request type determines the participant set; do not infer participants dynamically from transient service availability.
- Participant roles are explicit per publish type:
  - `workflow-step participant`: executes durable finalize/validation work inside the publish workflow.
  - `digest-gate participant`: supplies digest attestation used to block/allow publish but does not necessarily execute a workflow step.
  For full publishes, Game Logic is digest-gate only unless/until it owns explicit publish-time finalize steps.
- A service outside the matrix for the current publish type may be validated separately, but must not block digest gating for that publish type.
- `PublishVersionRequest` must carry a stable `publish_request_id`; the Game Design Temporal `publish` workflow uses that caller-visible request identity as its durable business key, so retries must reuse the same request id instead of minting a fresh client UUID on each attempt.
- Orchestrator routing is strict by publish type/scope:
  - Full publish requests (`scope.versionId`) call only participants in the full-publish matrix.
  - Script-only publish requests (`scope.scriptPatchVersion`) call only script-patch participants.
  - Services outside the active participant set are not called; `UNSUPPORTED_SCOPE` from an out-of-scope service is informational only.
  - `UNSUPPORTED_SCOPE` from an in-scope required participant is a hard gate failure.
- Changes to this matrix require an explicit doc + migration update in both `version-control.md` and `world-editing-tools.md`.
- Every service listed in this matrix must maintain a service-local digest input manifest documenting included/excluded objects, canonicalization, and `digestSchemaVersion` bump criteria. Publish gating should fail closed when a participant cannot attest a digest under its documented manifest for the reported schema version.
- Full publish additionally requires that the final immutable release attestation row be written successfully; a version is not publish-complete until `published_release_bundle` exists for the target `(tenantId, versionId)`, and version asset artifacts must not transition to terminal `PUBLISHED` state before that attestation write succeeds.
- The publish orchestrator must not expose a half-complete success state between "all digests matched" and "release attestation row exists". Until `published_release_bundle` commits successfully, the version remains non-launchable and consumers must observe it exactly as non-attested.
- Downstream launch, repair, and rollback-preflight readers must also fail closed when the attestation schema is unsupported by the current service version; unsupported attestation is not a soft warning.
- Exact-bytes repair is not a publish-attestation rewrite. If a Published/Active release cannot reproduce the attested digest/manifests/artifact bytes exactly, including the attested `requiredManifestAssetKeys[]`, normal repair must fail closed and require either publishing a new `versionId` or a future explicit re-attestation workflow with its own audit and immutability contract.
- Any future re-attestation workflow must be modeled as a distinct control-plane operation, not as a hidden side effect of `PublishVersion` retry, `ExportAssets` retry, or ordinary repair tooling.
- Successful publish attempts record a Game Design-owned participant-digest baseline keyed by `(tenantId, publishType, participantKey, scope, appliedCommitId)`, where a script-patch `scope` always includes both `baseVersionId` and `scriptPatchVersion`. Any later publish attempt that observes the same applied commit with a different scope, digest schema, or content digest must fail closed before attestation is written.
- Publish APIs surface typed control-plane failure codes for gate failures (for example `PARTICIPANT_UNAVAILABLE`, `PARTICIPANT_SCOPE_MISMATCH`, `UNSUPPORTED_DIGEST_SCHEMA`, `APPLIED_COMMIT_MISMATCH`, `RECORDED_CONTENT_DIGEST_MISMATCH`) rather than collapsing them into one generic publish failure outcome.

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

Script-only fixes are tracked as `scriptPatchVersion` values attached to a `baseVersionId`. Together, `(baseVersionId, scriptPatchVersion)` identify the effective published script artifact and its base-cohesion/cache context for a game. A script-only publication may also receive a separate `versionId` for publication metadata and history; that identifier does not replace the base-version/patch pair when resolving the effective script artifact. The accepted Game Session runtime execution pin and rollback fence remain the exact `{scriptPatchVersion, scriptPinEpoch}` tuple; `baseVersionId` is separately validated as the patch's base-cohesion and artifact context and does not silently add a field to Trigger Identity or replace that pin tuple. Artifact, cache, and runtime-read contracts that resolve an effective script artifact must carry and exact-compare the base-version/patch pair; the tenant readiness lifecycle and its readiness identity remain keyed by `scriptPatchVersion` as defined by Automation & Scripting's canonical lifecycle.

The exact tuple propagation, final-effect fencing, and Game Session rollout-history guarantees described below are target-state behavior; current implementation and proof gaps are tracked in the [Game Session runtime and tick coordination tracker](../../../project-management/implementation-tracking/game-session-runtime-and-tick-coordination.md#active-gaps).

Current wire/storage evidence is narrower: Game Session's authoritative pin row and the live trigger/work-item surfaces carry `scriptPatchVersion` (with `scriptPinEpoch` propagation itself also incomplete), while some read responses enrich that patch with a Game Design publication link containing `baseVersionId`. That response-side enrichment is not an authoritative pin binding; the target base-version/patch pair and its exact comparison remain unimplemented and unproved.

- The Game Session Service records the exact `{scriptPatchVersion, scriptPinEpoch}` currently pinned for each `gameInstanceId`, validates that patch's `{baseVersionId, scriptPatchVersion}` against the instance runtime version, and includes the exact pin tuple in every instance-scoped gameplay/runtime script trigger, work item, schedule/timer firing, handoff, and tick effect. Base-version/patch metadata may be retained alongside those records as artifact and cohesion context, but it does not silently redefine Trigger Identity or the Game Session pin fence. Tenant-readiness `onLoad` is the pre-instance-pin exception: it carries the candidate `scriptPatchVersion` and its separately validated base/artifact context but omits `gameInstanceId`, runtime scope, and `scriptPinEpoch`, and cannot emit gameplay work or effects. Game Session also owns the append-only history of committed pin, repin, and rollback attempts; Game Design does not reconstruct it from publication or readiness events.
- The Automation & Scripting Service owns the tenant readiness lifecycle (`PENDING_VALIDATION`, `ONLOAD_RUNNING`, `READY`, `FAILED`, terminal `SUPERSEDED`) of each `<tenantId, scriptPatchVersion>` readiness identity as described in the [scripting DSL reference and lifecycle](../../system-architecture-scripting-dsl-reference-and-lifecycle.md#script-execution-lifecycle), while validating its associated `baseVersionId` as artifact/base-cohesion context. Current implementation/proof status is tracked in the [Automation and Scheduler Runtime tracker](../../../project-management/implementation-tracking/automation-and-scheduler-runtime.md#capability-status). **Target state:** A newer publish supersedes only an unpinned candidate still `PENDING_VALIDATION` or `ONLOAD_RUNNING`. Readiness-generation fencing prevents a stale evaluator from committing `READY` or reopening admission; its terminal result is `finalStage=DSL_EVAL`, `finalOutcome=canceled`, `finalReason=stale_execution_fenced`, with no DSL re-entry or new `scriptEventId`/step identity. Retry requires republishing a new immutable patch. A patch may only be pinned for an instance once Automation & Scripting has marked that `scriptPatchVersion` `READY` for that tenant and its base-version cohesion validates. Terminal `SUPERSEDED` patches remain visible for audit/history but are ineligible for new readiness or new instance pinning. An already-pinned `READY` patch is not relabeled `SUPERSEDED` merely because a newer publish arrives; it remains admissible only under its exact current Game Session tuple until an explicit Game Session repin or rollback. Already-admitted work remains governed by its captured Game Session tuple and epoch/fence rules. Current readiness workflow/projection rows and their request path remain keyed by `<tenantId, scriptPatchVersion>`; a response-side publication lookup that enriches `baseVersionId` is not durable proof of the pair and must not be treated as implementation of this target identity.
- Runtime services load and cache scripts only after validating the exact `{baseVersionId, scriptPatchVersion}` artifact binding together with the Game Session `{scriptPatchVersion, scriptPinEpoch}` pin tuple against a bounded-fresh authoritative observation. Cache entries must be keyed by or carry the effective `{baseVersionId, scriptPatchVersion}` pair and must never be reused for another base version merely because `scriptPatchVersion` matches. Automation may reuse a cached artifact only while its observed exact pin tuple is fresh and equal and its base-version/patch binding is exact; absent or stale projection evidence requires a bounded Game Session refresh, and unavailable or mismatched validation fails closed without a stale-epoch fallback. For new instance-scoped gameplay/runtime trigger admission, Automation & Scripting must not silently substitute a different base version, patch, or epoch if the supplied artifact is unknown, unavailable, `FAILED`, or `SUPERSEDED`; admission fails closed and remains observable. An already-pinned `READY` immutable patch may continue exact-tuple trigger admission and use its frozen artifact only under that validation until an explicit Game Session repin or rollback, or an epoch/fence rejection. Recovery to an earlier patch is an explicit Game Session repin, producing a new epoch and history entry.

Rollouts and rollbacks:

- Rolling out a new script patch for a game consists of publishing the patch in Game Design Service, allowing Automation & Scripting Service to validate and mark it `READY`, and then orchestrating independent Game Session mutations for the selected `gameInstanceId` values. Each instance mutation uses its own explicit tagged expected-current CAS: `EXPECT_UNPINNED` only for first pinning, `EXPECT_EPOCH(scriptPinEpoch)` for every normal mutation of an already-pinned instance, including `SET`, `REPIN`, or `ROLLBACK`, and the explicit `UNCONDITIONAL` tag only for the `platformAdmin` break-glass repair branch with the matrix's `privileged_control` authorization; that branch skips only current-pin comparison and retains coherent owner-state, readiness, and all other validation. Each successful mutation commits that instance's exact tuple, advances that instance's epoch, and appends that instance's history entry; bulk rollout is only orchestration of these per-instance CAS operations. Existing effects remain tied to their captured tuple; future effects observe the new tuple. See [Pin Compare-and-Set Preconditions](../../system-architecture-scripting-rollout-and-rollback.md#pin-compare-and-set-preconditions).
- Every successful pin or rollback mutation commits a new exact `scriptPinEpoch` and append-only Game Session history entry. Rolling back a script patch means explicitly repinning an earlier `{baseVersionId, scriptPatchVersion}` pair; the target must be tenant-`READY` in Automation & Scripting and base-compatible with the affected instance (`baseVersionId` matches its current `runtimeVersionId`). A same-version repin also advances the epoch, but is classified as a `SET`/`REPIN` operation rather than a rollback. These are instance rollout/control-plane actions, not tenant lifecycle state transitions. Routine rollback fences script work without pausing or rejecting unrelated player-command admission or ordinary gameplay ticks; the scoped Automation pause covers only new script admission and reconciliation. An explicitly declared unfenced effect family or migration remains the exceptional full-pause path in [ADR 0106](../../decisions/adr-0106-epoch-fenced-script-rollback-without-routine-gameplay-pause.md). Historical records for prior effects remain associated with the exact tuples that were in effect at the time.

Game Design Service owns the authoring history and metadata for script revisions and patch versions; Automation & Scripting Service owns runtime readiness and exact-artifact execution; Game Session Service owns which exact patch/epoch is pinned per instance and the committed rollout history, and must record that tuple alongside each effect for determinism and auditability. See [ADR 0109](../../decisions/adr-0109-game-session-owned-script-rollout-history.md), [ADR 0110](../../decisions/adr-0110-explicit-opt-in-schedule-continuity-across-script-transitions.md), and [ADR 0111](../../decisions/adr-0111-unified-dsl-with-distinct-embedded-script-and-plugin-lifecycles.md).

Scope constraints:

- Script-only patches are limited to script definitions and related Automation & Scripting metadata. They must not introduce or modify assets, world layouts, entity templates, or other non-script configuration; any change that requires updating templates or `game_assets` / `version_asset` mappings must be delivered via a full `PublishVersion` that produces a new `versionId`.

## Benefits

- **Target benefit:** Once durable branch/commit APIs and their audit surfaces exist, designers can experiment on feature branches without affecting the main game line; those APIs are not live today.
- **Target benefit:** Once commit metadata is persisted and exposed by the version-control contract, patch notes can be generated from commit messages; the current API does not provide that automation.
- Downstream services continue to consume only published versions so runtime stability is preserved.

## Related Documentation

- [Game Design Service Architecture](README.md)
- [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md)
