# Deployment Validation Helpers

This directory contains the canonical FireMUD deployment-validation entrypoints.

These scripts answer two different questions:

- Is a real target environment safe and complete enough to deploy, promote, or reopen?
- Are the checked-in Kubernetes overlay definitions in the repo internally valid?

They are not generic CI utilities. They enforce the deployment contract for player-facing and self-hosted environments.

## Script Map

- `preflight.sh`
  - Canonical deployment pre-check entrypoint for a real target environment.
  - Use this before trusting a rendered deployment, before apply/promotion, or before reopening traffic after a major environment change.
  - It renders the target manifests, validates FireMUD deployment policy, writes a JSON report, and fails when required policy checks do not pass.
  - Supports `staging`, `production`, and `hobby-self-hosted` environment classes.
  - Used by operator deployment workflows and by CI static-policy validation.

- `validate-kustomize-overlays.sh`
  - CI-focused validator for the checked-in Kubernetes overlay definitions in the repo.
  - Use this when validating overlay changes in a PR, not as the main gate for a real environment deploy.
  - It renders the `stage` and `prod` overlays, checks that referenced images exist, enforces staging backup-marker rules, and runs production preflight validation in `ci-static` context when the PR includes the required production attestation inputs.

## Choosing The Right Script

- Use `preflight.sh` when the question is: "Is this environment/deployment valid enough to proceed?"
- Use `validate-kustomize-overlays.sh` when the question is: "Did we define the repo's `stage`/`prod` overlays correctly?"
- Do not treat `validate-kustomize-overlays.sh` as a substitute for operator preflight on real deployments.

## Related Docs

- [system-architecture-deploy-preflight-policy.md](../../design/architecture/system-architecture-deploy-preflight-policy.md)
- [system-architecture-deployment-runbook.md](../../design/architecture/system-architecture-deployment-runbook.md)
