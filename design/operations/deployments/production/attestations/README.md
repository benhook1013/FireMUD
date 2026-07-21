# Production Promotion Attestations

Store exactly one attestation artifact per production promotion PR as:

- `<deployment-ref>.json`

Required fields:

- `attestationVersion`
- `environment` (`staging`)
- `stagingOverlayCommitSha`
- `productionOverlayRef`
- `serviceDigests`
- `smokeEvidence`
- `generatedAt`
- `approvedBy`
- `rollbackMode`

Production overlay PR validation rejects promotions when this directory does not contain exactly one attestation artifact for the promotion being merged.
