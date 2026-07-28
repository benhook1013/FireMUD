# Production Backup Readiness Evidence

Store one full record per `roll-forward-only` production promotion, or when the compact recovery-compatibility result requires a new drill, as:

- `<deployment-ref>.json`

## Implementation Status

The field list below is the target-state contract. The target `PREFLIGHT-BACKUP-001` integration validates the richer backup lineage for production promotion; `PREFLIGHT-BACKUP-002` separately reads the durable recovery controller before production first-live or reopen. The current executable intentionally fails the traffic-open gate closed because that controller read is not implemented. Production promotion currently blocks at the incomplete finalized-baseline authority check before `PREFLIGHT-BACKUP-001` reaches the expanded envelope, freshness, digest, attestation, inventory, or nested evidence validation described below. Operators must not treat checked-in field shape or that fail-closed result as proof that the complete target contract was evaluated.

Required fields:

- `environment` (`production`)
- `deploymentRef`
- `promotionAttestationRef`
- `assessedAt`
- `assessedBy`
- `rollbackMode` (`rollback-compatible` or `roll-forward-only`)
- `backupLastSuccessAt`
- `backupVerifyLastSuccessAt`
- `restoreDrillLastSuccessAt`
- `restorePlanRef`
- `restoreRecoveryRecordRef`
- `baselineRecoveryRecordRef`
- `recoveryControllerLineage`
- `backupConfidentialityEvidence`
- `backupCoverage` (`environment-wide-postgresql`)
- `backupArtifactRef`
- `artifactErasureHighWater`
- `initialCatchupHighWater`
- `restoreHighWater`
- `sourceServiceDigests`
- `candidateServiceDigests`
- `candidateMigrationPathRef`
- `backupToolDigest`
- `recoveryToolDigest`
- `recoveryContractFingerprint`
- `evidenceRefs`

`promotionAttestationRef` must point to the production attestation record for the release, and `candidateServiceDigests` must match that attested digest set exactly. The source artifact must come from current production database lineage under representative writes; the drill must retain snapshot-bound `artifactErasureHighWater`, capture immutable `initialCatchupHighWater` and final-cutover `restoreHighWater`, replay the gap-free erasure interval, restore with candidate recovery tooling, and prove the exact candidate migration path, config, bindings, environment-wide cold-start convergence, hardening, and controlled reopen through `continueRecovery(operationId, expectedPhase, maintenanceLockToken, evidenceRef)`. Before the bounded recover operation releases the drill boundary, the public `resume(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` authorization gate must match the durable operation, its recorded scope, server-issued lock token, and immutable evidence, then record `RESUME_AUTHORIZED`; it does not itself release the lock or open traffic. The referenced `restoreRecoveryRecordRef` must carry the same immutable `artifactErasureHighWater`, `initialCatchupHighWater`, and `restoreHighWater` values.

`recoveryControllerLineage` must point to finalized environment-wide `cold_start_restore` controller state from the qualifying drill. `backupConfidentialityEvidence` must prove encrypted transport/storage, environment-scoped least-privilege access and audit, retention/secure deletion, and, whenever production-origin data is exercised outside production, quarantine, sanitization, validation, and deletion. A checked-in recovery JSON projection is post-finalization evidence and is not the authority for the release that finalized the controller.

Target production preflight rejects `compatibilityStatus=incompatible` unconditionally. A `drill_required` result is not an alternate promotion path: the required fresh drill and full evidence must be completed, the compatibility result must be regenerated as `compatible`, and only that updated compatible attestation can pass `recovery_compatibility_check()`. A `roll-forward-only` promotion also rejects evidence that is missing, stale, or not bound to the promoted attestation and digest set. Current production promotion preflight blocks every class before this validator executes; expanded envelope, bindings, complete inventory membership, immutable evidence, nested recovery-controller, confidentiality, hardening, and controlled-reopen validation remain executable gaps.

Compatible rollback releases do not duplicate this full record. Their promotion/deployment evidence contains the compact `recoveryCompatibility` result defined by the backup recovery evidence contract.
