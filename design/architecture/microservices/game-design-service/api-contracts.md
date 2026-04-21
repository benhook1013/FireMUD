# Game Design Service API Contracts

## Overview

The protobuf service definitions under [../../../../protos/game-design/v1](../../../../protos/game-design/v1) are the authoritative wire-contract source for Game Design gRPC APIs. Architecture-doc JSON examples are normative for semantics and invariants, but field names and enums must ultimately converge on the proto definitions.

For REST endpoints, the authoritative request/response schema source is [openapi.yaml](../../../../services/game-design-service/src/main/resources/openapi.yaml). Design-doc examples should be updated to match when those schemas evolve.

## Implementation Status

The release-attestation, launch-resolution, version-state, settings, and asset-purge APIs are live in the current proto/service path. Plugin publication APIs (`UploadPluginBundle`, `PublishPluginVersion`, `GetPublishedPluginVersion`, and `ListPluginVersionStatuses`) are target-state contracts defined for the modding slice and must be added to the proto/service implementation before clients depend on them as live wire contracts.

## gRPC APIs

- `SaveRevision` – persists a new or updated design asset.
- `PublishVersion` – freezes a set of revisions and notifies downstream services.
- `PublishScriptPatchVersion` – creates a script-only patch version referencing a base version.
- `UploadPluginBundle` – stores a signed plugin bundle, verifies archive safety and signatures, extracts indexed manifest metadata, and records the pre-publication design-time status.
- `PublishPluginVersion` – runs design-time validation for an uploaded plugin bundle version and transitions it into immutable publication history when validation succeeds.
- `GetPublishedPluginVersion` / `ListPluginVersionStatuses` – authoritative design-time read APIs for plugin publication lifecycle, signer verification status, and validation outcomes.
- `ListVersions` – enumerates published versions for selection when creating a game instance.
- `GetVersionState` / `CompareAndSetVersionState` – authoritative control-plane version lifecycle reads and CAS transitions. These APIs are now live in the proto/service path and are the canonical owner for `versionStateEpoch`.
- `GetDesignControlPlaneDigest` – digest surface for publish gating over normalized metadata.
- `GetTemplateReferencePhase` – returns the persisted normalized-reference enforcement phase (`BACKFILLING`, `VALIDATED`, `ENFORCED`) used by instance-creation and retirement workflows.
- `GetPublishedReleaseBundle` – authoritative read surface for immutable release attestation used by activation, cutover preflight, and repair workflows. `NOT_FOUND` means the target version is not publish-complete; `SCHEMA_VERSION_UNSUPPORTED` means callers must fail closed until they understand the attestation schema.
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
- `POST /assets` – upload a binary asset for a tenant; the service streams bytes to object storage and persists asset metadata in PostgreSQL.
- `POST /templates` – create a new game template.
- `GET /templates` – list templates for a tenant.

```bash
curl http://localhost:8080/ping
```

Detailed request and response schemas are defined in the [OpenAPI specification](../../../../services/game-design-service/src/main/resources/openapi.yaml).

### gRPC

- `Ping(PingRequest) returns (PingResponse)` – connectivity check defined in [`game_design_service.proto`](../../../../protos/game-design/v1/game_design_service.proto).
- `SaveRevision(SaveRevisionRequest) returns (SaveRevisionResponse)` – persists a design change.
- `PublishVersion(PublishVersionRequest) returns (PublishVersionResponse)` – publishes a frozen version.
- `PublishScriptPatchVersion(PublishScriptPatchVersionRequest) returns (PublishScriptPatchVersionResponse)` – publishes a script-only patch version.
- `ListVersions(ListVersionsRequest) returns (ListVersionsResponse)` – lists available versions.
- `GetVersionState(GetVersionStateRequest) returns (GetVersionStateResponse)` – reads authoritative version lifecycle state and CAS epoch.
- `CompareAndSetVersionState(CompareAndSetVersionStateRequest) returns (CompareAndSetVersionStateResponse)` – performs CAS-guarded lifecycle transitions.
- `GetDesignControlPlaneDigest(GetDesignControlPlaneDigestRequest) returns (GetDesignControlPlaneDigestResponse)` – returns normalized metadata digest used by publish gates.
- `GetTemplateReferencePhase(GetTemplateReferencePhaseRequest) returns (GetTemplateReferencePhaseResponse)` – returns the persisted normalized-reference enforcement phase (`BACKFILLING`, `VALIDATED`, `ENFORCED`) used by instance-creation and retirement workflows.
- `GetPublishedReleaseBundle(GetPublishedReleaseBundleRequest) returns (GetPublishedReleaseBundleResponse)` – returns the immutable `(tenantId, versionId)` release attestation including participant digests, any required `artifactDigests[]` and `requiredManifestAssetKeys[]` for exported derived assets, `manifestHash`, and `generationConfigRevision`. Missing attestation must surface as `NOT_FOUND`, and unreadable attestation schema must surface as `SCHEMA_VERSION_UNSUPPORTED`.
- `ResolveLaunchDescriptor(ResolveLaunchDescriptorRequest) returns (ResolveLaunchDescriptorResponse)` – resolves template metadata and control-plane inputs into one immutable launch descriptor for a game-instance creation attempt.
  Missing attestation must surface as `RELEASE_BUNDLE_NOT_FOUND`, unsupported attestation schema as `SCHEMA_VERSION_UNSUPPORTED`, and cross-version replacement launches without exactly one approved persisted remap set for the source/target pair as `LAUNCH_REMAP_REQUIRED`.
- `CreateTemplateRemapSet(CreateTemplateRemapSetRequest) returns (CreateTemplateRemapSetResponse)` – persists a draft remap set for a concrete `(tenantId, sourceVersionId, targetVersionId)` pair.
- `ApproveTemplateRemapSet(ApproveTemplateRemapSetRequest) returns (ApproveTemplateRemapSetResponse)` – marks a persisted remap set approved for launch/cutover consumers and records approval reason.
- `GetTemplateRemapSet(GetTemplateRemapSetRequest) returns (GetTemplateRemapSetResponse)` – returns the canonical remap-set payload and approval state for an existing `remapSetId`.
- `CanDeleteVersionAssets(CanDeleteVersionAssetsRequest) returns (CanDeleteVersionAssetsResponse)` – validates whether version-scoped assets are purge-eligible.
- `BeginPurgeVersionAssets(BeginPurgeVersionAssetsRequest) returns (BeginPurgeVersionAssetsResponse)` – CAS-guarded purge start that atomically re-checks deletion eligibility and transitions `version_asset_artifact` into purge-in-progress state.
- `FinalizePurgeVersionAssets(FinalizePurgeVersionAssetsRequest) returns (FinalizePurgeVersionAssetsResponse)` – CAS-guarded purge completion that transitions purge-in-progress artifacts to `PURGED` after byte-deletion confirmation.

```bash
grpcurl -plaintext localhost:6565 game_design.v1.GameDesignService/Ping
```

## Related Documentation

- [Game Templates and Configuration Tools](game-templates.md)
- [Asset Storage Setup](asset-storage.md)
- [Version Control for Design Assets](version-control.md)
- [In-Game Modding and Plugin Framework](modding-framework.md)
