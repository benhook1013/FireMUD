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

## Internal Runtime Installation Candidate

`runtime-installation-inventory.py --rendered-manifest PATH --declarations PATH --output PATH` emits deterministic candidate JSON for the [runtime ownership installation inventory](../../design/architecture/system-architecture-deployment-runbook.md#runtime-ownership-installation-inventory). It uses the existing PyYAML dependency, reads and hashes exact original input bytes without changing them, and exclusively creates the output so an existing evidence file is preserved. Retain both original inputs alongside the candidate; the candidate's two digests bind those bytes.

The closed declaration schema is `firemud-runtime-installation-declarations/v1`: it has the inventory root fields except `renderedManifestDigest` and `declarationsDigest`, plus `nonWorkloadKinds`. Each member has the inventory member fields except `image`, which the producer obtains from the exact rendered container. `nonWorkloadKinds` is a sorted unique array of exact `{apiVersion, kind}` objects explicitly classifying rendered kinds without PodSpecs; every entry must occur in the render. All ordinary/init containers, including unrelated sidecars and restartable init containers, require an exact ownership declaration. Unknown or unsupported workloads, hidden container fields, aliases, duplicate mappings, ambiguous identities, extra/missing declarations, namespace mismatch and mutable images refuse generation.

Input bindings are explicitly captured name/version/digest facts; this tool does not discover live Secret versions, prove applicable input completeness or establish an installation. The protected installation source must independently verify those facts and every applicable input, establish the current completed installation, and hold its identity through World registration. `UNRESOLVED` family classifications remain in the candidate and cannot support qualified launch writes. Candidate production supplies no installation, registration, admission or cleanup authority, and does not implement the ADR 0177 executor.

Inputs are bounded to 16 MiB of rendered YAML and 4 MiB of declarations, with at most 1,024 members; emitted JSON is bounded to 4 MiB. The focused offline vectors are `python3 dev-tools/deploy/test-runtime-installation-inventory.py`; they exercise candidate generation and refusal, not protected installation delivery.

The files under `fixtures/runtime-installation/` are controlled interoperability vectors, including synthetic catalog/input facts. The Python producer reproduces the exact candidate bytes and World’s parser consumes that same vector; they are not installed authority or a deployable enrollment.
