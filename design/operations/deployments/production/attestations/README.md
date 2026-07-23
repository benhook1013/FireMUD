# Production Promotion Attestations

Store exactly one attestation artifact per production promotion PR as:

- `<deployment-ref>.json`

Required fields:

- `attestationVersion`
- `environment` (`staging`)
- `stagingOverlayCommitSha`
- `stagingDeploymentEventId` (canonical UUID selecting the immutable staging apply record)
- `productionOverlayRef`
- `serviceDigests`
- `smokeEvidence`
- `generatedAt`
- `approvedBy`
- `rollbackMode`
- `recoveryCompatibility`, containing:
  - `baselineRecoveryRecordRef`
  - `baselineRecoveryContractFingerprint`
  - `candidateRecoveryContractFingerprint`
  - `changedDimensions`
  - `compatibilityStatus` (`compatible`, `drill_required`, or `incompatible`)
  - `compatibilityRationale`
  - `evaluatedAt`
  - `evaluatorToolDigest`
  - `newDrillRequired` (`true` for `compatibilityStatus=drill_required` and every `roll-forward-only` release)
  - `backupReadinessRef` when `newDrillRequired=true`

Production overlay PR validation rejects promotions when this directory does not contain exactly one attestation artifact for the promotion being merged.
Validation also rejects `recoveryCompatibility.compatibilityStatus=incompatible` unconditionally. `drill_required` and `roll-forward-only` remain subject to their separate fresh-drill and full-evidence requirements.
