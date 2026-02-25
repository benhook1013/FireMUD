# Staging Deployment Records

Store one deployment record per staging overlay apply as:

- `<stagingOverlayCommitSha>.json`

Required fields:

- `environment` (`staging`)
- `overlayCommitSha`
- `appliedAt`
- `appliedBy`
- `serviceDigests`
- `preflightReportPath`
- `smokeEvidence`

Production promotion attestation validation depends on these records.
