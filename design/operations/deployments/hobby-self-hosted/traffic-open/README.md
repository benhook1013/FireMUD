# Hobby Traffic-Open Evidence

Store one record per `first-live` or `reopen` hobby traffic-open event as:

- `<deployment-ref>/<deployment-event-id>.json`

## Implementation Status

`dev-tools/deploy/write-traffic-open-evidence.py` does not yet write or validate the accepted cold-start recovery references and two-phase traffic-open lifecycle. Its output cannot authorize player-facing first-live or reopen until it implements this contract.

Required fields:

- `schemaVersion` (`traffic-open-record/v1`)
- `environment` (`hobby-self-hosted`)
- `eventType`
- `trafficOpenStatus` (`finalized` in the checked-in projection; runtime authorization is held by the recovery controller)
- `deploymentRef`
- `deploymentEventId` (must equal the referenced preflight report)
- `assessedAt`
- `assessedBy`
- `backupComplianceRef`
- `baselineRecoveryRecordRef`
- `actualRecoveryRecordRef` (durable actual-recovery controller reference for first-live or reopen; checked-in projection follows finalization)
- `playerFacingTargetBoundary`
- `preflightReportPath`
- `trafficOpenedAt` when finalized
- `evidenceRefs`

Hobby preflight requires the traffic-open projection to reference the canonical backup-compliance file, the current environment-wide cold-start baseline with immutable erasure high-water capture and gap-free replay, and a successful hobby preflight report with the same `deploymentEventId` that consumed `design/operations/environments/hobby-self-hosted/expected-bindings.yaml`. First-live and reopen additionally read the durable actual-recovery controller in `ready_to_reopen`, verify the event-matching player-facing target boundary, and require `PREFLIGHT-BACKUP-003=pass`; `continueRecovery(operationId, expectedPhase, evidenceRef)` idempotently reconciles through the internal `releasing` phase, applies and observes quarantine release, and reaches `finalized` before traffic flows. The exporter writes both immutable projections only afterward.
