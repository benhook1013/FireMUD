# Deployment Evidence Records

This directory stores environment deployment evidence used by architecture contracts.

## Current Implementation Status

These paths define the canonical target-state evidence locations, but current enforcement is partial. Static overlay CI validates the checked-in evidence shape and available bindings. Production promotion preflight blocks every promotion class at the incomplete finalized-baseline authority check before staging digest lineage, the expanded backup-readiness envelope, or nested recovery-controller evidence can be evaluated; those diagnostics remain unimplemented on the executable production path. Production first-live and reopen preflight also fails closed because the durable recovery-controller read is not implemented. Hobby traffic-open preflight validates schema, environment/event/deployment binding, compliance status, evidence references, and the referenced operator preflight report, then fails closed because compliance freshness and live current-event recovery-controller authority are not implemented. Executable waivers and consumed reports containing `waiverPath` fail closed until trusted one-time consumption authority exists. Checked-in recovery and traffic-open files are post-finalization evidence projections; the durable recovery controller and its linked immutable evidence are pre-release authority.

- `staging/deployments/<overlayCommitSha>/<deploymentEventId>.json`: immutable staging apply records used by production promotion attestation validation.
- `staging/recovery/<recovery-ref>.json`: staging post-restore sanitization evidence required before reopening traffic when restoring production-origin data.
- `production/attestations/<deployment-ref>.json`: production promotion attestation artifacts referenced by overlay PRs.
- `production/release-manifests/<release-tag-or-deployment-ref>.json`: release digest manifests binding official release tags or deployment refs to the promoted digest set.
- `production/recovery/<recovery-ref>.json`: production post-restore recovery evidence including external credential validation results.
- `production/backup-readiness/<deployment-ref>.json`: full production recovery evidence for `roll-forward-only` promotions and releases whose compatibility result requires a new drill.
- `production/traffic-open/<event>-<deployment-ref>.json`: post-finalization production first-live/reopen projection bound to the canonical preflight report, the production backup binding, an `environment-wide-postgresql` artifact, and finalized `cold_start_restore` recovery-controller lineage. Tenant/region scope and standalone restore-drill timestamps are not traffic-open authority.
- `<environment>/preflight/<deployment-ref>/<deploymentEventId>.json`: immutable preflight policy reports for one concrete run/apply event.
- `<environment>/preflight/<deployment-ref>/<deploymentEventId>.waiver.json`: target-state break-glass waiver records bound to the report's deployment event UUID; not currently executable.
- `hobby-self-hosted/deployments/<deployment-ref>/<deploymentEventId>.json`: immutable hobby deploy evidence records.
- `hobby-self-hosted/traffic-open/<deployment-ref>.json`: hobby traffic-open evidence bound to the canonical hobby preflight report and backup-compliance record.
- `hobby-self-hosted/recovery/<recovery-ref>.json`: hobby restore-hardening evidence records.
