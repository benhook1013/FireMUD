# Production Promotion Attestations

## Implementation Status

The repository has live validation seams for this artifact boundary: production-applicable overlay validation requires exactly one changed attestation file, and `dev-tools/deploy/preflight.py` resolves the canonical deployment-ref path and validates the attestation fields, digest bindings, staging-event references, and recovery-compatibility result that its current enforcement path reaches. These checks are fail-closed diagnostics and promotion gates where explicitly enabled; they do not prove that the referenced staging or production state actually exists or converged.

The attestation producer and the complete target promotion authority remain incomplete. In particular, no repository implementation currently produces the full staging live-state, recovery-controller, backup-readiness, secret-compliance, or controlled-reopen evidence required by the target contract, and the current preflight intentionally stops before the complete nested recovery and live-environment validation. Those are target-only gaps, not evidence that a checked-in attestation or a passing structural check authorizes production traffic.

Store exactly one attestation artifact per production promotion PR as:

- `<deployment-ref>.json`

Required fields:

- `attestationVersion`
- `environment` (`staging`)
- `stagingOverlayCommitSha`
- `stagingDeploymentEventId` (canonical UUID selecting the immutable staging apply record)
- `productionOverlayRef`
- `serviceDigests`
- `servicePlatformDigests` – the canonical platform-child map owned by [Promotion Attestations](../../../../architecture/system-architecture-promotion-attestation.md#artifact-format). It has exactly the same service keys as `serviceDigests`; each service maps canonical lowercase OCI platform keys (`os/architecture` or `os/architecture/variant`) to exact immutable child-manifest references (`image@sha256:...`). For a direct single-platform manifest in `serviceDigests`, the one platform entry binds that same manifest digest. For an index-backed `serviceDigests` reference, the platform entries bind the index's descriptor-selected child digests; they must not repeat or bind the index digest itself. A multi-architecture service must cover exactly the production-admitted platform set and prove each index descriptor's platform-to-child binding.
- `smokeEvidence` (non-empty list of closed `{ref, contentDigest}` entries; each `contentDigest` is the SHA-256 digest of the exact retained JSON bytes, encoded exactly as `sha256:<64 lowercase hexadecimal characters>`, each artifact's `deploymentRef` matches the selected `stagingOverlayCommitSha`, each artifact's `deploymentEventId` equals the selected `stagingDeploymentEventId`, and `ref` values are unique)
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

The selected [staging deployment record](../../staging/deployments/README.md) is the authoritative source for the promoted digest maps. Both the attestation's `servicePlatformDigests` and `serviceDigests` must deep-equal the corresponding maps in that selected staging record exactly, including service keys, platform keys, and child-manifest references for the platform map; resolving to equivalent registry content is not sufficient. Missing or extra services/platforms, non-canonical keys, a child digest that does not match the selected `serviceDigests` manifest/index descriptor, or any staging-record mismatch fails closed.

Production overlay PR validation rejects promotions unless the current PR changes exactly one attestation artifact for the deployment ref being promoted. Historical attestations remain retained in this directory and do not count against that per-promotion requirement.
Validation also rejects `recoveryCompatibility.compatibilityStatus=incompatible` unconditionally. A `drill_required` result is not an alternate promotion path: the required fresh drill and full evidence must be completed, the compatibility result must be regenerated as `compatible`, and only that updated compatible attestation can pass `recovery_compatibility_check()`. Every `roll-forward-only` promotion remains subject to its separate exact-candidate fresh-drill and full-evidence requirements.
