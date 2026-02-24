# FireMUD Promotion Attestation Contract

This document defines the canonical attestation artifact used to promote image digests from staging to production.

## Purpose

- Provide machine-verifiable evidence that a specific digest set was deployed and validated in staging.
- Prevent production overlay PRs from promoting unverified or drifted image digests.
- Keep promotion and rollback evidence auditable and stable across tooling changes.

## Artifact Format

The attestation is a JSON document committed with the production overlay change or uploaded as a referenced CI artifact.

Required fields:

- `attestationVersion` – schema version string (for example `v1`).
- `environment` – must be `staging`.
- `stagingOverlayCommitSha` – Git SHA of the staging overlay commit applied.
- `serviceDigests` – map of service name to immutable digest (`image@sha256:...`).
- `smokeEvidence` – list of URLs or artifact IDs for smoke-test results.
- `generatedAt` – UTC timestamp in ISO-8601 format.
- `approvedBy` – human approver identity (or approved automation identity plus change ticket).

Optional fields:

- `notes` – short free-form context.
- `ticket` – change-management or incident ticket reference.

## Validation Rules

- Production overlay PRs must include or reference exactly one attestation artifact.
- Every production overlay digest must match the digest in `serviceDigests`.
- `environment` must be `staging`.
- `stagingOverlayCommitSha` must exist in Git history and correspond to a successful staging deployment record.
- Attestation schema must validate against the current `attestationVersion`.
- If any check fails, production promotion is blocked.

## Storage and Retention

- Keep the attestation with the production promotion evidence (PR body, attachment, or committed file).
- Retain attestation artifacts for at least as long as release/rollback audit history is retained.
- Rollback PRs should reference the original attestation used for the digest set being restored.

## Ownership

- CI owns schema validation.
- Operators own staging evidence quality and approver identity.
- Platform owners own schema versioning and compatibility policy.

## Related Documentation

- `design/architecture/system-architecture-cicd.md`
- `design/architecture/system-architecture-deployment-runbook.md`
