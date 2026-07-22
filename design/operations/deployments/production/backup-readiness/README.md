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
- `recoveryControllerLineage`
- `backupConfidentialityEvidence`
- `serviceDigests`
- `evidenceRefs`

`promotionAttestationRef` must point to the production attestation record for the release, and `serviceDigests` must match that attested digest set exactly.

`recoveryControllerLineage` must point to finalized environment-wide `cold_start_restore` controller state from the qualifying drill. `backupConfidentialityEvidence` must prove encrypted transport/storage, environment-scoped least-privilege access and audit, retention/secure deletion, and production-origin non-production quarantine, sanitization, validation, and deletion when applicable. A checked-in recovery JSON projection is post-finalization evidence and is not the authority for the release that finalized the controller.

Production preflight rejects `roll-forward-only` promotions when this evidence is missing, stale, or not bound to the promoted attestation and digest set.
