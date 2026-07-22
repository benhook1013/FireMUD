# Deployment Evidence Records

This directory stores environment deployment evidence used by architecture contracts.

## Current Implementation Status

These paths define the canonical target-state evidence locations, but current enforcement is partial. Production promotion preflight validates attestation and staging digest lineage, compact recovery-compatibility field shape, finalized baseline identity and freshness, and the expanded backup-readiness top-level envelope and available bindings. It intentionally fails roll-forward-only and drill-required promotion closed because complete nested recovery-controller, participant, confidentiality, hardening, and controlled-reopen evidence validation is not implemented. Production first-live and reopen preflight also fails closed because the durable recovery-controller read is not implemented. Hobby traffic-open preflight currently validates schema, environment/event/deployment binding, compliance status, evidence references, and the referenced preflight report, but does not yet validate compliance freshness or live current-event recovery-controller lineage. Expanded recovery-controller lineage, environment-wide artifact and confidentiality proof, mandatory post-finalization projection refresh, and current-event recovery-lineage rejection remain target-state. Checked-in recovery and traffic-open files are post-finalization evidence projections; the durable recovery controller and its linked immutable evidence are pre-release authority.

- `staging/deployments/<overlayCommitSha>.json`: staging apply records used by production promotion attestation validation.
- `staging/recovery/<recovery-ref>.json`: staging post-restore sanitization evidence required before reopening traffic when restoring production-origin data.
- `production/attestations/<deployment-ref>.json`: production promotion attestation artifacts referenced by overlay PRs.
- `production/release-manifests/<release-tag-or-deployment-ref>.json`: release digest manifests binding official release tags or deployment refs to the promoted digest set.
- `production/recovery/<recovery-ref>.json`: production post-restore recovery evidence including external credential validation results.
- `production/backup-readiness/<deployment-ref>.json`: full production recovery evidence for `roll-forward-only` promotions and releases whose compatibility result requires a new drill.
- `production/traffic-open/<event>-<deployment-ref>.json`: post-finalization production first-live/reopen projection bound to the canonical preflight report, the production backup binding, an `environment-wide-postgresql` artifact, and finalized `cold_start_restore` recovery-controller lineage. Tenant/region scope and standalone restore-drill timestamps are not traffic-open authority.
- `<environment>/preflight/<deployment-ref>.json`: preflight policy reports.
- `<environment>/preflight/<deployment-ref>.waiver.json`: break-glass waiver records for a single deployment event.
- `hobby-self-hosted/deployments/<deployment-ref>.json`: hobby deploy evidence records.
- `hobby-self-hosted/traffic-open/<deployment-ref>.json`: hobby traffic-open evidence bound to the canonical hobby preflight report and backup-compliance record.
- `hobby-self-hosted/recovery/<recovery-ref>.json`: hobby restore-hardening evidence records.
