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
- `deploymentEventId` (must equal the UUID in `preflightReportPath` and the `<deployment-event-id>` filename component)
- `assessedAt`
- `assessedBy`
- `backupComplianceRef`
- `baselineRecoveryRecordRef`
- `actualRecoveryRecordRef` (durable actual-recovery controller reference for first-live or reopen; checked-in projection follows finalization)
- `playerFacingTargetBoundary`
- `preflightReportPath`
- `trafficOpenedAt` when finalized
- `evidenceRefs`

The traffic-open projection is post-finalization retained output and is not a preflight input or release authority. Before first-live or reopen, preflight consumes the canonical backup-compliance record, general preflight evidence, immutable pre-release recovery evidence, and the durable actual-recovery controller in fresh `ready_to_reopen`, with an event-matching player-facing target boundary and `PREFLIGHT-BACKUP-003=pass`; `releasing` is not authorization. `continueRecovery(operationId, expectedPhase, evidenceRef)` idempotently reconciles through the internal `releasing` phase, applies and observes quarantine release, and reaches `finalized` before traffic flows. The exporter writes both immutable projections only afterward, preserving the matching `deploymentEventId` and `preflightReportPath` lineage.
