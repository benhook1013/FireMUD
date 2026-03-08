# Staging Deployment Records

Store one deployment record per staging overlay apply as:

- `<stagingOverlayCommitSha>.json`

Required fields:

- `environment` (`staging`)
- `overlayCommitSha`
- `appliedAt`
- `appliedBy`
- `deployStatus` (`pass`)
- `smokeStatus` (`pass`)
- `serviceDigests`
- `preflightReportPath`
- `liveStateEvidence`
  - `status` (`pass`)
  - `observedOverlaySha`
  - `observedDigests`
- `secretComplianceSnapshotAt`
- `secretComplianceStatus`
- `secretComplianceEvidenceRef`
- `smokeEvidence`

Production promotion attestation validation depends on these records.
