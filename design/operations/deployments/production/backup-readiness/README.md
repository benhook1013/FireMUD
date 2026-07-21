# Production Backup Readiness Evidence

Store one record per `roll-forward-only` production promotion as:

- `<deployment-ref>.json`

Required fields:

- `environment` (`production`)
- `deploymentRef`
- `promotionAttestationRef`
- `assessedAt`
- `assessedBy`
- `rollbackMode` (`roll-forward-only`)
- `backupLastSuccessAt`
- `backupVerifyLastSuccessAt`
- `restoreDrillLastSuccessAt`
- `restorePlanRef`
- `serviceDigests`
- `evidenceRefs`

`promotionAttestationRef` must point to the production attestation record for the release, and `serviceDigests` must match that attested digest set exactly.

Production preflight rejects `roll-forward-only` promotions when this evidence is missing, stale, or not bound to the promoted attestation and digest set.
