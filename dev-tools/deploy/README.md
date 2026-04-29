# Deployment Validation Helpers

This directory contains the canonical FireMUD deployment-validation entrypoints.

These scripts are not generic CI utilities. They enforce the repository's deployment contract for player-facing and self-hosted environments.

## Script Map

- `preflight.sh`
  - Canonical deployment pre-check entrypoint.
  - Renders the target manifests, validates FireMUD deployment policy, writes a JSON report, and fails when required policy checks do not pass.
  - Supports `staging`, `production`, and `hobby-self-hosted` environment classes.
  - Used by operator deployment workflows and by CI static-policy validation.

- `validate-kustomize-overlays.sh`
  - CI-focused validator for the checked-in Kustomize overlays.
  - Renders the `stage` and `prod` overlays, checks that referenced images exist, enforces staging backup-marker rules, and runs production preflight validation in `ci-static` context when the PR includes the required production attestation inputs.

## Choosing The Right Script

- Use `preflight.sh` when validating an actual environment deploy or promotion.
- Use `validate-kustomize-overlays.sh` when validating the checked-in overlay lane itself.
- Do not treat `validate-kustomize-overlays.sh` as a substitute for operator preflight on real deployments.

## Related Docs

- [system-architecture-deploy-preflight-policy.md](../../design/architecture/system-architecture-deploy-preflight-policy.md)
- [system-architecture-deployment-runbook.md](../../design/architecture/system-architecture-deployment-runbook.md)
