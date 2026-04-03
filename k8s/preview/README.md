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
  - defaults to `2` when unset
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

The dedicated first-party browser client remains a later concern. If a temporary preview-only browser helper is used during bring-up, it should not be treated as the long-term frontend hosting architecture; that role belongs to a dedicated first-party web application service.
