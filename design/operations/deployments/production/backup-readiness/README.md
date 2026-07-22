# Production Backup Readiness Evidence

Store one full record per `roll-forward-only` production promotion, or when the compact recovery-compatibility result requires a new drill, as:

- `<deployment-ref>.json`

## Implementation Status

The field list below is the target-state contract. The target `PREFLIGHT-BACKUP-002` integration validates the richer backup lineage and reads the durable recovery controller before production first-live or reopen; the current executable intentionally fails closed because that controller read is not implemented. `PREFLIGHT-BACKUP-001` remains partial: it validates the expanded top-level envelope, freshness, compact compatibility and baseline binding, candidate digests, and available attestation references, then fails closed because it does not yet dereference complete participant, validator, external-effect inventories and their linked immutable evidence or enforce every nested source/candidate, controller-lineage, confidentiality, and erasure-high-water field below. Operators must not treat that promotion-time check alone as proof of the complete target contract.

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
- `sourceServiceDigests`
- `candidateServiceDigests`
- `candidateMigrationPathRef`
- `backupToolDigest`
- `recoveryToolDigest`
- `recoveryContractFingerprint`
- `evidenceRefs`

`promotionAttestationRef` must point to the production attestation record for the release, and `candidateServiceDigests` must match that attested digest set exactly. The source artifact must come from current production database lineage under representative writes; the drill must retain `artifactErasureHighWater`, capture immutable `restoreHighWater`, replay the gap-free erasure interval, restore with candidate recovery tooling, and prove the exact candidate migration path, config, bindings, environment-wide cold-start convergence, hardening, and controlled reopen through `continueRecovery(operationId, expectedPhase, evidenceRef)`.

`recoveryControllerLineage` must point to finalized environment-wide `cold_start_restore` controller state from the qualifying drill. `backupConfidentialityEvidence` must prove encrypted transport/storage, environment-scoped least-privilege access and audit, retention/secure deletion, and, whenever production-origin data is exercised outside production, quarantine, sanitization, validation, and deletion. A checked-in recovery JSON projection is post-finalization evidence and is not the authority for the release that finalized the controller.

Target production preflight rejects `roll-forward-only` promotions and any `drill_required` release when this evidence is missing, stale, or not bound to the promoted attestation and digest set. Current preflight validates the expanded envelope and bindings, then fails all recovery-baseline reuse and these promotion classes closed until complete inventory membership, immutable evidence, nested recovery-controller, confidentiality, hardening, and controlled-reopen validation is implemented.

Compatible rollback releases do not duplicate this full record. Their promotion/deployment evidence contains the compact `recoveryCompatibility` result defined by the backup recovery evidence contract.
