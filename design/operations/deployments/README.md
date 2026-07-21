# Deployment Evidence Records

This directory stores environment deployment evidence used by architecture contracts.

- `staging/deployments/<overlayCommitSha>.json`: staging apply records used by production promotion attestation validation.
- `staging/recovery/<recovery-ref>.json`: staging post-restore sanitization evidence required before reopening traffic when restoring production-origin data.
- `production/attestations/<deployment-ref>.json`: production promotion attestation artifacts referenced by overlay PRs.
- `production/release-manifests/<release-tag-or-deployment-ref>.json`: release digest manifests binding official release tags or deployment refs to the promoted digest set.
- `production/recovery/<recovery-ref>.json`: production post-restore recovery evidence including external credential validation results.
- `production/backup-readiness/<deployment-ref>.json`: full production recovery evidence for `roll-forward-only` promotions and releases whose compatibility result requires a new drill.
- `production/traffic-open/<event>-<deployment-ref>.json`: production first-live/reopen evidence bound to the canonical preflight report, the production backup binding, an `environment-wide-postgresql` artifact, and finalized `cold_start_restore` recovery records. Tenant/region scope and standalone restore-drill timestamps are not traffic-open authority.
- `<environment>/preflight/<deployment-ref>.json`: preflight policy reports.
- `<environment>/preflight/<deployment-ref>.waiver.json`: break-glass waiver records for a single deployment event.
- `hobby-self-hosted/deployments/<deployment-ref>.json`: hobby deploy evidence records.
- `hobby-self-hosted/traffic-open/<deployment-ref>.json`: hobby traffic-open evidence bound to the canonical hobby preflight report and backup-compliance record.
- `hobby-self-hosted/recovery/<recovery-ref>.json`: hobby restore-hardening evidence records.
