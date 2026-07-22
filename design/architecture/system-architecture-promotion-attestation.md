# FireMUD Promotion Attestation Contract

This document defines the canonical attestation artifact used to promote image digests from staging to production.

## Purpose

- Provide machine-verifiable evidence that a specific digest set was deployed and validated in staging.
- Prevent production overlay PRs from promoting unverified or drifted image digests.
- Keep promotion and rollback evidence auditable and stable across tooling changes.

## Artifact Format

The attestation is a JSON document committed in-repo with the production overlay change so overlay PR validation is deterministic.

Canonical storage path:

- `design/operations/deployments/production/attestations/<deployment-ref>.json`

Current trust model:

- For the current single-admin/operator phase, the Git-reviewed in-repo evidence chain is the promotion trust root. CI validates the attestation, referenced staging deployment record, live-state evidence shape, immutable secret-compliance evidence references, and digest equality deterministically from repository state plus registry image availability.
- The attestation record is not a detached cryptographic signature. If FireMUD later requires multi-party release approval or stronger provenance, add signed/SLSA-style attestations as a new evidence layer without replacing the canonical in-repo promotion index.
- Operator-authored evidence is acceptable only when it points to immutable artifacts or observed live-state values. Free-form notes can provide context, but they must not be the only proof for digest, smoke, live-state, or secret-compliance decisions.

Required fields:

- `attestationVersion` – schema version string (for example `v1`).
- `environment` – must be `staging`.
- `stagingOverlayCommitSha` – Git SHA of the staging overlay commit applied.
- `serviceDigests` – map of service name to immutable digest (`image@sha256:...`).
- `smokeEvidence` – list of URLs or artifact IDs for smoke-test results.
- `generatedAt` – UTC timestamp in ISO-8601 format.
- `approvedBy` – human approver identity (or approved automation identity plus change ticket).
- `rollbackMode` – `rollback-compatible` or `roll-forward-only` classification for the promoted digest set. `rollback-compatible` is allowed only when the previous known-good release remains safe to re-apply against the currently bound database schema, secret/config contract, file-path contract, and external-binding contract. Any release that requires new secret formats, new mounted resource shapes, changed credential semantics, or new external target bindings must be classified as `roll-forward-only` unless the prior release is explicitly proven compatible with those bindings.
  Example: switching a player-facing service from inline JWT secret consumption to file-mounted `FIREMUD_AUTH_JWT_SECRET_PATH`, or changing the expected external asset bucket binding, is `roll-forward-only` unless the previous release is proven compatible with the new mount/binding contract.
- `recoveryCompatibility` – the compact result defined in `system-architecture-backup-recovery-evidence-and-compliance.md`. It references the current baseline recovery record, compares baseline and candidate recovery-contract fingerprints, records changed dimensions and evaluator identity, and states whether a new drill is required. It does not duplicate the full recovery record.
- `productionOverlayRef` – target production overlay change identifier (for example the overlay PR deployment-ref or intended overlay commit token).
- `releaseDigestManifestRef` – required for official production releases; path to the release digest manifest that binds the release tag or deployment reference to this attestation and digest set.

Artifact-lineage rule:

- `serviceDigests` must identify the exact staged artifact lineage being promoted.
- Production attestation must never bless a digest that was rebuilt after staging from the same source commit or release tag; rebuilt artifacts require a new staging deployment record and a new attestation lineage.

Optional fields:

- `notes` – short free-form context.
- `ticket` – change-management or incident ticket reference.

## Validation Rules

### Baseline Recovery Freshness

For `recoveryCompatibility.compatibilityStatus=compatible`, `baselineRecoveryRecordRef` must independently resolve to the canonical finalized projection of a `production-equivalent-drill` with `trafficExposure=isolated-drill` and `coordinationRecoveryMode=cold_start_restore`. Its recovery proof is fresh only when the referenced projection's `finalizedAt` precedes `evaluatedAt`, remains within the canonical 30-day window at current promotion time, and the recovery controller reached `recoveryStatus=finalized`; a stale evaluation, `ready_to_reopen`, or partially observed controller is not a fresh drill. Freshness does not override the existing invalidation rules: any invalidating or unknown recovery-contract dimension requires `drill_required` or `incompatible`, and `roll-forward-only` still requires the matching full backup-readiness record.

- Production overlay PRs must include exactly one in-repo attestation artifact.
- Every production overlay digest must match the digest in `serviceDigests`.
- Official production release PRs must include exactly one release digest manifest whose `promotionAttestationRef` points to the attestation and whose `serviceDigests` match byte-for-byte.
- `environment` must be `staging`.
- `stagingOverlayCommitSha` must exist in Git history and correspond to a successful staging deployment record.
- The referenced staging deployment record must include live-state verification (`liveStateEvidence`) proving the running digests matched the reviewed overlay after apply.
- `liveStateEvidence` must be machine-checkable: status `pass`, the observed overlay SHA, and observed running digests for the promoted services must match the referenced staging deployment record and attestation.
- The referenced staging deployment record must include `deployStatus=pass` and `smokeStatus=pass`.
- The staging record must reference `design/operations/deployments/staging/preflight/<stagingOverlayCommitSha>.json`; that operator report must carry the same `deploymentEventId`, exact staging applicability, and a `completedAt` no later than the record's `appliedAt`.
- The referenced staging deployment record must include `secretComplianceStatus` set to `pass` and a `secretComplianceEvidenceRef`.
- The referenced secret-compliance evidence must include immutable artifact identifiers for all required credential classes; warning-only or note-only evidence is not promotable.
- The referenced production overlay digests must be byte-identical to the staged digests recorded in the deployment record; retags are acceptable, rebuilds are not.
- `secretComplianceEvidenceRef` may satisfy compliance through either immutable bootstrap provisioning evidence or immutable rotation evidence, as defined in `infrastructure/environment-and-secrets-overview.md`, but warning-only compliance records are never promotable.
- Attestation schema must validate against the current `attestationVersion`.
- `recoveryCompatibility.compatibilityStatus=compatible` may reuse a baseline only for a `rollback-compatible` release when the fresh finalized-drill requirements above pass, the candidate fingerprint is unchanged, and `changedDimensions[]` contains no invalidating or unknown recovery-contract change. `drill_required` and every `roll-forward-only` release must reference the matching full backup-readiness record; `incompatible` blocks promotion.
- If any check fails, production promotion is blocked.

External-only attestation storage is not allowed for production promotions because it prevents deterministic PR validation.

## Storage and Retention

- Keep the attestation in the repository with the production promotion evidence and reference it from the PR body.
- Production overlay PRs must include exactly one changed attestation file under `design/operations/deployments/production/attestations/`.
- Staging deployment records are stored in-repo at `design/operations/deployments/staging/deployments/<stagingOverlayCommitSha>.json`.
- The staging deployment record is the canonical evidence source for `stagingOverlayCommitSha` validation and must contain at minimum:
  - `environment` (`staging`)
  - `overlayCommitSha`
  - `deploymentEventId`
  - `appliedAt`
  - `appliedBy`
  - `deployStatus`
  - `smokeStatus`
  - `serviceDigests` map
  - `preflightReportPath`
  - `liveStateEvidence`
  - `secretComplianceSnapshotAt`
  - `secretComplianceStatus`
  - `secretComplianceEvidenceRef`
  - `smokeEvidence` list
- Producer contract:
  - Operators create/update the deployment record as part of staging apply in the deployment runbook flow.
  - CI promotion validation must fail if the referenced deployment record is missing, malformed, or digest-mismatched.
- Retain attestation artifacts for at least as long as release/rollback audit history is retained.
- Rollback PRs should reference the original attestation used for the digest set being restored.

## Ownership

- CI owns schema validation.
- Operators own staging evidence quality and approver identity.
- Platform owners own schema versioning and compatibility policy.

## Related Documentation

- `design/architecture/system-architecture-cicd.md`
- `design/architecture/system-architecture-deployment-runbook.md`
