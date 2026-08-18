# Game Design Service API Contracts

## Overview

The protobuf service definitions under [../../../../protos/game-design/v1](../../../../protos/game-design/v1) are the authoritative wire-contract source for Game Design gRPC APIs. Architecture-doc JSON examples are normative for semantics and invariants, but field names and enums must ultimately converge on the proto definitions.

For REST endpoints, the authoritative request/response schema source is [openapi.yaml](../../../../services/game-design-service/src/main/resources/openapi.yaml). Design-doc examples should be updated to match when those schemas evolve.

## Implementation Status

The release-attestation, launch-resolution, version-state, settings, asset-purge, script-patch publication read APIs, and the first plugin publication workflow (`UploadPluginBundle`, `PublishPluginVersion`, `GetPublishedPluginVersion`, and `ListPluginVersionStatuses`) are live in the current proto/service path. Full-version publish now runs on the canonical Temporal `publish` workflow family when Temporal is enabled: the synchronous `PublishVersion` API returns success only after terminal publish-workflow success and commit of the immutable `published_release_bundle`/release attestation; timeout or failure is surfaced explicitly (as an error or in-progress result where the caller surface supports that), never as half-complete success. The durable release-attestation orchestration survives service restarts and exposes workflow runtime metadata through `GetPublishedReleaseBundle`. `UploadPluginBundle` now parses signed plugin bundles, enforces bounded ZIP intake limits, verifies allowlisted Ed25519 signatures, extracts immutable manifest metadata, persists the raw bundle in Game Design storage, and records a durable `SIGNATURE_VERIFIED` publication row. The live intake currently selects, persists, and exposes one allowlisted `signerKeyId`, not the complete target `verifiedSignatures[]` set; current proto/storage also expose no append-only signature-evidence ledger or append RPC. `PublishPluginVersion` validates the uploaded bundle and requires a published base-version release, applies component policy, exports plugin assets into a version-scoped distribution manifest, and supersedes older published versions. Its current `abilitySchemaDigest` comparison incorrectly uses the attested `AUTOMATION_SCRIPTING` aggregate participant digest; the target contract requires the dedicated Game Logic-owned ability-schema attestation for the same base version, so exact ability-schema compatibility remains unproved until the release bundle and validator converge. Explicit design-time revoke transitions and a durable append-only plugin publication-event read surface are live too through `RevokePluginVersion` and `ListPluginVersionStatusEvents`; the main remaining follow-through includes the dedicated ability-schema proof and broader activation-time alias/binding validation in owning runtime services.

When Temporal is disabled or its beans are absent, `PublishVersion` uses the same deterministic publish-attempt and reconciliation gates synchronously, including release-bundle and release-attestation commitment, returns only terminal success or failure, and lacks Temporal workflow durability and status metadata.

## gRPC APIs

- `SaveRevision` – persists a version-scoped design revision and can optionally apply a typed World Management design mutation in the same control-plane path, including scoped multi-row `WORLD_GENERATION_SUBTREE` payloads for generated room, exit, generation-rule, and spawn-binding changes.
- `PublishVersion` – freezes a set of revisions and now drives durable full-version publish / release-attestation orchestration through the Game Design Temporal `publish` workflow family when Temporal is enabled.
- `PublishScriptPatchVersion` – creates a script-only patch version referencing a base version.
- `GetPublishedScriptPatchVersion` – authoritative design-time read API for script-patch publication lifecycle and digest identity.
- `UploadPluginBundle` – signed bundle ingestion, archive verification, object-store persistence, and indexed manifest extraction flow.
- `PublishPluginVersion` – promotes a previously uploaded verified bundle into immutable design-time publication after compatibility and policy validation, exporting a plugin distribution manifest when the signed bundle declares `assetRefs[]`.
- `RevokePluginVersion` – transitions a design-time plugin publication to `REVOKED_DESIGN`.
- `ListPluginVersionStatusEvents` – returns bounded, filtered append-only plugin publication status-event history.
- `AppendPluginVersionSignatureEvidence` (target only, signed-intake-only) – appends one verified Ed25519 signature-evidence entry to the Game Design-owned append-only evidence ledger for an existing signed-intake plugin version without changing immutable payload, publication content, status, or `bundleDigest`; it is not an unsigned-intake attestation path.
- `GetPublishedPluginVersion` – authoritative design-time read API for plugin publication lifecycle and compatibility metadata.
- `ListPluginVersionStatuses` – authoritative broader plugin publication listing surface for operator and authoring tooling.
- `ListVersions` – enumerates published versions for selection when creating a game instance.
- `GetVersionState` / `CompareAndSetVersionState` – authoritative control-plane version lifecycle reads and CAS transitions. These APIs are now live in the proto/service path and are the canonical owner for `versionStateEpoch`.
- `GetDesignControlPlaneDigest` – digest surface for publish gating over normalized metadata.
- `GetTemplateReferencePhase` – returns the persisted normalized-reference enforcement phase (`BACKFILLING`, `VALIDATED`, `ENFORCED`) used by instance-creation and retirement workflows.
- `GetPublishedReleaseBundle` – authoritative read surface for immutable release attestation used by activation, cutover preflight, and repair workflows. It also carries the canonical publish workflow identity and workflow-runtime status for operators. `NOT_FOUND` means the target version is not publish-complete; `SCHEMA_VERSION_UNSUPPORTED` means callers must fail closed until they understand the attestation schema.
- `ResolveLaunchDescriptor` – resolves template metadata and control-plane inputs into one immutable launch descriptor for a game-instance creation attempt. The request must include `controlPlaneRequestId`, and repeated calls for the same launch attempt must return the same descriptor values without re-resolving to newer attestation or patch state. The resolved descriptor now carries the real version-state CAS epoch rather than a derived release-bundle surrogate, and deterministic launch-preflight failures such as `RELEASE_BUNDLE_NOT_FOUND` and `LAUNCH_REMAP_REQUIRED` are returned as normal app errors rather than generic invalid-input collapse. When a cross-version replacement launch is valid, the descriptor now freezes the approved persisted `remapSetId` for the source/target version pair.
- `CreateTemplateRemapSet` / `ApproveTemplateRemapSet` / `GetTemplateRemapSet` – authoritative control-plane APIs for persisted version-to-version remap identities and their audit history. Game Design owns the `remapSetId` namespace consumed by replacement launch resolution and later cutover preflight.
- `CanDeleteVersionAssets` – deletion-eligibility oracle for version-scoped asset prefixes.
- `BeginPurgeVersionAssets` – CAS-guarded purge start that atomically re-checks deletion eligibility and transitions `version_asset_artifact` into purge-in-progress state.
- `FinalizePurgeVersionAssets` – CAS-guarded purge completion that transitions purge-in-progress artifacts to `PURGED` after byte-deletion confirmation.

These gRPC entries are the discoverability index for the control-plane contracts described in [Game Templates and Configuration Tools](game-templates.md) and related architecture docs. When request/response schemas evolve, the proto contract and those architecture sections must be updated in the same change so launch-resolution and template-phase semantics do not drift.

## REST & gRPC Endpoints

Default ports: REST on `8080`, gRPC on `6565`.

### REST

- `GET /ping` – basic health check returning `"pong"`.
- `POST /assets` – upload a binary asset for a tenant; the service streams bytes to object storage and persists asset metadata in PostgreSQL. This is a bypass-safe Game Design creator write when routed externally through Gateway because it is tenant-scoped, domain-local to Game Design, guarded by tenant access checks, and does not depend on Logging & Admin-owned policy or cross-domain write orchestration.
- `POST /templates` – create a new game template. This is a bypass-safe Game Design creator write when routed externally through Gateway because it is tenant-scoped, domain-local to Game Design, guarded by tenant access checks, and does not depend on Logging & Admin-owned policy or cross-domain write orchestration.
- `GET /templates` – list templates for a tenant.

Externally routed Game Design REST paths use the Gateway allowlist prefix (for example `/api/design/assets` and `/api/design/templates`) and are stripped to the service-local paths above before reaching Game Design. Game Design owns validation and domain audit behavior for these creator workflows. Operator writes for moderation, quota overrides, runtime feature-flag overrides, and tick remediation remain Logging & Admin ingress only and are not part of this bypass-safe creator-write class.

```bash
curl http://localhost:8080/ping
```

Detailed request and response schemas are defined in the [OpenAPI specification](../../../../services/game-design-service/src/main/resources/openapi.yaml).

### gRPC

- `Ping(PingRequest) returns (PingResponse)` – connectivity check defined in [`game_design_service.proto`](../../../../protos/game-design/v1/game_design_service.proto).
- `SaveRevision(SaveRevisionRequest) returns (SaveRevisionResponse)` – persists a version-scoped design change and can return the typed result of an applied world-design mutation when the request carries `worldDesignMutation`, including scoped world-generation-subtree writes.
- `PublishVersion(PublishVersionRequest) returns (PublishVersionResponse)` – publishes a frozen version. Callers must provide a stable `publish_request_id` so retries converge on one durable Temporal workflow identity. When Temporal is enabled, the durable publish / release-attestation work runs through the Game Design `publish` workflow family while preserving the same synchronous API contract: success is returned only after terminal workflow success and commit of `published_release_bundle`/release attestation; timeout or failure is surfaced explicitly (as an error or in-progress result where the caller surface supports that), and no half-complete success is exposed.
- `PublishScriptPatchVersion(PublishScriptPatchVersionRequest) returns (PublishScriptPatchVersionResponse)` – publishes a script-only patch version.
- `GetPublishedScriptPatchVersion(GetPublishedScriptPatchVersionRequest) returns (GetPublishedScriptPatchVersionResponse)` – returns the immutable script-patch publication read model, including base version, lifecycle state, digest identity, and last-changed time.
- `UploadPluginBundle(UploadPluginBundleRequest) returns (UploadPluginBundleResponse)` – ingests a signed plugin bundle, verifies signatures against the configured signer allowlist, persists the raw bundle under Game Design-owned storage, and records `SIGNATURE_VERIFIED` design-time metadata keyed by `(tenantId, pluginId, pluginVersionId)`.
- `PublishPluginVersion(PublishPluginVersionRequest) returns (PublishPluginVersionResponse)` – promotes a previously uploaded plugin bundle into `PUBLISHED` after base-version attestation checks, policy validation, and plugin distribution-manifest export.
- `RevokePluginVersion(RevokePluginVersionRequest) returns (RevokePluginVersionResponse)` – transitions a design-time plugin publication to `REVOKED_DESIGN`, preserving its readable publication history and making it ineligible for future activation.
- `ListPluginVersionStatusEvents(ListPluginVersionStatusEventsRequest) returns (ListPluginVersionStatusEventsResponse)` – returns bounded, filtered append-only plugin publication status-event history.
- `AppendPluginVersionSignatureEvidence(AppendPluginVersionSignatureEvidenceRequest) returns (AppendPluginVersionSignatureEvidenceResponse)` (target only) – verifies and appends one Ed25519 signed-intake-v1 evidence entry for an existing signed plugin version in `SIGNATURE_VERIFIED` or `PUBLISHED`, without changing immutable payload/publication content, status, or `bundleDigest`; it cannot create evidence for an unsigned package, and the current proto has no such RPC.
- `GetPublishedPluginVersion(GetPublishedPluginVersionRequest) returns (GetPublishedPluginVersionResponse)` – returns the immutable plugin publication read model, including base version, publication state, bundle digest, and distribution-manifest metadata. Target responses also expose the canonical ordered complete `verifiedSignatures[]` view composed from the immutable initial signed-intake evidence plus the append-only evidence ledger; the current response exposes only the singular selected `signerKeyId`.
- `ListPluginVersionStatuses(ListPluginVersionStatusesRequest) returns (ListPluginVersionStatusesResponse)` – lists immutable plugin publication rows for a tenant with optional `pluginId`, `publicationState`, `changedAfterMs`, `changedBeforeMs`, and bounded `limit` filters. Target rows expose the same composed `verifiedSignatures[]` view; current rows expose only the singular selected `signerKeyId`.
- `ListVersions(ListVersionsRequest) returns (ListVersionsResponse)` – lists available versions.
- `GetVersionState(GetVersionStateRequest) returns (GetVersionStateResponse)` – reads authoritative version lifecycle state and CAS epoch.
- `CompareAndSetVersionState(CompareAndSetVersionStateRequest) returns (CompareAndSetVersionStateResponse)` – performs CAS-guarded lifecycle transitions.
- `GetDesignControlPlaneDigest(GetDesignControlPlaneDigestRequest) returns (GetDesignControlPlaneDigestResponse)` – returns normalized metadata digest used by publish gates.
- `GetTemplateReferencePhase(GetTemplateReferencePhaseRequest) returns (GetTemplateReferencePhaseResponse)` – returns the persisted normalized-reference enforcement phase (`BACKFILLING`, `VALIDATED`, `ENFORCED`) used by instance-creation and retirement workflows.
- `GetPublishedReleaseBundle(GetPublishedReleaseBundleRequest) returns (GetPublishedReleaseBundleResponse)` – returns the immutable `(tenantId, versionId)` release attestation including participant digests, any required `artifactDigests[]` and `requiredManifestAssetKeys[]` for exported derived assets, `manifestHash`, `generationConfigRevision`, and the canonical publish workflow runtime metadata (`publishWorkflowId`, `workflowRunId`, `workflowStatus`). Missing attestation must surface as `NOT_FOUND`, and unreadable attestation schema must surface as `SCHEMA_VERSION_UNSUPPORTED`.
- `ResolveLaunchDescriptor(ResolveLaunchDescriptorRequest) returns (ResolveLaunchDescriptorResponse)` – resolves template metadata and control-plane inputs into one immutable launch descriptor for a game-instance creation attempt.
  Missing attestation must surface as `RELEASE_BUNDLE_NOT_FOUND`, unsupported attestation schema as `SCHEMA_VERSION_UNSUPPORTED`, and cross-version replacement launches without exactly one approved persisted remap set for the source/target pair as `LAUNCH_REMAP_REQUIRED`.
- `CreateTemplateRemapSet(CreateTemplateRemapSetRequest) returns (CreateTemplateRemapSetResponse)` – persists a draft remap set for a concrete `(tenantId, sourceVersionId, targetVersionId)` pair.
- `ApproveTemplateRemapSet(ApproveTemplateRemapSetRequest) returns (ApproveTemplateRemapSetResponse)` – marks a persisted remap set approved for launch/cutover consumers and records approval reason.
- `GetTemplateRemapSet(GetTemplateRemapSetRequest) returns (GetTemplateRemapSetResponse)` – returns the canonical remap-set payload and approval state for an existing `remapSetId`.
- `CanDeleteVersionAssets(CanDeleteVersionAssetsRequest) returns (CanDeleteVersionAssetsResponse)` – validates whether version-scoped assets are purge-eligible.
- `BeginPurgeVersionAssets(BeginPurgeVersionAssetsRequest) returns (BeginPurgeVersionAssetsResponse)` – CAS-guarded purge start that atomically re-checks deletion eligibility and transitions `version_asset_artifact` into purge-in-progress state.
- `FinalizePurgeVersionAssets(FinalizePurgeVersionAssetsRequest) returns (FinalizePurgeVersionAssetsResponse)` – CAS-guarded purge completion that transitions purge-in-progress artifacts to `PURGED` after byte-deletion confirmation.

#### `AppendPluginVersionSignatureEvidence` (target only)

This target mutation belongs to Game Design and is separate from runtime plugin activation. It is a signed-intake-only supplement to `UploadPluginBundle`: the target row must have entered the lifecycle through the allowlisted Ed25519 signed-intake path, and an unsigned package cannot use this RPC to manufacture signature evidence. Unsigned operator-permitted intake, when implemented under ADR 0111, needs its own platform-acceptance/approval evidence path and is not represented by this signature ledger. For signed intake, the mutation appends to an immutable evidence ledger; it never rewrites the uploaded bundle, signed payload, `pluginVersionId`, `bundleDigest`, publication status, or publication content. It is allowed only while the design-time status is `SIGNATURE_VERIFIED` or `PUBLISHED`; other statuses fail deterministically without mutation. A successful append on a `PUBLISHED` version does not republish or change its status, and an append on `SIGNATURE_VERIFIED` leaves that status unchanged.

The owner first resolves `controlPlaneRequestId` and the normalized request digest: an exact terminal retry returns its stored result before current status/evidence validation, while changed-digest reuse returns `IDEMPOTENCY_CONFLICT` without mutation. Only a new request evaluates current `SIGNATURE_VERIFIED`/`PUBLISHED` eligibility. The owner serializes this mutation with `RevokePluginVersion` status transitions and `PublishPluginVersion` supersession of older versions. Under one owner-row/status-revision transaction, it rechecks status eligibility and commits the duplicate/conflict decision, the ledger append when applicable, the idempotency result, and exactly one `PluginVersionSignatureEvidenceAppended` outbox event if and only if the outcome is `APPENDED`; an identical no-op or conflict has no ledger append or event. Revoke, supersede, and evidence append therefore resolve as one ordered owner decision, never as a partially applied combination.

Signature verification uses the canonical [Signing and Key Lifecycle](modding-framework.md#signing-and-key-lifecycle-required) owner contract: resolve the exact stored immutable `bundleDigest` for `(tenantId, pluginId, pluginVersionId)`, require it to equal the expected `bundleDigest`, and verify the appended Ed25519 entry against the signed-intake-v1 envelope and preimage. The resulting canonical `verifiedSignatures[]` is the complete ordered set of the immutable initial signed-intake evidence plus append-only evidence and retains the owner-defined key metadata; this API defines no separate algorithm, schema, or domain-separator scheme.

Inputs:

- exact `(tenantId, pluginId, pluginVersionId)`;
- expected `bundleDigest`;
- one bounded Ed25519 signed-intake-v1 evidence entry containing the signer identity, signature bytes, and optional creation metadata; and
- a stable `controlPlaneRequestId`.

The normalized request digest includes the complete plugin identity, expected digest, and canonical evidence entry. Outputs return the exact plugin identity, unchanged publication status, immutable `bundleDigest`, the canonical ordered complete `verifiedSignatures[]` set composed from the immutable initial signed-intake evidence plus the append-only ledger, and an `appendOutcome` of `APPENDED` or `IDENTICAL_EVIDENCE_NOOP`. Only `APPENDED` creates one durable `PluginVersionSignatureEvidenceAppended` event; exact retries return the stored result without another append or event. Reusing a request ID with a changed normalized digest is an idempotency conflict. A request carrying identical existing signer evidence is an idempotent no-op; conflicting same-signer evidence, digest mismatch, malformed or over-limit evidence, invalid cryptographic verification, no allowlisted signer, or any revoked signer fails deterministically without mutation.

### Script-patch and plugin transition consequences

`PublishScriptPatchVersion` and `PublishPluginVersion` are design-time publication operations. They persist immutable authoring metadata and compatibility evidence but do not activate a running instance, allocate a script pin epoch, or write rollout history. A patch must pass the normal publication boundary before Automation can perform tenant readiness; readiness still does not imply an instance pin. **Target-state ownership:** Game Session owns the exact instance pin, epoch, and committed append-only rollout/rollback history, while Automation owns readiness, artifact loading, and observed convergence. The current Game Session implementation does not yet prove complete epoch propagation or Game-Session-owned append-only rollout history; see [Game Session API implementation notes](../game-session-service/api-contracts.md#implementation-notes). This document does not claim those target-state consequences are live.

Creator/operator read models must therefore distinguish:

- design-time publication (`PUBLISHED`, or a design-time failure);
- tenant runtime readiness (`READY`, `FAILED`, or an intermediate state) from Automation; and
- the authoritative instance pin/epoch and rollout history from Game Session.

Game Design must not infer a rollback or repin from event arrival, readiness rows, or plugin activation state. Plugin publication and instance activation remain separate API lifecycles, and plugin graphs use the same DSL/sandbox as embedded scripts while retaining independent immutable plugin identity.

```bash
grpcurl -plaintext localhost:6565 game_design.v1.GameDesignService/Ping
```

## Related Documentation

- [Game Templates and Configuration Tools](game-templates.md)
- [Asset Storage Setup](asset-storage.md)
- [Version Control for Design Assets](version-control.md)
- [In-Game Modding and Plugin Framework](modding-framework.md)
