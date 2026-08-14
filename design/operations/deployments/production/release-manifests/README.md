# Production Release Digest Manifests

Store one manifest per official production release or release-candidate deployment at its stable production deployment ref:

- `<deployment-ref>.json`

Required fields:

- `schemaVersion`
- `releaseTag` when the Git tag exists
- `deploymentRef`
- `sourceCommitSha`
- `productionOverlayRef`
- `promotionAttestationRef`
- `stagingDeploymentRecordRef`
- `serviceDigests`
- `releaseNotesRef`
- `releaseComplianceAssetRefs`
- `generatedAt`
- `approvedBy`

Validation rules:

- `promotionAttestationRef` must point to the production promotion attestation for the same deployment.
- `stagingDeploymentRecordRef` must point to the staging deployment record referenced by that attestation.
- `serviceDigests` must match the attestation and production overlay byte-for-byte.
- Release-note and compliance workflows may publish assets for a tag, but this manifest is the release authority for runtime artifact lineage.
