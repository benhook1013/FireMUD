# Production Traffic-Open Evidence

Store one record per `first-live` or `reopen` production traffic-open event as:

- `<event>-<deployment-ref>.json`

Current implementation note:

`dev-tools/deploy/write-traffic-open-evidence.py` still writes the superseded tenant/region scope shape and cannot produce accepted player-facing readiness evidence. It must be updated before this gate can pass.

Required fields:

- `schemaVersion` (`traffic-open-record/v1`)
- `environment` (`production`)
- `eventType`
- `trafficOpenStatus` (`authorized` before the transition; `finalized` after it)
- `deploymentRef`
- `assessedAt`
- `assessedBy`
- `preflightReportPath`
- `backupLastSuccessAt`
- `backupVerifyLastSuccessAt`
- `restoreDrillLastSuccessAt`
- `backupReadinessRef`
- `baselineRecoveryRecordRef`
- `actualRecoveryRecordRef` when `eventType=reopen`
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

Production preflight must require the referenced preflight report to pass and must dereference a current baseline recovery record proving the environment-wide cold-start contract. A `reopen` event must also reference the `ready_to_reopen` actual-recovery record for the exact player-facing target boundary; an isolated drill cannot replace it. The gated transition finalizes both records and records `trafficOpenedAt` before routing traffic. The current executable validates only the older evidence shape, so first-live and reopen remain blocked until it implements this contract.
