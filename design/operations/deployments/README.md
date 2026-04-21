# Deployment Evidence Records

This directory stores environment deployment evidence used by architecture contracts.

- `staging/deployments/<overlayCommitSha>.json`: staging apply records used by production promotion attestation validation.
- `staging/recovery/<recovery-ref>.json`: staging post-restore sanitization evidence required before reopening traffic when restoring production-origin data.
- `production/attestations/<deployment-ref>.json`: production promotion attestation artifacts referenced by overlay PRs.
- `production/release-manifests/<release-tag-or-deployment-ref>.json`: release digest manifests binding official release tags or deployment refs to the promoted digest set.
- `production/recovery/<recovery-ref>.json`: production post-restore recovery evidence including external credential validation results.
- `production/backup-readiness/<deployment-ref>.json`: production backup-readiness evidence for `roll-forward-only` promotions.
- `<environment>/preflight/<deployment-ref>.json`: preflight policy reports.
- `<environment>/preflight/<deployment-ref>.waiver.json`: break-glass waiver records for a single deployment event.
- `hobby-self-hosted/deployments/<deployment-ref>.json`: hobby deploy evidence records.
- `hobby-self-hosted/recovery/<recovery-ref>.json`: hobby restore-hardening evidence records.
