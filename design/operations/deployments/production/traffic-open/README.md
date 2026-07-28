# Production Traffic-Open Evidence

Store one record per `first-live` or `reopen` production traffic-open event as:

- `design/operations/deployments/production/traffic-open/<event>-<deployment-ref>/<deploymentEventId>.json`

For production, `<deployment-ref>` is the exact lowercase hexadecimal Git commit reference from the event's preflight `deploymentRef.overlayCommitSha`; copy it verbatim rather than deriving or sanitizing a display label. It therefore cannot introduce path separators. `<deploymentEventId>` is the canonical lowercase UUID from the same preflight report. The directory event is `first-live` or `reopen` and must equal the stored `eventType`; the stored `deploymentEventId` must equal the complete UUID filename.

In the target flow, preflight reads the durable actual-recovery controller while it holds quarantine at `ready_to_reopen`; it does not consume a transient traffic-open projection. After the controller reaches `finalized`, the exporter emits the immutable environment-wide traffic-open projection below, retaining the controller reference and exact player-facing target boundary. The current executable has no production controller read or production projection writer, so `PREFLIGHT-BACKUP-002` intentionally fails closed.

Required fields:

- `schemaVersion` (`traffic-open-record/v1`)
- `environment` (`production`)
- `eventType`
- `trafficOpenStatus` (`finalized`)
- `deploymentRef`
- `deploymentEventId` (must equal the UUID in `preflightReportPath` and the final `<deploymentEventId>` filename component)
- `assessedAt`
- `assessedBy`
- `preflightReportPath`
- `backupLastSuccessAt`
- `backupVerifyLastSuccessAt`
- `restoreDrillLastSuccessAt`
- `backupReadinessRef`
- `baselineRecoveryRecordRef`
- `actualRecoveryRecordRef` (durable actual-recovery controller reference for first-live or reopen; checked-in projection follows finalization)
- `playerFacingTargetBoundary`
- `backupCoverage` (`environment-wide-postgresql`)
- `backupArtifactRef`
- `backupToolDigest`
- `recoveryToolDigest`
- `recoveryContractFingerprint`
- `sourceEnvironmentBinding`
- `drillTargetBoundary`
- `trafficExposure` (`player-facing-first-live` when `eventType=first-live`, or `player-facing-reopen` when `eventType=reopen`; the referenced baseline drill retains `isolated-drill` in its own recovery record)
- `backupConfidentialityEvidence`
- `trafficOpenedAt` when finalized
- `evidenceRefs`

Production preflight requires the referenced preflight report to pass and dereferences the finalized restore and baseline drill projections proving the environment-wide cold-start contract, immutable erasure high-water capture, gap-free erasure replay, and backup confidentiality. For first-live and reopen, preflight also reads the durable actual-recovery controller in `ready_to_reopen`, verifies the exact `playerFacingTargetBoundary` and event-matching `trafficExposure`, and requires `PREFLIGHT-BACKUP-002=pass`; an isolated drill cannot replace this live authority, and the checked-in projection is not consulted to authorize the same release. `continueRecovery(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` idempotently advances only the recorded internal phase. Before the bounded recover operation releases quarantine or permits traffic, the public `resume(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` authorization gate must match the durable operation, its recorded scope, server-issued lock token, and immutable evidence, then record `RESUME_AUTHORIZED`; it does not itself release the lock or open traffic. The internal success-release phase then applies and observes quarantine release through the finalized state. The exporter records `trafficOpenedAt` and writes both immutable finalized projections afterward; validation of that retained projection requires the referenced actual-recovery record to be finalized and its `deploymentEventId` and `preflightReportPath` to match the same event-scoped preflight report. Backup-pause scope remains maintenance evidence only, not traffic-open recovery proof.
