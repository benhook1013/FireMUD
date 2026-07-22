# Production Traffic-Open Evidence

Store one record per `first-live` or `reopen` production traffic-open event as:

- `<event>-<deployment-ref>.json`

In the target flow, preflight reads the durable actual-recovery controller while it holds quarantine at `ready_to_reopen`; it does not consume a transient traffic-open projection. After the controller reaches `finalized`, the exporter emits the immutable environment-wide traffic-open projection below, retaining the controller reference and exact player-facing target boundary. The current executable has no production controller read or production projection writer, so `PREFLIGHT-BACKUP-002` intentionally fails closed.

Required fields:

- `schemaVersion` (`traffic-open-record/v1`)
- `environment` (`production`)
- `eventType`
- `trafficOpenStatus` (`finalized`)
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
- `backupConfidentialityEvidence`
- `trafficOpenedAt` when finalized
- `evidenceRefs`

Production preflight requires the referenced preflight report to pass and dereferences a current baseline recovery projection proving the environment-wide cold-start contract, immutable erasure high-water capture, gap-free erasure replay, and backup confidentiality. A pre-release `reopen` check reads the durable actual-recovery controller in `ready_to_reopen` for `playerFacingTargetBoundary`; an isolated drill cannot replace it, and the checked-in projection is not consulted to authorize the same release. `continueRecovery(operationId, expectedPhase, evidenceRef)` idempotently reconciles the internal `ready_to_reopen -> releasing -> finalized` phases, applies and observes quarantine release, and only then permits traffic. The exporter records `trafficOpenedAt` and writes both immutable finalized projections afterward; validation of that retained projection requires the referenced actual-recovery record to be finalized too. Backup-pause scope remains maintenance evidence only, not traffic-open recovery proof.
