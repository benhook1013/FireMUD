# FireMUD Deployment Preflight Policy Contract

This document defines the authoritative preflight policy gate for staging and production deployments, plus the equivalent policy contract that player-facing hobby/self-hosted operators must run before opening traffic.

## Purpose

- Provide one deterministic preflight entrypoint used by both CI and operators.
- Ensure secret contracts, digest pinning, and bridge/security invariants are enforced before apply.
- Produce a reusable pass/fail artifact for deployment evidence.

## Authoritative Entrypoint

- Command: `./dev-tools/deploy/preflight.sh <staging|production|hobby-self-hosted>`
- Input: target environment and resolved overlay/manifests for that environment.
- Output: non-zero exit code on failure and a machine-readable report artifact (for example JSON).
- Context:
  - `operator` (default): required checks are blocking for real applies.
  - `ci-static`: uses the same policy IDs/report schema but may mark runtime-only checks (for example production attestation when not in a production promotion flow) as `not_applicable`.

`hobby-self-hosted` deployments may use different packaging/manifests, but they must evaluate the same player-facing policy IDs that apply to their environment class and produce the same evidence shape.

## Enforcement Boundaries

- Overlay PR CI (`validate-kustomize-overlays.yml`) enforces static checks: digest pinning, image existence, attestation schema/digest matching, and repository policy markers.
- Operator pre-apply execution (`preflight.sh`) enforces resolved-manifest and target-environment checks: required secret/key contracts, JWT/JWKS contracts, Redis role split, and bridge alignment.
- Deployment apply is blocked unless required checks for the target class pass (or an explicit break-glass waiver is recorded).

## Environment Applicability

| Environment class | Overlay PR CI required | Operator preflight required | Notes |
| --- | --- | --- | --- |
| `staging` | Yes | Yes | Both gates mandatory before apply. |
| `production` | Yes | Yes | Both gates mandatory before apply. |
| `hobby-self-hosted` | Optional (recommended) | Yes | Operator preflight is mandatory; CI may be unavailable in single-operator setups. |

## Required Policy Checks

Every run must emit one result per policy ID below, with status `pass`, `fail`, or `not_applicable` (with reason):

- `PREFLIGHT-DIGEST-001` – all staging/production workload images are immutable digests (`image@sha256:...`).
- `PREFLIGHT-DIGEST-002` – hobby/self-hosted workload manifests are digest-pinned where the operator packaging format supports digest references.
- `PREFLIGHT-SECRETS-001` – required Secrets and keys exist for the target environment.
- `PREFLIGHT-JWT-001` – player-facing environments use `FIREMUD_AUTH_JWT_SECRET_PATH` and do not rely on inline-only JWT secrets.
- `PREFLIGHT-JWKS-001` – JWKS resource type matches environment policy (`jwt-jwks` Secret for player-facing environments; ConfigMap only for explicitly non-player-facing/test environments).
- `PREFLIGHT-BRIDGE-001` – `GATEWAY_WS_URL` matches the expected internal Gateway listener for the target environment.
- `PREFLIGHT-REDIS-001` – player-facing environments resolve distinct Coordination vs Cache Redis endpoints.
- `PREFLIGHT-PROMOTION-001` – production promotions reference a valid staging attestation with matching digests.

Policy applicability:

- `PREFLIGHT-PROMOTION-001` is required for `production` and `not_applicable` for `staging` and `hobby-self-hosted`.
- `PREFLIGHT-DIGEST-001` is required for any flow using Kustomize overlays (`staging`, `production`) and `not_applicable` for `hobby-self-hosted`.
- `PREFLIGHT-DIGEST-002` is recommended/advisory for `hobby-self-hosted` and `not_applicable` for `staging`/`production`.

## Evidence Contract

The report artifact must include:

- `environment`
- `deploymentRef` object with one of:
  - `overlayCommitSha` for overlay-driven deployments (`staging`, `production`), or
  - `manifestRef` / `chartVersion` for hobby/self-hosted deployments.
- `checkResults[]` with `policyId`, `status`, `message`
- `startedAt` and `completedAt` timestamps
- `toolVersion`

CI and manual operator runs must produce the same report shape so audit tooling can compare them.

### Evidence Storage and Retention

- Preflight report artifacts are stored in-repo under:
  - `design/operations/deployments/<environment>/preflight/<deployment-ref>.json`
- Break-glass waivers are stored beside the report artifact as:
  - `design/operations/deployments/<environment>/preflight/<deployment-ref>.waiver.json`
- `deployment-ref` is:
  - `<overlayCommitSha>` for overlay-driven staging/production deployments, or
  - a normalized manifest/chart reference token for hobby/self-hosted deployments.
- Retention requirement: keep preflight reports and waivers for at least as long as release/rollback audit history is retained.
- Waiver records must include: approver identity, incident/change ticket, scope (policy IDs waived), expiration (deployment event only), and timestamp.

## Failure Handling

- Any failed required check blocks deployment.
- Waivers are break-glass only, must be explicit, and must include approver + incident/change ticket in the report.
- Waivers expire after the specific deployment event and must not silently carry forward.

## Related Documentation

- `design/architecture/system-architecture-deployment-runbook.md`
- `design/architecture/system-architecture-cicd.md`
- `design/architecture/system-architecture-promotion-attestation.md`
