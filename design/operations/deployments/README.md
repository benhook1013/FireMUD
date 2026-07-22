# Deployment Evidence Records

This directory stores environment deployment evidence used by architecture contracts.

## Current Implementation Status

These paths define the canonical target-state evidence locations, but current enforcement is partial. Preflight validates the basic schema, environment/deployment binding, freshness, evidence references, and existing digest or attestation checks described by each record. Expanded recovery-controller lineage, environment-wide artifact and confidentiality proof, mandatory post-finalization projection refresh, and current-event recovery-lineage rejection remain target-state until the stacked recovery-operations enforcement lands. Checked-in recovery and traffic-open files are evidence projections; the durable recovery controller is runtime authority.

- `staging/deployments/<overlayCommitSha>.json`: staging apply records used by production promotion attestation validation.
- `staging/recovery/<recovery-ref>.json`: staging post-restore sanitization evidence required before reopening traffic when restoring production-origin data.
- `production/attestations/<deployment-ref>.json`: production promotion attestation artifacts referenced by overlay PRs.
- `production/release-manifests/<release-tag-or-deployment-ref>.json`: release digest manifests binding official release tags or deployment refs to the promoted digest set.
- `production/recovery/<recovery-ref>.json`: production post-restore recovery evidence including external credential validation results.
- `production/backup-readiness/<deployment-ref>.json`: production backup-readiness evidence for `roll-forward-only` promotions.
- `production/traffic-open/<event>-<deployment-ref>.json`: post-finalization production traffic-open projection bound to the canonical production preflight report and environment-wide recovery-controller lineage.
- `<environment>/preflight/<deployment-ref>.json`: preflight policy reports.
- `<environment>/preflight/<deployment-ref>.waiver.json`: break-glass waiver records for a single deployment event.
- `hobby-self-hosted/deployments/<deployment-ref>.json`: hobby deploy evidence records.
- `hobby-self-hosted/traffic-open/<deployment-ref>.json`: hobby traffic-open evidence bound to the canonical hobby preflight report and backup-compliance record.
- `hobby-self-hosted/recovery/<recovery-ref>.json`: hobby restore-hardening evidence records.
