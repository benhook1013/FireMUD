# Hosted Environment Tooling

This directory contains tooling for FireMUD's hosted Kubernetes environments.

## Folder Map

- `shared/`
  - helpers used by both hosted lanes
  - kubeconfig setup, namespace deletion, shared smoke, shared image wait, shared runtime rollout wait, rollout diagnostics, and shared pull-secret/TLS setup
  - `wait-for-hosted-runtime-rollouts.sh` owns the canonical 15-deployment rollout inventory; callers provide the namespace and per-deployment timeout

- `preview/`
  - PR-preview-only helpers
  - capacity allocation and bounded priority reclaim, PR-head freshness checks, preview namespace pruning, preview NodePort allocation, and preview-specific value rendering/summary output
  - the reconciler suppresses duplicate proof repair while the exact source or trusted consumer run is active and caps Namespace-backed proof-retry dispatches at three per exact deployed tuple
  - [Preview eligibility](preview/preview-eligibility.py) requires valid GitHub label metadata for deploy and retain operations; destroy remains eligible for cleanup when metadata is malformed. Same-repository pull requests targeting `main` or `develop` remain ordinarily eligible, while another same-repository base requires the maintainer-controlled `preview:priority` label in addition to current mergeability and exact source-tuple validation
  - priority reclaim applies only when the preview pool is full, selects the oldest ordinary allocation after rechecking both target and victim labels at the deletion boundary, and never reclaims another currently priority-labelled allocation

- `dev-demo/`
  - fixed `develop` environment helpers
  - dev-demo-specific namespace labels, value rendering, and summary output

## Rollout Failure Contract

When a hosted deploy fails after cluster access is available, start with `shared/show-rollout-diagnostics.sh`.

It is the canonical first-look diagnostic for both PR preview and dev-demo and prints:

- the namespace labels/annotations that identify the current target
- blocked workload and pod readiness reasons
- service and target-port detail
- safe selected ConfigMap values
- secret and TLS certificate summaries
- recent events, unavailable workload describes, and current plus previous logs for problematic pods

Use it before ad hoc live inspection so preview debugging stays deterministic and comparable across runs.

## Public Helpers

- [trust-bootstrap/prove-protected-pod-boundary.sh](trust-bootstrap/prove-protected-pod-boundary.sh) – operator-invoked native admission proof: `bash dev-tools/hosted/trust-bootstrap/prove-protected-pod-boundary.sh --context CONTEXT --namespace dev --pod EXACT_PROTECTED_POD` (also canonical `pr-N`). Requires Python/PyYAML and kubectl; verifies installed trusted policy content and compilation, then uses only server dry-run CREATE/UPDATE/ephemeral-container requests. This is partial admission evidence, not routing, replacement-race, shared-key closure or promotion proof; see [the attribution owner](../../design/architecture/system-architecture-jwt-and-token-contracts.md#protected-readiness-receiver-attribution).
- [shared/show-rollout-diagnostics.sh](shared/show-rollout-diagnostics.sh) – first-look diagnostics for a hosted namespace when a rollout is blocked.
- [shared/wait-for-hosted-runtime-rollouts.sh](shared/wait-for-hosted-runtime-rollouts.sh) – bounded readiness wait for the canonical hosted runtime deployment inventory.

## Disposable Native Admission Fixture

[The admission-only fixture](trust-bootstrap/fixtures/protected-pod-admission-proof.yaml) supplies real Account/Game Session-named Pod targets running pause, not application receivers. The automated runner entrypoint must first establish ownership of a wholly fresh disposable cluster and arrange exact-cluster teardown on success, failure and cancellation. Neither a context name, an absent `dev` Namespace nor fixture labels establish that ownership. Never use this fixture against shared environments. The fixture uses the existing test-tool `pause:3.10` reference; execution remains blocked until the entrypoint supplies an independently verified immutable `PROOF_PAUSE_IMAGE` digest. No digest is invented or resolved by this example.

After that prerequisite, the entrypoint can automate this sequence from the repository root with kubectl, jq and Python/PyYAML available:

```bash
set -euo pipefail
: "${proof_context:?explicit run-owned disposable context required}"
: "${PROOF_PAUSE_IMAGE:?verified registry.k8s.io/pause@sha256 digest required}"
[[ "$PROOF_PAUSE_IMAGE" =~ ^registry\.k8s\.io/pause@sha256:[a-f0-9]{64}$ ]]
existing_namespace=$(kubectl --context "$proof_context" get namespace dev --ignore-not-found -o name)
[[ -z "$existing_namespace" ]]
kubectl --context "$proof_context" create -f k8s/trust-bootstrap/deployment-admission.yaml
sed "s|registry.k8s.io/pause:3.10|$PROOF_PAUSE_IMAGE|g" \
  dev-tools/hosted/trust-bootstrap/fixtures/protected-pod-admission-proof.yaml \
  | kubectl --context "$proof_context" create -f -
for app in account-service game-session-service; do
  kubectl --context "$proof_context" -n dev rollout status "deployment/$app" --timeout=180s
  proof_pod=$(kubectl --context "$proof_context" -n dev get pods -l "app=$app" -o json \
    | jq -er '.items | select(length == 1) | .[0].metadata.name')
  bash dev-tools/hosted/trust-bootstrap/prove-protected-pod-boundary.sh \
    --context "$proof_context" --namespace dev --pod "$proof_pod"
done
```

Any missing compilation status, unexpected API/schema/PSA rejection, ambiguous Pod selection or changed target fails this stage; do not disable protections or count an unrelated rejection as the expected native-policy denial. This sequence creates fixture resources only in the owned disposable cluster; the executor's probes remain server dry-run. It establishes only native admission evidence, with no real TLS, application UID/process correspondence, CNI routing, replacement-race, shared-key closure, signer or promotion claim. Both fixture Deployments use hosted Helm's shared `firemud-app` ServiceAccount name, with token automount disabled.
