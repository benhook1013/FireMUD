# FireMUD Deployment Runbook

This runbook describes the **standard deployment flow** for FireMUD in Kubernetes-backed environments.

For high-level CI/CD architecture, see `design/architecture/system-architecture-cicd.md`. This document focuses on the concrete steps and checks an operator performs when rolling out a new version.

## Prerequisites

- CI pipeline has produced a tagged image for each service to deploy.
- Database migrations have been validated (see `design/architecture/system-architecture-database-migrations.md`).
- Redis, PostgreSQL, and core infrastructure components (Gateway, TCP Proxy, Observability stack) are healthy.
- The operator has `kubectl` access and a kubeconfig for the target Kubernetes cluster (staging or production) from a secure admin workstation or bastion host.

## Standard Deployment Flow

1. **Review Release Notes**
   - Confirm which services and schema changes are included.
   - Identify any manual migration or configuration steps called out for operators.
2. **Verify CI/CD Status**
   - Ensure the GitHub Actions pipeline for the target branch and tag is green.
   - Check that container images are available in the configured registry.
3. **Apply Kubernetes Manifests**
   - Update the image tags in the environment-specific Kustomize overlays for the target environment (for example `k8s/overlays/stage` or `k8s/overlays/prod`).
   - Apply the manifests (for example `kubectl apply -k k8s/overlays/prod`).
4. **Monitor Rollout**
   - Watch deployment rollout status for each updated service.
   - Verify pod readiness and liveness probes are passing.
   - Check logs for startup errors, especially around database connectivity, Redis connectivity, and secrets loading.
5. **Post-Deployment Checks**
   - Run smoke tests and login/session checks as described in `design/developer-workflows/login-session-smoke-tests.md`.
   - Confirm that the game session tick loop is running and that players can connect via both Web client and Telnet.

## Canary or Phased Rollouts

For higher-risk changes:

- Deploy to a non-production environment and run extended smoke, load, and gameplay tests.
- Use Kubernetes deployment strategies (for example `RollingUpdate` with conservative surge/unavailable settings) to minimize impact.

## Rollback

If a deployment causes instability:

- Roll back to the previous known-good image tag for the affected services.
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
| **Staging** | Merge to `develop` → ensure CI is green → update image tags in `k8s/overlays/stage` if needed → `kubectl apply -k k8s/overlays/stage` → monitor rollout and run smoke tests | Revert image tags in `k8s/overlays/stage` to the previous release → re-apply overlays → monitor rollout |
| **Production** | Create/merge release tag (for example `v1.2.3`) via `release-please` → ensure CI and security scans are green → update image tags in `k8s/overlays/prod` to the tagged images → `kubectl apply -k k8s/overlays/prod` → monitor rollout and run smoke tests | Revert image tags in `k8s/overlays/prod` to the last known-good tag → re-apply overlays → monitor rollout; follow database migration downgrade guidance when schema changes are involved |
