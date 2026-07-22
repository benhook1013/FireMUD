# Staging Deployment Records

Store one deployment record per staging overlay apply as:

- `<stagingOverlayCommitSha>.json`

Required fields:

- `environment` (`staging`)
- `overlayCommitSha`
- `deploymentEventId` (canonical UUID matching the consumed preflight report)
- `appliedAt`
- `appliedBy`
- `deployStatus` (`pass`)
- `smokeStatus` (`pass`)
- `serviceDigests`
- `preflightReportPath`
  - must equal `design/operations/deployments/staging/preflight/<overlayCommitSha>.json`
  - report `completedAt` must not be later than `appliedAt`
- `liveStateEvidence`
  - `status` (`pass`)
  - `observedOverlaySha`
  - `observedDigests`
- `secretComplianceSnapshotAt`
- `secretComplianceStatus`
- `secretComplianceEvidenceRef`
- `smokeEvidence`

Production promotion attestation validation depends on these records.
