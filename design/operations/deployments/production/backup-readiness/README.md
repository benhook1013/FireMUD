# Production Backup Readiness Evidence

Store one full record per `roll-forward-only` production promotion, or when the compact recovery-compatibility result requires a new drill, as:

- `<deployment-ref>.json`

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
- `recoveryControllerLineage`
- `backupConfidentialityEvidence`
- `backupCoverage` (`environment-wide-postgresql`)
- `backupArtifactRef`
- `sourceServiceDigests`
- `candidateServiceDigests`
- `candidateMigrationPathRef`
- `backupToolDigest`
- `recoveryToolDigest`
- `recoveryContractFingerprint`
- `evidenceRefs`

`promotionAttestationRef` must point to the production attestation record for the release, and `candidateServiceDigests` must match that attested digest set exactly. The source artifact must come from current production database lineage under representative writes; the drill must restore with candidate recovery tooling and prove the exact candidate migration path, config, bindings, environment-wide cold-start convergence, hardening, and controlled reopen.

`recoveryControllerLineage` must point to finalized environment-wide `cold_start_restore` controller state from the qualifying drill. `backupConfidentialityEvidence` must prove encrypted transport/storage, environment-scoped least-privilege access and audit, retention/secure deletion, and production-origin non-production quarantine, sanitization, validation, and deletion when applicable. A checked-in recovery JSON projection is post-finalization evidence and is not the authority for the release that finalized the controller.

Production preflight rejects `roll-forward-only` promotions and any `drill_required` release when this evidence is missing, stale, or not bound to the promoted attestation and digest set.

Compatible rollback releases do not duplicate this full record. Their promotion/deployment evidence contains the compact `recoveryCompatibility` result defined by the backup recovery evidence contract.
