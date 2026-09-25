# Deployment Validation Helpers

This directory contains the canonical FireMUD deployment-validation entrypoints.

These scripts answer two different questions:

- Is a real target environment safe and complete enough to deploy, promote, or reopen?
- Are the checked-in Kubernetes overlay definitions in the repo internally valid?

They are not generic CI utilities. They enforce the deployment contract for player-facing and self-hosted environments.

## Script Map

- `preflight.py`
  - Canonical deployment pre-check entrypoint for a real target environment.
  - Use this before trusting a rendered deployment, before apply/promotion, or before reopening traffic after a major environment change.
  - It renders the target manifests, validates FireMUD deployment policy, writes a JSON report, and fails when required policy checks do not pass.
  - Supports `staging`, `production`, and `hobby-self-hosted` environment classes.
  - Its `hosted-bridge <render-path> <namespace> <release-name>` form applies the same `PREFLIGHT-BRIDGE-001` validator to preview/dev-demo Helm output; optional `--expected-hosted-telnet-node-port <port>` requires the exact expected rendered Telnet NodePort and rejects any other explicit `nodePort`; operator context additionally checks that the controller-projected `<release>-gateway-internal-ws` and `<release>-tcp-proxy-bridge` Secrets exist with `tls.crt`, `tls.key`, and `ca.crt`, and that `<release>-telnet-tls` exists with `tls.crt` and `tls.key`.
  - The ordinary environment form is used by operator deployment workflows and CI static-policy validation; hosted workflow integration for the `hosted-bridge` form is tracked separately from the validator capability.

- `write-traffic-open-evidence.py`
  - Canonical writer for hobby traffic-open projection records.
  - It does not write production traffic-open evidence. Production projection export belongs to the durable environment-wide recovery controller after finalization, and the pre-release gate cannot use a checked-in projection or caller-supplied evidence.
  - It validates the referenced hobby preflight report before emitting the projection JSON.

- `validate-kustomize-overlays.sh`
  - CI-focused validator for the checked-in Kubernetes overlay definitions in the repo.
  - Use this when validating overlay changes in a PR, not as the main gate for a real environment deploy.
  - It renders the `stage` and `prod` overlays, checks that referenced images exist, and enforces staging backup-marker rules. Any PR changing `k8s/**` runs production preflight in `ci-static` context, bound to the full current commit when no production attestation applies. Changes under `k8s/overlays/prod` additionally require exactly one production promotion attestation; roll-forward-only changes also require exactly one backup-readiness record. Shared `k8s/base`, Postgres, and Velero changes receive static policy validation without requiring production promotion evidence.

## Choosing The Right Script

- Use `preflight.py` when the question is: "Is this environment/deployment valid enough to proceed?"
- Use `validate-kustomize-overlays.sh` when the question is: "Did we define the repo's `stage`/`prod` overlays correctly?"
- Do not treat `validate-kustomize-overlays.sh` as a substitute for operator preflight on real deployments.

## Related Docs

- [system-architecture-deploy-preflight-policy.md](../../design/architecture/system-architecture-deploy-preflight-policy.md)
- [system-architecture-deployment-runbook.md](../../design/architecture/system-architecture-deployment-runbook.md)
