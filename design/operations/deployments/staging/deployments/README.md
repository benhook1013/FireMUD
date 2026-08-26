# Staging Deployment Records

Store one immutable deployment record per staging overlay apply as:

- `<overlayCommitSha>/<deploymentEventId>.json`

## Implementation Status

The current record producer does not yet emit the promotion-lineage JWT fields. Production-attestation validation now enforces `jwtCustodyProof` and `jwtRotationEvidenceRef` for a selected staging record, so promotion remains blocked until the producer and its underlying custody/rotation proof are implemented. The current `_promotion_check` validates `serviceDigests`/`observedDigests` but does not validate `servicePlatformDigests`/`observedPlatformDigests`; the platform-child fields below are target-state contract coverage and remain an implementation gap rather than a reason to expand this docs-focused change.

Required fields:

- `environment` (`staging`)
- `overlayCommitSha`
  - must equal the directory name
- `deploymentEventId` (canonical UUID matching the consumed preflight report)
- `appliedAt`
- `appliedBy`
- `deployStatus` (`pass`)
- `smokeStatus` (`pass`)
- `serviceDigests` (map of each service to its exact staged immutable manifest or index reference)
- `servicePlatformDigests` (same service key set, with each service mapped from its exact admitted platform keys to immutable child-manifest references; for a single-platform direct-manifest `serviceDigests` reference, the one platform entry binds that same manifest, while for an index-backed reference, each entry binds the index descriptor's selected child manifest and never the index digest)
- `preflightReportPath`
  - must equal `design/operations/deployments/staging/preflight/<overlayCommitSha>/<deploymentEventId>.json`
  - report `completedAt` must not be later than `appliedAt`
  - report `completedAt` must be no more than 30 minutes before `appliedAt`
- `liveStateEvidence`
  - `status` (`pass`)
  - `observedOverlaySha`
  - `observedDigests` (must exactly equal top-level `serviceDigests`)
  - `observedPlatformDigests` (must exactly equal top-level `servicePlatformDigests`, including service keys, platform keys, and child-manifest references)
- `secretComplianceSnapshotAt`
- `secretComplianceStatus`
- `secretComplianceEvidenceRef`
- `smokeEvidence` (non-empty list of closed `{ref, contentDigest}` entries; each `contentDigest` is the SHA-256 digest of the exact retained JSON bytes, encoded exactly as `sha256:<64 lowercase hexadecimal characters>`, each artifact's `deploymentRef` matches `overlayCommitSha`, each artifact's `deploymentEventId` equals the deployment record's `deploymentEventId`, and `ref` values are unique)

Target-state promotion-lineage fields (required when this record is selected as production-attestation or production-promotion evidence):

- `jwtCustodyProof` – object with exactly `proofId`, `custodyMode`, and `contractVersion`, copied from the consumed operator preflight report's authorizing `jwtCustodyProof` without substitution or mode reselection.
- `jwtRotationEvidenceRef` – immutable, digest-qualified reference to the passing `PREFLIGHT-JWT-ROTATION-001` evidence for this staging deployment event and the same custody tuple.

The selected record's `jwtCustodyProof` and `jwtRotationEvidenceRef` must match the production candidate's applicable custody and rotation contract, and its `preflightReportPath` must resolve to the event-scoped operator report whose `deploymentEventId` matches this record and whose `PREFLIGHT-JWT-ROTATION-001` result is `pass`. This staging lineage is supplemental evidence and never replaces production event-bound preflight proof.

The canonical projection from environment `provisioningState` and bootstrap operation/evidence state into `secretComplianceStatus` and `secretComplianceEvidenceRef` is owned by [Environment Variables & Secrets Overview](../../../../architecture/infrastructure/environment-and-secrets-overview.md#secret-compliance-controls). This directory records the deployment-local snapshot and consequence; it does not redefine compliance precedence.

Production promotion attestation validation depends on these records.
