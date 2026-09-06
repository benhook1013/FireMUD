#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
python3 "$ROOT_DIR/dev-tools/validation/check_dev_demo_summary.py" "$ROOT_DIR"
python3 "$ROOT_DIR/dev-tools/validation/test_check_dev_demo_summary.py"

workflow="$ROOT_DIR/.github/workflows/dev-demo.yml"
runtime_rbac="$ROOT_DIR/k8s/preview/preview-deployer-rbac.yaml"
for required in \
  'HOSTED_IDENTITY_REQUESTER_KUBECONFIG' \
  'DEV_DEMO_RUNTIME_KUBECONFIG' \
  'apiVersion: platform.firemud.dev/v1alpha1' \
  'kind: HostedEnvironmentIdentity' \
  'name: dev-demo' \
  'namespace: firemud-system' \
  'desiredState: Active' \
  'desiredState: Retired' \
  'wait-for-hosted-identity.sh' \
  'prune-stale-preview-namespaces.sh' \
  '--delete-runtime' \
  'Read controller-owned TCP port' \
  'Create and annotate exact dev-demo runtime namespace' \
  'annotate-dev-demo-namespace.sh' \
  'Inject fixed trusted dev-demo Telnet port' \
  'dev-demo-bootstrap:' \
  'needs: [dev-demo-plan, dev-demo-identity-request]' \
  'needs: [dev-demo-plan, dev-demo-bootstrap]' \
  'needs: [dev-demo-plan, dev-demo-deploy]' \
  'runtime_prepared: ${{ steps.runtime-prepared.outputs.ready }}' \
  'test "$port" = 32016' \
  'kubectl auth can-i create pods/portforward'; do
  grep -Fq -- "$required" "$workflow" || {
    echo "$workflow must contain: $required" >&2
    exit 1
  }
done
grep -Fq -- 'elif [[ "$identity_name" == dev-demo ]]; then' "$ROOT_DIR/dev-tools/hosted/preview/wait-for-hosted-identity.sh" || {
  echo "dev-demo waiter must map the two-argument identity name to runtime namespace dev" >&2
  exit 1
}
grep -Fq -- 'runtime_namespace=dev' "$ROOT_DIR/dev-tools/hosted/preview/wait-for-hosted-identity.sh" || {
  echo "dev-demo waiter must use runtime namespace dev" >&2
  exit 1
}
deploy_body="$(sed -n '/^  dev-demo-deploy:/,/^  dev-demo-bootstrap:/p' "$workflow")"
if grep -Fq -- 'Create dev-demo smoke account' <<<"$deploy_body"; then
  echo "$workflow must not bootstrap the smoke account before Active identity request" >&2
  exit 1
fi
bootstrap_body="$(sed -n '/^  dev-demo-bootstrap:/,/^  dev-demo-verify:/p' "$workflow")"
grep -Fq -- 'Create dev-demo smoke account' <<<"$bootstrap_body" || {
  echo "$workflow must bootstrap the smoke account after Active identity request" >&2
  exit 1
}
if grep -Eq '^        if:' <<<"$bootstrap_body"; then
  echo "$workflow must not repeat the already-job-guarded bootstrap step condition" >&2
  exit 1
fi
grep -Fq -- 'pods/portforward' "$runtime_rbac" || {
  echo "$runtime_rbac must grant the dev-demo bootstrap port-forward subresource" >&2
  exit 1
}
grep -Fq -- 'verbs: ["create"]' "$runtime_rbac" || {
  echo "$runtime_rbac must keep port-forward access create-only" >&2
  exit 1
}
for forbidden in \
  'PREVIEW_KUBECONFIG' \
  'ensure-grpc-tls-secret.sh' \
  'ensure-dev-demo-namespace.sh' \
  'delete-hosted-namespace.sh' \
  'Wait for controller-provisioned dev-demo namespace'; do
  if grep -Fq -- "$forbidden" "$workflow"; then
    echo "$workflow must not use legacy hosted identity/runtime lifecycle helper: $forbidden" >&2
    exit 1
  fi
done
