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

The next hosted preview milestone is:

- real Helm apply into `pr-*` namespaces
- real reviewer-accessible preview traffic
- manual `LOGIN -> PLAY -> LOOK` proof over the TCP/Telnet path first

## Current secrets and JWT stance

- Preview target state requires PR-unique JWT signing material and JWKS data for each preview namespace so tokens minted in one PR environment cannot validate in another.
- The current checked-in Helm values mount signing material through `jwt-signing-keys` and `jwt-jwks` resources and point services at file-mounted JWT paths. That matches the broader Kubernetes application contract better than the older inline-secret model.
- The remaining gap is namespace uniqueness and lifecycle ownership: the checked-in example values still use placeholder signing-key/JWKS content, and the default preview workflow does not yet prove per-namespace generation or rotation of distinct JWT material. Until that wiring exists, preview JWT/JWKS isolation is not fully proven by the default workflow.
- The target preview contract is to create or inject a namespace-local signing-key Secret and matching JWKS resource during preview namespace preparation, then mount or reference those resources through the same application-level contract used by the rest of the Kubernetes-backed stack, with ConfigMap JWKS allowed only because preview keys are explicitly test-only material.

## Current transport stance

- Preview keeps the **target-state** service topology, auth/session model, and per-PR namespace isolation.
- Preview currently uses a temporary plaintext internal gRPC exception while the Spring gRPC SSL-bundle migration is still being re-proven. That exception is preview-only, must remain documented here and in the transport-alignment slice, and must be removed once preview mTLS is validated end-to-end again.
- The canonical non-local target state remains Spring Boot SSL bundles plus Spring gRPC server SSL-bundle binding for internal gRPC everywhere outside intentionally relaxed local development.
- The explicit cleanup path is:
  - keep preview transport expectations documented,
  - migrate services away from legacy top-level `grpc.server.*` assumptions to `spring.grpc.server.ssl.*` with `spring.ssl.bundle.*`,
  - add CI/static checks to prevent legacy server-TLS property drift,
  - remove the temporary preview plaintext exception after preview mTLS is re-proved.

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
