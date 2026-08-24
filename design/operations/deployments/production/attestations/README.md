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
- `servicePlatformDigests`
- `smokeEvidence` (non-empty list of closed `{ref, contentDigest}` entries; `smokeEvidence.contentDigest` is lowercase `sha256:<64 hex>` over the exact retained JSON bytes, each artifact's `deploymentRef` matches the selected `stagingOverlayCommitSha`, each artifact's `deploymentEventId` equals the selected `stagingDeploymentEventId`, and `ref` values are unique)
- `generatedAt`
- `approvedBy`
- `rollbackMode`
- `releaseDigestManifestRef` for official production releases
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
  - `newestVerifiedRestorablePointRef`
  - `newestVerifiedRestorablePointDigest`
  - `newestVerifiedRestorablePointAt`
  - `backupReadinessRef` when `newDrillRequired=true`

The `newestVerifiedRestorablePoint*` children belong to the compact `recoveryCompatibility` result defined by [Backup Recovery Evidence and Compliance](../../../../architecture/system-architecture-backup-recovery-evidence-and-compliance.md); they are not independent promotion-attestation fields and do not replace the separate full backup-readiness record when that record is required.

Production overlay PR validation rejects promotions unless the current PR changes exactly one attestation artifact for the deployment ref being promoted. Historical attestations remain retained in this directory and do not count against that per-promotion requirement.
Validation also rejects `recoveryCompatibility.compatibilityStatus=incompatible` unconditionally. A `drill_required` result is not an alternate promotion path: the required fresh drill and full evidence must be completed, the compatibility result must be regenerated as `compatible`, and only that updated compatible attestation can pass `recovery_compatibility_check()`. Every `roll-forward-only` promotion remains subject to its separate exact-candidate fresh-drill and full-evidence requirements.
