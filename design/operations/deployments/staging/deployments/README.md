# Staging Deployment Records

Store one immutable deployment record per staging overlay apply as:

- `<overlayCommitSha>/<deploymentEventId>.json`

Required fields:

- `environment` (`staging`)
- `overlayCommitSha`
  - must equal the directory name
- `deploymentEventId` (canonical UUID matching the consumed preflight report)
- `appliedAt`
- `appliedBy`
- `deployStatus` (`pass`)
- `smokeStatus` (`pass`)
- `serviceDigests`
- `preflightReportPath`
  - must equal `design/operations/deployments/staging/preflight/<overlayCommitSha>/<deploymentEventId>.json`
  - report `completedAt` must not be later than `appliedAt`
  - report `completedAt` must be no more than 30 minutes before `appliedAt`
- `liveStateEvidence`
  - `status` (`pass`)
  - `observedOverlaySha`
  - `observedDigests`
- `secretComplianceSnapshotAt`
- `secretComplianceStatus`
- `secretComplianceEvidenceRef`
- `smokeEvidence`

Target-state promotion-lineage fields (required when this record is selected as production-attestation or production-promotion evidence; the current record producer and validator do not yet emit or enforce them):

- `jwtCustodyProof` – object with exactly `proofId`, `custodyMode`, and `contractVersion`, copied from the consumed operator preflight report's authorizing `jwtCustodyProof` without substitution or mode reselection.
- `jwtRotationEvidenceRef` – immutable, digest-qualified reference to the passing `PREFLIGHT-JWT-ROTATION-001` evidence for this staging deployment event and the same custody tuple.

The selected record's `jwtCustodyProof` and `jwtRotationEvidenceRef` must match the production candidate's applicable custody and rotation contract, and its `preflightReportPath` must resolve to the event-scoped operator report whose `deploymentEventId` matches this record and whose `PREFLIGHT-JWT-ROTATION-001` result is `pass`. This staging lineage is supplemental evidence and never replaces production event-bound preflight proof.

The canonical projection from environment `provisioningState` and bootstrap operation/evidence state into `secretComplianceStatus` and `secretComplianceEvidenceRef` is owned by [Environment Variables & Secrets Overview](../../../../architecture/infrastructure/environment-and-secrets-overview.md#secret-compliance-controls). This directory records the deployment-local snapshot and consequence; it does not redefine compliance precedence.

Production promotion attestation validation depends on these records.
