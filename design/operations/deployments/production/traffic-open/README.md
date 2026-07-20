# Production Traffic-Open Evidence

Store one record per `first-live` or `reopen` production traffic-open event as:

- `<event>-<deployment-ref>.json`

Current implementation note:

`dev-tools/deploy/write-traffic-open-evidence.py` still writes the superseded tenant/region scope shape and cannot produce accepted player-facing readiness evidence. It must be updated before this gate can pass.

Required fields:

- `schemaVersion` (`traffic-open-record/v1`)
- `environment` (`production`)
- `eventType`
- `trafficOpenStatus` (`finalized` in the checked-in projection; runtime authorization is held by the recovery controller)
- `deploymentRef`
- `assessedAt`
- `assessedBy`
- `preflightReportPath`
- `backupLastSuccessAt`
- `backupVerifyLastSuccessAt`
- `restoreDrillLastSuccessAt`
- `backupReadinessRef`
- `baselineRecoveryRecordRef`
- `actualRecoveryRecordRef` when `eventType=reopen` (durable actual-recovery controller reference; checked-in projection follows finalization)
- `backupCoverage` (`environment-wide-postgresql`)
- `backupArtifactRef`
- `backupToolDigest`
- `recoveryToolDigest`
- `recoveryContractFingerprint`
- `sourceEnvironmentBinding`
- `drillTargetBoundary`
- `trafficExposure` (`isolated-drill` for the referenced drill)
- `trafficOpenedAt` when finalized
- `evidenceRefs`

Production preflight must require the referenced preflight report to pass and must dereference a current baseline recovery projection proving the environment-wide cold-start contract. A `reopen` event must also read the durable actual-recovery controller in `ready_to_reopen` for the exact player-facing target boundary; an isolated drill cannot replace it. The controller idempotently reconciles `ready_to_reopen -> releasing -> finalized`, applies and observes quarantine release, and only then permits traffic. The exporter records `trafficOpenedAt` and writes both immutable projections after `finalized`. The current executable validates only the older evidence shape, so first-live and reopen remain blocked until it implements this contract.
