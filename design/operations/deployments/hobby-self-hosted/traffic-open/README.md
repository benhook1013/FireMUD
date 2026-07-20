# Hobby Traffic-Open Evidence

Store one record per `first-live` or `reopen` hobby traffic-open event as:

- `<deployment-ref>.json`

Current implementation note:

`dev-tools/deploy/write-traffic-open-evidence.py` does not yet write or validate the accepted cold-start recovery references and two-phase traffic-open lifecycle. Its output cannot authorize player-facing first-live or reopen until it implements this contract.

Required fields:

- `schemaVersion` (`traffic-open-record/v1`)
- `environment` (`hobby-self-hosted`)
- `eventType`
- `trafficOpenStatus` (`finalized` in the checked-in projection; runtime authorization is held by the recovery controller)
- `deploymentRef`
- `assessedAt`
- `assessedBy`
- `backupComplianceRef`
- `baselineRecoveryRecordRef`
- `actualRecoveryRecordRef` when `eventType=reopen` (durable actual-recovery controller reference; checked-in projection follows finalization)
- `preflightReportPath`
- `trafficOpenedAt` when finalized
- `evidenceRefs`

Hobby preflight requires the traffic-open projection to reference the canonical backup-compliance file, the current environment-wide cold-start baseline, and a successful hobby preflight report that consumed `design/operations/environments/hobby-self-hosted/expected-bindings.yaml`. Reopen additionally reads the durable actual-recovery controller in `ready_to_reopen`; the controller idempotently reconciles through `releasing`, applies and observes quarantine release, and reaches `finalized` before traffic flows. The exporter writes both immutable projections only afterward.
