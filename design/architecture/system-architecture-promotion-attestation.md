# FireMUD Promotion Attestation Contract

This document defines the canonical attestation artifact used to promote image digests from staging to production.

## Purpose

- Provide machine-verifiable evidence that a specific digest set was deployed and validated in staging.
- Prevent production overlay PRs from promoting unverified or drifted image digests.
- Keep promotion and rollback evidence auditable and stable across tooling changes.

## Implementation Status

The current repository validates checked-in, operator-authored promotion evidence, including the JWT custody and rotation lineage fields below, but does not yet prove the target machine-generated evidence producer. The current preflight and staging-record producers do not yet emit that target evidence, so production promotion remains blocked until those producers and their underlying custody/rotation proof are implemented. The target contract below still requires operator review of evidence grounded in immutable artifacts and observed live state. Promotion/staging `smokeEvidence` is a non-empty list of closed entries `{ref, contentDigest}`: `ref` is a repository-relative retained JSON file and `contentDigest` is lowercase `sha256:<64 hex>` over that file's exact bytes. Each retained artifact must pass the canonical player-experience smoke evidence validator, declare `executionMode=live` and `externalAuthorityProvenance=retained-external`, preserve `deploymentRef` as the environment-agnostic staging deployment lineage (the selected `stagingOverlayCommitSha`), and carry the selected `deploymentEventId` equal to the selected `stagingDeploymentEventId`; the enclosing attestation and staging record bind that entry and digest to the exact overlay, service-digest, and live-state tuple. Simulated evidence, malformed or invalid retained evidence, missing files, and opaque references fail closed. External URL or artifact dereference is not implemented, so URL and opaque artifact references are not currently promotable. Recovery-baseline `smokeEvidence` uses the same closed `{ref, contentDigest}` shape, but remains a separate compatibility contract without promotion-only deployment-lineage binding. Shared selection and reporting guidance for required validation and runtime evidence is [Validation and Runtime Proof](../developer-workflows/validation-and-runtime-proof.md); execution results remain in PR/CI evidence or the owning implementation tracker rather than this target-state contract.

## Artifact Format

The attestation is a JSON document committed in-repo with the production overlay change so overlay PR validation is deterministic.

Canonical storage path:

- `design/operations/deployments/production/attestations/<deployment-ref>.json`

Current trust model:

- For the current single-admin/operator phase, the Git-reviewed in-repo evidence chain is the promotion trust root. CI validates the attestation, referenced staging deployment record, live-state evidence shape, immutable secret-compliance evidence references, and digest equality deterministically from repository state plus registry image availability.
- The attestation record is not a detached cryptographic signature. If FireMUD later requires multi-party release approval or stronger provenance, add signed/SLSA-style attestations as a new evidence layer without replacing the canonical in-repo promotion index.
- Target promotion tooling should machine-generate the candidate attestation and routine lineage fields from the successful staging deployment and observed live state.
- Operator-authored evidence is acceptable only when it points to immutable artifacts or observed live-state values. Free-form notes can provide context, but they must not be the only proof for digest, smoke, live-state, or secret-compliance decisions.

Required fields:

- `attestationVersion` – schema version string (for example `v1`).
- `environment` – must be `staging`.
- `stagingOverlayCommitSha` – Git SHA of the staging overlay commit applied.
- `stagingDeploymentEventId` – canonical UUID selecting the immutable staging apply event being promoted.
- `jwtCustodyProof` – object with exactly `proofId`, `custodyMode`, and `contractVersion`, copied from the selected staging deployment record and its consumed operator preflight report.
- `jwtRotationEvidenceRef` – immutable repository reference in the exact form `<repository-path>#sha256:<digest>` to the passing `PREFLIGHT-JWT-ROTATION-001` evidence carried by the selected staging deployment record.
- `serviceDigests` – map of service name to immutable OCI manifest or index reference (`image@sha256:...`). A single-architecture service records its manifest reference here; a multi-architecture service records its OCI index reference here.
- `servicePlatformDigests` – map of service name to a map of canonical lowercase OCI platform keys (`os/architecture` or `os/architecture/variant`, for example `linux/amd64` or `linux/arm64/v8`) to exact child-manifest references (`image@sha256:...`). A multi-architecture service's map keys must exactly equal the production-admitted platform set, with no missing or extra entries, and each index descriptor must prove the recorded platform-to-child binding. A single-architecture service has exactly one admitted-platform entry whose child digest equals its `serviceDigests` manifest digest.
- `smokeEvidence` – non-empty list of closed objects, each containing exactly `ref` and `contentDigest`; promotion/staging `ref` values must be unique within the list. `ref` is a repository-relative retained JSON evidence file; `contentDigest` is lowercase `sha256:<64 hex>` over the exact UTF-8 bytes retained at that path. Each file must pass the canonical player-experience smoke evidence validator, use live retained-external authority provenance, preserve `deploymentRef` as the selected `stagingOverlayCommitSha`, and carry the selected `deploymentEventId` equal to the `stagingDeploymentEventId` selected by this attestation. The enclosing attestation and staging deployment record bind the entry and digest transitively to their exact `stagingOverlayCommitSha`, `serviceDigests`, and live-state evidence; the entry does not duplicate those parent service-digest maps.
- `generatedAt` – UTC timestamp in ISO-8601 format.
- `approvedBy` – human approver identity (or approved automation identity plus change ticket).
- `rollbackMode` – `rollback-compatible` or `roll-forward-only` classification for the promoted digest set. `rollback-compatible` is allowed only when the previous known-good release remains safe to re-apply against the currently bound database schema, secret/config contract, file-path contract, and external-binding contract. Any release that requires new secret formats, new mounted resource shapes, changed credential semantics, or new external target bindings must be classified as `roll-forward-only` unless the prior release is explicitly proven compatible with those bindings.
  Example: switching a player-facing service from inline JWT secret consumption to file-mounted `FIREMUD_AUTH_JWT_SECRET_PATH`, or changing the expected external asset bucket binding, is `roll-forward-only` unless the previous release is proven compatible with the new mount/binding contract.
- `recoveryCompatibility` – the compact result defined in `system-architecture-backup-recovery-evidence-and-compliance.md`. It references the current baseline recovery record, compares baseline and candidate recovery-contract fingerprints, records changed dimensions and evaluator identity, and states whether a new drill is required. It does not duplicate the full recovery record.
- `productionOverlayRef` – target production overlay change identifier (for example the overlay PR deployment-ref or intended overlay commit token).
- `releaseDigestManifestRef` – required for official production releases; path to the release digest manifest at the stable production deployment-ref path (`design/operations/deployments/production/release-manifests/<deployment-ref>.json`), whose `releaseTag` field (when a tag exists) and deployment reference bind this attestation and digest set.

Artifact-lineage rule:

- `serviceDigests` must identify the exact staged artifact lineage being promoted.
- Production attestation must never bless a digest that was rebuilt after staging from the same source commit or release tag; rebuilt artifacts require a new staging deployment record and a new attestation lineage.
- Same-registry aliases are acceptable only when they resolve to the recorded digest. A registry copy that changes manifest/index bytes or digest requires a verified source-to-destination mapping and a new destination staging record before promotion.
- Service-image promotion evidence remains separate from tenant/game published-release-bundle evidence; either may reference an explicit compatibility contract, but neither replaces the other.

Optional fields:

- `notes` – short free-form context.
- `ticket` – change-management or incident ticket reference.

## Validation Rules

### Baseline Recovery Freshness

For `recoveryCompatibility.compatibilityStatus=compatible`, `baselineRecoveryRecordRef` must independently resolve to the canonical finalized projection of a `production-equivalent-drill` with `trafficExposure=isolated-drill` and `coordinationRecoveryMode=cold_start_restore`. Its recovery proof is fresh only when `finalizedAt <= evaluatedAt <= generatedAt`, both `evaluatedAt` and `generatedAt` are no later than promotion time, the referenced projection remains within the canonical 30-day window at promotion time, and the recovery controller reached `recoveryStatus=finalized`; a future or stale evaluation, `ready_to_reopen`, or partially observed controller is not a fresh drill. Freshness does not override the existing invalidation rules: any invalidating or unknown recovery-contract dimension requires `drill_required` or `incompatible`, and `roll-forward-only` still requires the matching full backup-readiness record.

- Production overlay PRs must include exactly one in-repo attestation artifact.
- Every production overlay digest must match the digest in `serviceDigests`, and its resolved platform-child map must match `servicePlatformDigests` exactly.
- Official production release PRs must include exactly one release digest manifest whose `promotionAttestationRef` points to the attestation and whose `serviceDigests` match byte-for-byte.
- `environment` must be `staging`.
- `stagingOverlayCommitSha` must exist in Git history and, together with `stagingDeploymentEventId`, select a successful staging deployment record. That record's `overlayCommitSha` must equal `stagingOverlayCommitSha`, and its `deploymentEventId` must equal `stagingDeploymentEventId`.
- The selected staging deployment record must include `jwtCustodyProof` and `jwtRotationEvidenceRef`; the attestation must copy both exactly. The tuple must exactly match the consumed event-scoped operator preflight report and the production candidate's applicable custody contract, while `jwtRotationEvidenceRef` must resolve to immutable evidence with `policyId=PREFLIGHT-JWT-ROTATION-001`, `status=pass`, the same staging deployment event identity, and the same custody tuple. These staging-lineage fields are supplemental and never replace production event-bound preflight proof.
- The referenced staging deployment record must include live-state verification (`liveStateEvidence`) proving the running digests matched the reviewed overlay after apply.
- `liveStateEvidence` must be machine-checkable: status `pass`, the observed overlay SHA, observed running digests, and `observedPlatformDigests` for the promoted services must match the referenced staging deployment record and attestation. The exact `servicePlatformDigests` map is carried in the staging deployment record and candidate attestation, compared with the production overlay's resolved index and child descriptors, and represented by `liveStateEvidence.observedPlatformDigests`.
- The referenced staging deployment record must include `deployStatus=pass` and `smokeStatus=pass`.
- Attestation and staging deployment `smokeEvidence` lists must contain exactly matching closed `{ref, contentDigest}` entries. Each referenced file must remain within the repository, its exact bytes must hash to the declared digest, its `deploymentRef` must equal the attested `stagingOverlayCommitSha`, its `deploymentEventId` must equal the attested `stagingDeploymentEventId` (the same selected event ID may be carried by every entry), and it must pass the canonical player-experience smoke evidence validator with `executionMode=live` and `externalAuthorityProvenance=retained-external`. Before accepting or validating a promotion-bound artifact, reject missing or non-retained-external provenance and reject `externalAuthority.profile=independent-omitted`; that degraded profile cannot satisfy promotion smoke evidence even when the outer provenance label is present. The attested/staged event tuple binds the exact overlay, promoted service digests, and live-state digests, while the content digest plus both artifact lineage fields bind the retained smoke bytes to that parent tuple without copying service-digest maps into every entry. Missing, opaque, malformed, invalid, stale, digest-mismatched, or simulated evidence blocks promotion. Compatible recovery-baseline `smokeEvidence` uses the same closed `{ref, contentDigest}` shape and validator path, but is not subject to promotion-only deployment-lineage binding or the `independent-omitted` prohibition.
- The staging record must reference `design/operations/deployments/staging/preflight/<stagingOverlayCommitSha>/<stagingDeploymentEventId>.json`; that operator report's `deploymentRef.overlayCommitSha` must equal `stagingOverlayCommitSha`, its `deploymentEventId` must equal `stagingDeploymentEventId`, and its `completedAt` must be no later than and no more than 30 minutes before the record's `appliedAt`.
- The referenced staging deployment record must include `secretComplianceStatus` set to `pass` and a `secretComplianceEvidenceRef`.
- The referenced secret-compliance evidence must include immutable artifact identifiers for all required credential classes; warning-only or note-only evidence is not promotable.
- The referenced production overlay digests must be byte-identical to the staged digests recorded in the deployment record; same-digest aliases are acceptable, rebuilds are not. Multi-architecture validation compares both the index digest and the declared `servicePlatformDigests` map, including exact platform-key coverage and child-manifest bindings.
- `secretComplianceEvidenceRef` may satisfy compliance through either immutable bootstrap provisioning evidence or immutable rotation evidence, as defined in `infrastructure/environment-and-secrets-overview.md`, but warning-only compliance records are never promotable.
- Attestation schema must validate against the current `attestationVersion`.
- `recoveryCompatibility.compatibilityStatus=compatible` may reuse a baseline only for a `rollback-compatible` release when the fresh finalized-drill requirements above pass, the candidate fingerprint is unchanged, and `changedDimensions[]` contains no invalidating or unknown recovery-contract change. `drill_required` blocks promotion until a fresh drill is complete and the result is regenerated as `compatible`; evidence attached to the stale result does not make it promotable. Every `roll-forward-only` release requires the regenerated compatible result and the matching full backup-readiness record. `incompatible` blocks promotion.
- If any check fails, production promotion is blocked.
- A missing attestation is a failed check; validation must never skip promotion preflight because evidence is absent.

External-only attestation storage is not allowed for production promotions because it prevents deterministic PR validation.

## Storage and Retention

- Keep the attestation in the repository with the production promotion evidence and reference it from the PR body.
- Production overlay PRs must include exactly one changed attestation file under `design/operations/deployments/production/attestations/`.
- Staging deployment records are stored in-repo at `design/operations/deployments/staging/deployments/<stagingOverlayCommitSha>/<stagingDeploymentEventId>.json`.
- The staging deployment record is the canonical evidence source for the selected `stagingOverlayCommitSha` and `stagingDeploymentEventId` pair and must contain at minimum:
  - `environment` (`staging`)
  - `overlayCommitSha`
  - `deploymentEventId`
  - `appliedAt`
  - `appliedBy`
  - `deployStatus`
  - `smokeStatus`
  - `serviceDigests` map
  - `servicePlatformDigests` map
  - `preflightReportPath`
  - `liveStateEvidence`
  - `secretComplianceSnapshotAt`
  - `secretComplianceStatus`
  - `secretComplianceEvidenceRef`
  - `jwtCustodyProof`
  - `jwtRotationEvidenceRef`
  - `smokeEvidence` list of closed `{ref, contentDigest}` entries, with each referenced artifact bound to the selected `stagingDeploymentEventId`
- Producer contract:
  - Target-state tooling emits the candidate deployment evidence from observed staging state for operator review.
  - CI promotion validation must fail if the referenced deployment record is missing, malformed, or digest-mismatched.
- Retain attestation artifacts for at least as long as release/rollback audit history is retained.
- Rollback PRs should reference the original attestation used for the digest set being restored.

## Ownership

- CI owns schema validation.
- Operators own staging evidence quality and approver identity.
- Platform owners own schema versioning and compatibility policy.
- Platform Operations owns storage and promotion-evidence infrastructure; Game Design owns tenant/game publication and release attestation, which this service must not duplicate.

## Related Documentation

- `design/architecture/system-architecture-cicd.md`
- `design/architecture/system-architecture-deployment-runbook.md`
