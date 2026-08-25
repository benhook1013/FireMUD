# Hobby Traffic-Open Evidence

Store one record per `first-live` or `reopen` hobby traffic-open event as:

- `<deployment-ref>/<deploymentEventId>.json`

## Implementation Status

`dev-tools/deploy/write-traffic-open-evidence.py` does not yet write or validate the accepted cold-start recovery references and two-phase traffic-open lifecycle. Its output cannot authorize player-facing first-live or reopen until it implements this contract.

The complete required-field schema, conditional references, validation rules, and canonicalization/digest rules are owned by [Hobby Traffic-Open Evidence](../../../../architecture/system-architecture-backup-recovery-evidence-and-compliance.md#hobby-traffic-open-evidence).

The traffic-open projection is post-finalization retained output and is not a preflight input or release authority. Before first-live or reopen, preflight consumes the canonical backup-compliance record snapshot identified by `backupComplianceRef`, `backupComplianceRecordVersion`, and `backupComplianceRecordDigest`; it must resolve and verify the immutable retained record rather than dereference a mutable current `backup-compliance.yaml` alias. It also consumes general preflight evidence, immutable pre-release recovery evidence, and the durable actual-recovery controller in fresh `ready_to_reopen`, with an event-matching player-facing target boundary and `PREFLIGHT-BACKUP-003=pass`; `releasing` is not authorization. `continueRecovery(operationId, expectedPhase, evidenceRef)` idempotently reconciles through the internal `releasing` phase, applies and observes quarantine release, and reaches `finalized` before traffic flows. The exporter writes both immutable projections only afterward, preserving the matching `deploymentEventId` and `preflightReportPath` lineage.
