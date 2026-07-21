# Production Traffic-Open Evidence

Store one record per `first-live` or `reopen` production traffic-open event as:

- `<event>-<deployment-ref>.json`

Preflight consumes a transient `ready_to_reopen` projection while the actual-recovery controller still holds quarantine. After the controller reaches `finalized`, the canonical writer emits the immutable environment-wide traffic-open projection below. Both forms retain the durable actual-recovery controller reference and bind it to the exact player-facing target boundary.

Required fields:

- `schemaVersion` (`traffic-open-record/v1`)
- `environment` (`production`)
- `eventType`
- `trafficOpenStatus` (`ready_to_reopen` for transient pre-release authorization; `finalized` in the checked-in projection)
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
- `playerFacingTargetBoundary` when `eventType=reopen`
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

Production preflight requires the referenced preflight report to pass and dereferences a current baseline recovery projection proving the environment-wide cold-start contract. A pre-release `reopen` check reads the durable actual-recovery controller in `ready_to_reopen` for `playerFacingTargetBoundary`; an isolated drill cannot replace it. The controller idempotently reconciles `ready_to_reopen -> releasing -> finalized`, applies and observes quarantine release, and only then permits traffic. The exporter records `trafficOpenedAt` and writes both immutable finalized projections afterward; validation of that retained projection requires the referenced actual-recovery record to be finalized too.
