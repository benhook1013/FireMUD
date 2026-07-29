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

Production preflight requires the referenced preflight report to pass and dereferences the finalized restore and baseline drill projections proving the environment-wide cold-start contract, immutable erasure high-water capture, gap-free erasure replay, and backup confidentiality. For first-live and reopen, preflight also reads the durable actual-recovery controller in `ready_to_reopen`, verifies the exact `playerFacingTargetBoundary` and event-matching `trafficExposure`, and requires `PREFLIGHT-BACKUP-002=pass`; an isolated drill cannot replace this live authority, and the checked-in projection is not consulted to authorize the same release. The public wire `expectedPhase` values are exactly `ready_to_reopen` for `continueRecovery` and `awaiting_resume` for `resume`. The first happens to match the internal durable phase `ready_to_reopen`; the second maps to internal durable phase `AWAITING_RESUME`. Clients must send only the documented lowercase wire value for the applicable verb and must not send internal enum casing, `RESUME_AUTHORIZED`, `releasing`, or alternate representations. The canonical transition is `continueRecovery(operationId, expectedPhase=ready_to_reopen, maintenanceLockToken, evidenceRef)` -> internal `AWAITING_RESUME`: it validates complete Account projection evidence and replay-domain proof where applicable, idempotently advances only the recorded internal phase, and does not authorize release. The external public `resume(operationId, expectedPhase=awaiting_resume, maintenanceLockToken, evidenceRef)` gate must then match the durable operation, its recorded scope, server-issued lock token, Account projection evidence, replay-domain proof where applicable, and immutable evidence, then record `RESUME_AUTHORIZED`; it does not itself release the lock or open traffic. Only afterward may the separate internal success-release phase apply and observe quarantine release through the finalized state. The exporter records `trafficOpenedAt` and writes both immutable finalized projections afterward; validation of that retained projection requires the referenced actual-recovery record to be finalized and its `deploymentEventId` and `preflightReportPath` to match the same event-scoped preflight report. Backup-pause scope remains maintenance evidence only, not traffic-open recovery proof.
