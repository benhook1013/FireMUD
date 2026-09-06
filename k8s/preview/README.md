# Preview Cluster Bootstrap

This directory captures the cluster-scoped prerequisites for FireMUD's hosted `pr-preview` environment.

The target platform is a single-node k3s cluster on Hetzner with:

- wildcard DNS under `preview.firedevops.net`
- Traefik ingress
- cert-manager with Let's Encrypt issuers
- one namespace per pull request
- a dedicated preview deployer identity separate from the default k3s admin kubeconfig
- a self-hosted GitHub Actions runner on the preview host for cluster-touching preview jobs

These manifests are intentionally cluster-scoped. They are installed once per preview cluster, not once per preview namespace.

## Apply cluster prerequisites

```bash
kubectl apply -k k8s/preview
```

This installs:

- `ClusterIssuer` resources for Let's Encrypt staging and production
- a `preview-deployer` ServiceAccount in `kube-system`
- broad cluster-scoped RBAC for the preview deployer so CI can create and destroy `pr-*` namespaces and manage namespaced resources inside them

## CI credential model

The preview GitHub Actions workflow should not expose the cluster API publicly to GitHub-hosted runners. Instead:

- GitHub-hosted jobs handle orchestration and any future image build/push work
- the self-hosted `preview` runner on the Hetzner host handles namespace prep, secret creation, manifest validation, and eventual Helm apply/destroy
- that self-hosted runner uses a dedicated kubeconfig derived from the `preview-deployer` ServiceAccount rather than the raw k3s admin kubeconfig

Recommended GitHub secrets:

- `PREVIEW_GHCR_USERNAME`
- `PREVIEW_GHCR_TOKEN`

Recommended GitHub Actions variables:

- `PREVIEW_MAX_ACTIVE`
  - optional
  - defaults to `1` when unset
  - enforced by `preview.yml` by counting namespaces labeled `firemud.dev/preview=true`

## Current limitation

These manifests prepare the preview cluster itself. The repository now also contains a preview workflow and chart path that can:

- reach the cluster from the self-hosted preview runner without exposing the Kubernetes API broadly
- create preview namespaces
- create/update GHCR pull secrets
- create/update preview gRPC TLS secrets
- render preview manifests
- validate those manifests against the live cluster API with server-side dry-run
- deploy or upgrade a real Helm release into `pr-*` namespaces
- seed the preview bootstrap state needed for reviewer proof
- run hosted smoke against the TCP/Telnet path

The dev-demo credential-bearing account bootstrap runs on the self-hosted runner
through the Kubernetes API: it first fail-closes on
`kubectl auth can-i create pods/portforward`, then uses a loopback-only
`kubectl port-forward --address 127.0.0.1 service/spring-cloud-gateway <port>:80`.
Only the non-credential session/world bootstrap remains in the short-lived
in-cluster pod; that pod receives the account-id ConfigMap only and never a
credential Secret or credential environment variables. This bounded hop is required until an internal HTTPS/mTLS
Gateway listener is available.

Current implementation limitations:

- preview redeploy is intentionally clean-state today; the workflow resets the namespace before deploy rather than preserving mutable PR state across updates
- the first hosted proof target remains manual and smoke-backed `LOGIN -> PLAY -> LOOK` over the TCP/Telnet path, not a broader browser-first environment contract
- the dedicated first-party frontend/runtime delivery path remains later work

## Current secrets and JWT stance

- Preview target state requires PR-unique JWT signing material and JWKS data for each preview namespace so tokens minted in one PR environment cannot validate in another.
- The current checked-in Helm values mount signing material through `jwt-signing-keys` and `jwt-jwks` resources and point services at file-mounted JWT paths. That matches the broader Kubernetes application contract better than the older inline-secret model.
- The preview value renderer now generates namespace-local signing-key and JWKS content for each rendered deployment, so preview no longer depends on one static inline shared JWT secret. The remaining gap is lifecycle ownership and rotation proof, not generation itself.
- The target preview contract is to create or inject a namespace-local signing-key Secret and matching JWKS resource during preview namespace preparation, then mount or reference those resources through the same application-level contract used by the rest of the Kubernetes-backed stack, with ConfigMap JWKS allowed only because preview keys are explicitly test-only material.

## Current transport stance

- Preview keeps the **target-state** service topology, auth/session model, and per-PR namespace isolation.
- Preview now follows the same Spring Boot SSL-bundle plus Spring gRPC server SSL-bundle binding contract as the other Kubernetes-backed environments.
- The checked-in Helm values mount workload mTLS material, set `FIREMUD_GRPC_PLAINTEXT: "false"`, and keep the internal gRPC topology aligned with the non-local target state rather than carrying a separate preview-only plaintext mode.
- The remaining transport distinction in preview is the TCP bridge's `ws://spring-cloud-gateway/ws/game` bootstrap path for reviewer-facing Telnet smoke, not an internal gRPC plaintext exception.

## Current network-policy stance

- Hosted preview now renders checked-in baseline internal-service `NetworkPolicy` resources from the Helm chart.
- The player-facing Kustomize/base path and the hosted preview/dev-demo path now share one checked-in baseline policy posture, even though the actual environment classes still differ in lifecycle and operational scope.

## Current TCP bootstrap contract

- Hosted preview TCP smoke and manual reviewer proof both assume a bootstrap gameplay session exists before `LOGIN`.
- Preview currently creates that bootstrap state explicitly rather than deriving it from a hidden client-side convention:
  - the preview workflow creates the smoke account in tenant `1`
  - preview `tcp-proxy-service` is given preview-only default bootstrap metadata
  - the smoke path expects the initial bootstrap session to resolve to session `1` in tenant `1`
- That bootstrap contract is preview-only operational glue, not the long-term player-facing TCP contract.
- The purpose of this explicit bootstrap state is to keep preview reviewer-usable while the actual gameplay admission path is still being hardened and documented.
- The cleanup path for retiring or replacing this bootstrap contract is tracked in `02.15.5-task-list-preview-tcp-admission-cleanup-vertical-slice.md`.

Preview TCP contract:

- preview TCP uses a small reserved external port range `32000-32015`
- one port is allocated per live preview namespace
- this is preview-only multiplexing on a shared host/IP, not the long-term production Telnet edge contract

The dedicated first-party browser client remains a later concern. If a temporary preview-only browser helper is used during bring-up, it should not be treated as the long-term frontend hosting architecture; that role belongs to a dedicated first-party web application service.
