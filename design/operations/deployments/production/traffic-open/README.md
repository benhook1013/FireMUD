# Production Traffic-Open Evidence

Store one record per `first-live` or `reopen` production traffic-open event as:

- `<event>-<deployment-ref>.json`

Canonical writer:

- `python3 dev-tools/deploy/write-traffic-open-evidence.py production <deployment-ref> <first-live|reopen> --assessed-by <operator> --preflight-report design/operations/deployments/production/preflight/<deployment-ref>.json --backup-last-success-at <timestamp> --backup-verify-last-success-at <timestamp> --restore-drill-last-success-at <timestamp> --tenant-id <tenant> --region-id <region> --evidence-ref <ref>`

Required fields:

- `schemaVersion` (`traffic-open-record/v1`)
- `environment` (`production`)
- `eventType`
- `deploymentRef`
- `assessedAt`
- `assessedBy`
- `preflightReportPath`
- `backupLastSuccessAt`
- `backupVerifyLastSuccessAt`
- `restoreDrillLastSuccessAt`
- `coordinatedBackupScope`
  - `type` (`tenant_region`)
  - `tenantId`
  - `regionId`
- `evidenceRefs`

Production preflight now requires the referenced production preflight report to exist, target the canonical production expected-bindings manifest, and contain no failing required checks before traffic-open backup evidence passes.
