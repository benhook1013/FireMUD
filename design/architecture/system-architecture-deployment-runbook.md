# FireMUD Deployment Runbook

This runbook describes the **standard deployment flow** for FireMUD in Kubernetes-backed environments.

For high-level CI/CD architecture, see `design/architecture/system-architecture-cicd.md`. This document focuses on the concrete steps and checks an operator performs when rolling out a new version.

## Prerequisites

- CI pipeline has produced immutable image digests for each service to deploy.
- Database migrations have been validated (see `design/architecture/system-architecture-database-migrations.md`).
- Redis, PostgreSQL, and core infrastructure components (Gateway, TCP Proxy, Observability stack) are healthy.
- The operator has `kubectl` access and a kubeconfig for the target Kubernetes cluster (staging, production, or hobby-self-hosted) from a secure admin workstation or bastion host.

## Overlay Deployment Flow (Staging and Production)

1. **Review Release Notes**
   - Confirm which services and schema changes are included.
   - Identify any manual migration or configuration steps called out for operators.
2. **Verify CI/CD Status**
   - Ensure the GitHub Actions pipeline for the target branch and tag is green.
   - Check that container image digests are available in the configured registry.
   - Confirm deployment evidence includes rollback-mode classification (`rollback-compatible` or `roll-forward-only`) for the release candidate.
3. **Run Preflight Policy Checks**
   - Validate the target overlay before apply and fail fast on policy violations.
   - Evaluate policy IDs from `design/architecture/system-architecture-deploy-preflight-policy.md` (for example `PREFLIGHT-DIGEST-001`, `PREFLIGHT-SECRETS-001`, `PREFLIGHT-JWT-001`, `PREFLIGHT-JWKS-001`, `PREFLIGHT-BRIDGE-001`, `PREFLIGHT-REDIS-001`, and `PREFLIGHT-PROMOTION-001` for production).
   - Treat preflight as blocking. Do not run `kubectl apply` until all checks pass.
   - Use the canonical entrypoint: `./dev-tools/deploy/preflight.sh <staging|production|hobby-self-hosted>`.
   - Store the preflight report artifact under `design/operations/deployments/<environment>/preflight/<deployment-ref>.json` with optional waiver record `.../<deployment-ref>.waiver.json` as defined in `design/architecture/system-architecture-deploy-preflight-policy.md`.
4. **Update the Environment Overlay (Git-Tracked)**
   - Update the image digests in the environment-specific Kustomize overlay for the target environment (for example `k8s/overlays/stage` or `k8s/overlays/prod`) via a Git change.
   - Use a pull request for the overlay change so promotion and rollback remain auditable (the merged commit is the source of truth for what is intended to be running in that environment).
5. **Apply Kubernetes Manifests**
   - From a secure operator environment, apply the overlay (for example `kubectl apply -k k8s/overlays/prod`).
   - Each environment boundary (staging vs production) uses its own cluster credentials and secret sources; `firemud` is the default namespace name within each boundary. When using a non-default namespace for drills or temporary restores, treat that namespace as an explicit override tied to the selected overlay or restore script inputs.
   - Treat the apply as an operational action that enacts the already-reviewed overlay change.
   - Record which overlay commit was applied so “what is deployed?” is answerable even when cluster state drifts:
     - Capture the Git commit SHA and timestamp in the deployment notes/runbook record for the environment.
     - Stamp the SHA into the cluster so it is retrievable during incidents:
       - Preferred: annotate the namespace with the overlay SHA:
         - `kubectl annotate namespace <namespace> firemud.io/overlay-sha=<git-sha> --overwrite`
       - Alternative: create/update a dedicated ConfigMap (for example `firemud-deploy-info`) that stores `overlay_sha` and `applied_at` as data keys.
6. **Monitor Rollout**
   - Watch deployment rollout status for each updated service.
   - Verify pod readiness and liveness probes are passing.
   - Check logs for startup errors, especially around database connectivity, Redis connectivity, and secrets loading.
7. **Post-Deployment Checks**
   - Run smoke tests and login/session checks as described in `design/developer-workflows/login-session-smoke-tests.md`.
   - Confirm that the game session tick loop is running and that players can connect via both Web client and Telnet.
8. **Record Rollback Classification**
   - Mark the deployment evidence as `rollback-compatible` when previous digests are safe to re-apply.
   - Mark as `roll-forward-only` when schema/contract changes make old-binary rollback unsafe; include the forward-remediation or restore-point path.

## Hobby Manifest/Chart Deployment Flow (Hobby / Self-Hosted)

1. **Resolve Deployment Inputs**
   - Select the exact `manifestRef` or `chartVersion` to deploy.
   - Define rollback target (`previousManifestRef` or `previousChartVersion`) before apply.
2. **Run Operator Preflight**
   - Run `./dev-tools/deploy/preflight.sh hobby-self-hosted`.
   - Treat required preflight checks as blocking for player-facing traffic.
   - Store preflight report and optional waiver artifacts using the same evidence path contract: `design/operations/deployments/hobby-self-hosted/preflight/<deployment-ref>.json` and `.../<deployment-ref>.waiver.json`.
3. **Apply Manifests/Charts**
   - Apply from a secure operator environment using the chosen manifest/chart input.
   - Record the applied `manifestRef`/`chartVersion`, timestamp, and operator identity in deployment evidence.
4. **Monitor Rollout and Run Smoke Checks**
   - Verify pod readiness/liveness, secrets loading, and Redis/PostgreSQL connectivity.
   - Run login/session smoke checks and confirm player connectivity paths.
5. **Record Deployment Evidence**
   - Record deployment evidence at `design/operations/deployments/hobby-self-hosted/deployments/<deployment-ref>.json`.
   - Include: deployment input reference, preflight report path, smoke evidence references, and rollback reference.

## Canary or Phased Rollouts

For higher-risk changes:

- Deploy to a non-production environment and run extended smoke, load, and gameplay tests.
- Use Kubernetes deployment strategies (for example `RollingUpdate` with conservative surge/unavailable settings) to minimize impact.

## Rollback

If a deployment causes instability:

- Evaluate rollback mode from deployment evidence before taking action:
  - `rollback-compatible`: roll back to the previous known-good digest set for affected services.
  - `roll-forward-only`: do not re-apply old binaries; execute the documented forward remediation or restore-point recovery path.
- If database schema changes were applied, follow the guidance in `design/architecture/system-architecture-database-migrations.md` for downgrade or compatibility handling.
- Use the Redis and tick system runbooks to recover any affected coordination state, ensuring idempotent replay where required.

## Related Runbooks

- `design/architecture/system-architecture-scaling-runbook.md`
- `design/architecture/system-architecture-redis-incident-runbook.md`
- `design/architecture/system-architecture-asset-store-runbook.md`
- `design/architecture/system-architecture-telnet-degraded-runbook.md`

---

## Per-Environment Deployment & Rollback Summary

| Environment | Deploy Steps | Rollback Steps |
| --- | --- | --- |
| **Staging** | Ensure CI is green → run preflight policy checks (digest pinning + secret contract) → open/merge PR that updates image digests in `k8s/overlays/stage` → apply overlay: `kubectl apply -k k8s/overlays/stage` → monitor rollout and run smoke tests | Open/merge PR that reverts `k8s/overlays/stage` to the last known-good digest set → re-apply overlay → monitor rollout |
| **Production** | Merge the `release-please` release PR to `main` and confirm the release tag (for example `v1.2.3`) exists → ensure CI and security scans are green → verify staging attestation for the exact digest set → run preflight policy checks → open/merge PR that updates `k8s/overlays/prod` to the approved digests → apply overlay: `kubectl apply -k k8s/overlays/prod` → monitor rollout and run smoke tests | Open/merge PR that reverts `k8s/overlays/prod` to the last known-good digest set → re-apply overlay → monitor rollout; follow database migration downgrade guidance when schema changes are involved |
| **Hobby / Self-Hosted** | Resolve target manifests/charts → run operator preflight (`./dev-tools/deploy/preflight.sh hobby-self-hosted`) and capture report → apply manifests/charts from operator environment → monitor rollout and run smoke tests → record deployment evidence (`manifestRef`/`chartVersion`, preflight report, rollback reference) | Re-apply previously known-good manifest/chart reference and confirm health; if schema changed, follow migration compatibility guidance |

Overlay PRs should include a clear deployment intent payload: target environment, service image digests, source commit/tag, rollback digest set (or explicit `roll-forward-only` marker), and (for production) a staging attestation reference. Attestation schema and validation requirements are defined in `design/architecture/system-architecture-promotion-attestation.md`. CI validates overlay images and preflight policy contracts via [`.github/workflows/validate-kustomize-overlays.yml`](../../.github/workflows/validate-kustomize-overlays.yml) before merge.
