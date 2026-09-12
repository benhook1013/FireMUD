# Hosted environment identity controller

This install owns one fixed control namespace, `firemud-system`, and the namespaced `platform.firemud.dev/v1alpha1` `HostedEnvironmentIdentity` API. The API is intentionally closed:

```yaml
apiVersion: platform.firemud.dev/v1alpha1
kind: HostedEnvironmentIdentity
metadata:
  name: pr-123 # or dev-demo
  namespace: firemud-system
spec:
  desiredState: Active # Active -> Retired only
```

There is no user-supplied hostname, Secret, Certificate, issuer, port, key, rollout, or consumer field. The trusted lifecycle workflow supplies and prepares the runtime namespace (`pr-N` or `dev`), where `N` is one to 51 decimal digits with a nonzero first digit; the controller derives the retained identity namespace (`pr-N-identity` or `dev-identity`) and the public host (`pr-N.preview.firedevops.net` or `dev.preview.firedevops.net`). The controller materializes only the retained identity namespace and controller-owned identity material; runtime preparation remains workflow-owned.

## Resource and status contract

Controller-managed resources carry these stable labels:

- `app.kubernetes.io/name=hosted-environment-identity-controller` where the object is owned by this controller;
- `firemud.dev/managed-by=hosted-identity-controller`;
- `firemud.dev/retention=retained`;
- `firemud.dev/identity-name=<dev-demo|pr-N>`; and
- `firemud.dev/role=ingress|telnet|gateway-internal-ws|tcp-proxy-bridge|grpc` for identity material.

Existing runtime namespaces retain their workflow labels. PR namespaces must have `firemud.dev/preview=true` and a matching `firemud.dev/pr-number`; the demo namespace must have `firemud.dev/dev-demo=true` and `firemud.dev/environment-class=dev-demo-cluster`.

Status contains only non-secret evidence: `observedGeneration`, phase, conditions, a derived `profile` (including `runtimeNamespaceUid`, `requestedHeadSha`, and `deployedHeadSha`), and ingress, Telnet, Gateway-internal WebSocket, TCP Proxy bridge, and gRPC revision, source-generation, provenance, and state. `Ready` is true only when the current runtime Namespace UID and requested 40-character head SHA match the observed profile, the separately recorded post-Helm deployed head matches that request, and every consumer has converged. A runtime UID, request, or deployed-head change clears `Ready`; pre-Helm namespace preparation cannot claim deployment success.

The phases are `Pending`, `Provisioning`, `WaitingForCertificate`, `RuntimeAbsent`, `Syncing`, `Verifying`, `Ready`, `Degraded`, `Blocked`, `Retiring`, and `Retired`.
Normal Active reconciliation observes the derived profile, ensures independent cert-manager ingress and Telnet Certificates/Secrets, retains the shared `firemud-grpc-tls` transport bundle without claiming per-workload identity, and issues separate `<release>-gateway-internal-ws` server and `<release>-tcp-proxy-bridge` client identities from the same fixed internal trust anchor without sharing leaf keys. The Gateway identity has only `serverAuth` and the exact `spring-cloud-gateway-mtls.<namespace>.svc.cluster.local` DNS SAN; the TCP Proxy identity has only `clientAuth` and the exact `spiffe://firemud/ns/<namespace>/sa/tcp-proxy-service` URI SAN. Reconciliation syncs source material to runtime with resource-version compare-and-swap, and rolls all eleven gRPC consumers before fixed-SNI/SAN/issuer endpoint probes. The final acceptance gate also performs a mutual-TLS handshake to the fixed Account gRPC Service with the current projected client certificate, fixed CA, exact in-cluster SNI/hostname, and current leaf fingerprint; rollout counters alone cannot make the identity Ready. It separately uses the projected TCP Proxy bridge identity to verify the exact Gateway-internal TLS server identity. Existing runtime Secret material is copied to its fixed `*-previous` snapshot in the retained identity namespace before replacement. A clean destination may be created without a predecessor.
Renewal repeats the same generation-safe sequence.
Ordinary internal WebSocket identity renewal updates retained and projected material in place; it does not terminate the bridge. A consumer that has not reloaded the new identity keeps the controller non-ready. Explicit `Retired` intent while a runtime still exists instead scales both `spring-cloud-gateway` and `tcp-proxy-service` to zero and waits for their observed replica counts to reach zero before any retained identity cleanup can proceed, preventing stale in-memory identity reuse when replacement material is unavailable.

Missing runtime namespaces retain identity material and report a blocked or degraded condition. Retirement waits for an exact runtime Namespace `404`, then removes identity material and publishes generation-bound `Retired` with `Ready=False` while retaining the finalizer. The lifecycle requester observes that terminal status and deletes the request; only the subsequent deletion reconcile removes the finalizer. If retirement instead observes `RuntimeIdentityChanged`, the controller preserves its previously observed profile and never adopts the mismatched live values as retirement authority. The trusted lifecycle recovers by removing the whole disposable runtime namespace and proving the runtime-first exact `404`; the next retirement reconcile can then follow the normal ownership-checked retained-identity cleanup path. This does not authorize manual retained-identity deletion or bypass the bridge-shutdown and runtime-absence gates. Timeouts, non-404 API errors, resource-version conflicts, unknown API responses, mismatched SAN/EKU, or failed rollout/probe evidence stop the state machine without destructive cleanup. Reconciliation is idempotent and retries after interruption.

## Authority boundary

The requester ServiceAccount can create or change only identity objects in `firemud-system`; admission rejects any other caller, name, namespace, spec, reserved ownership metadata, or Active reactivation. The controller ServiceAccount can update only status/finalizers in that namespace, and it establishes exactly one derived identity/runtime Role and RoleBinding pair during reconciliation. The identity scope grants Certificate list/watch, named Certificate get/update/patch/delete, unscoped Certificate create, CertificateRequest list, named Secret get/update/patch/delete, and unscoped Secret create access in the retained identity namespace. The runtime scope grants only named Secret get/update/patch/delete, unscoped Secret create, and named Deployment get/update/patch access in the runtime namespace. Runtime ServiceAccounts receive none of these grants. ValidatingAdmissionPolicies add a second boundary around controller-managed TLS names, cert-manager ownership, and the exact Role/RoleBinding rules and subject. The fixed `firemud-system` Namespace is created or deleted only by `system:masters`. That `system:masters` alternative is limited to CREATE and DELETE: every caller's UPDATE or PATCH of `firemud-system`, including `system:masters`, is accepted only when labels, annotations, ownership metadata, finalizers, and spec are unchanged. The namespace-controller teardown exception is limited to DELETE of canonical managed identity material by `system:serviceaccount:kube-system:namespace-controller`.

Kubernetes RBAC cannot constrain `create`, `escalate`, or `bind` with `resourceNames`, nor can it inspect Role rules. The controller therefore has a small ClusterRole containing those unavoidable scope-writer verbs plus named update/delete access; fail-closed admission is the field, namespace, and subject boundary. Trusted bootstrap refuses active mode unless every required policy and binding passes its fail-closed readback. It has no wildcard data-plane grant and no cluster-wide Secret or Certificate access. It owns creation and retirement deletion of only the exact derived retained identity Namespace (`dev-identity` or `pr-N-identity`), after validating its labels and ownership. The controller intentionally has no Namespace `update` permission: retained Namespace drift blocks reconciliation, and its fail-closed recovery is deletion of the whole malformed retained Namespace by a `system:masters` operator followed by canonical recreation during the next eligible Active reconciliation. The runtime Namespace (`dev` or `pr-N`) remains workflow-owned and must already exist with its exact preview or dev-demo labels before identity materialization or projection. The controller has no authority to create or delete runtime Namespaces. Kubernetes cluster-admin/`system:masters` uses only the explicit policy alternatives; the fixed `firemud-system` UPDATE path remains metadata- and spec-preserving for every caller.

The NetworkPolicy permits DNS and TCP/443 plus TCP/6443 for the Kubernetes API and fixed public HTTPS probes; its IPv4/IPv6 destinations exclude link-local `169.254.0.0/16` and `fe80::/10`. The hosted k3s cluster uses flannel VXLAN with kube-router policy enforcement: `kubernetes.default` is exposed on ClusterIP `:443`, DNATed to the hosted node API endpoint `:6443`, and kube-router evaluates the effective post-DNAT destination port. Both API ports are therefore required, while the destination-broad 443 allowance also carries the exact Gateway TLS probe because a selector rule cannot narrow the union. It also permits destination-broad Telnet egress on TCP/32000-32016 (the preview allocator's 32000-32015 range plus fixed dev-demo port 32016). Only TCP/6565 to Account is label-scoped to runtime Namespaces carrying the externally owned canonical preview or dev-demo label. Kubernetes NetworkPolicy cannot identify an API server or public hostname, so the controller must enforce the derived SNI/SAN/issuer allowlist itself; these rules are explicit infrastructure allowances, not unrestricted identity trust.

The checked-in Deployment contains fail-closed image and activation markers. `bootstrap-hosted-identity-controller.sh` accepts only the approved image repository with a full SHA-256 digest and an explicit `paused`, `observe`, or `active` mode. Before invoking `kubectl`, it uses GitHub CLI attestation verification to require default SLSA provenance from this repository's `runtime-images.yml` workflow on exactly `develop` or `main` and rejects self-hosted-runner provenance. The trusted operator context therefore requires Python 3 with PyYAML, `gh` authenticated for GitHub API and private-repository attestation reads, read authentication for the private GHCR image, and network access to GitHub API, GHCR, and the Sigstore trust services used by GitHub attestations. After verification, bootstrap substitutes the image, gRPC trust-anchor fingerprint, and mode in a private render. It applies and reads back the fail-closed namespace guard and its `Deny` binding before applying the namespace-lifecycle ClusterRoleBinding, then uses server-side apply without force-conflict takeover for the complete install. Its default is `paused`; Active bootstrap retains the later gate that verifies every required validating admission policy and binding before the controller is permitted to reconcile.

The private controller image's Pod explicitly references the canonical `ghcr-preview-pull` Secret. Before bootstrap applies any installation resource, it requires `firemud-system/ghcr-preview-pull` to be a usable `kubernetes.io/dockerconfigjson` credential containing a nonempty `ghcr.io` basic-auth entry. Bootstrap neither accepts nor writes registry credentials. On a fresh cluster, a `system:masters` operator must select the trusted Kubernetes context, create the fixed namespace from the checked-in manifest, create or update the Secret with the shared helper, and only then run bootstrap (which defaults to `paused`):

```bash
kubectl auth whoami -o jsonpath='{range .status.userInfo.groups[*]}{.}{"\n"}{end}' \
  | grep -Fx system:masters >/dev/null
kubectl apply --server-side \
  --field-manager=firemud-hosted-identity-bootstrap \
  -f k8s/hosted-identity-controller/namespace.yaml
PREVIEW_GHCR_USERNAME='<registry-user>' \
PREVIEW_GHCR_TOKEN='<read-package-token>' \
  dev-tools/hosted/shared/ensure-ghcr-pull-secret.sh firemud-system
FIREMUD_HOSTED_IDENTITY_TRUSTED_OPERATOR=1 \
  dev-tools/hosted/controller/bootstrap-hosted-identity-controller.sh \
  --image 'ghcr.io/benhook1013/firemud-hosted-identity-controller@sha256:<64-hex-digest>' \
  --grpc-trust-anchor-sha256 '<64-hex-fingerprint>' \
  --activation-mode paused
```

Supply the registry values through the operator's secret mechanism; the placeholders above are sequencing documentation, not literal values. A missing, wrong-type, invalidly encoded, or credential-empty pull Secret stops bootstrap before the namespace guard or controller Deployment is applied. Rotate the credential by rerunning the shared helper, then rerun paused bootstrap before considering `observe` or `active` mode.

## Secret admission break-glass recovery

Use this recovery only when the `firemud-hosted-identity-secret-boundary` binding itself is incorrectly denying Secret writes needed to repair the hosted identity installation. From the repository root at a trusted commit, first select a trusted Kubernetes context and verify that the authenticated user belongs to `system:masters`. Only then reapply the currently deployed, attested controller image and configured gRPC trust anchor in `paused` mode. Bootstrap waits for that paused Deployment rollout; the final readback must also return exactly `paused` before admission state changes:

```bash
set -euo pipefail

kubectl auth whoami -o jsonpath='{range .status.userInfo.groups[*]}{.}{"\n"}{end}' \
  | grep -Fx system:masters >/dev/null
controller_image="$(kubectl -n firemud-system get deployment firemud-hosted-identity-controller -o jsonpath='{.spec.template.spec.containers[?(@.name=="controller")].image}')"
if [[ -z "$controller_image" ]]; then
  echo "failed to read the deployed controller image" >&2
  exit 1
fi
grpc_trust_anchor_sha256="$(kubectl -n firemud-system get deployment firemud-hosted-identity-controller -o jsonpath='{.spec.template.spec.containers[?(@.name=="controller")].env[?(@.name=="FIREMUD_HOSTED_IDENTITY_GRPC_TRUST_ANCHOR_SHA256")].value}')"
if [[ -z "$grpc_trust_anchor_sha256" ]]; then
  echo "failed to read the deployed gRPC trust anchor" >&2
  exit 1
fi
FIREMUD_HOSTED_IDENTITY_TRUSTED_OPERATOR=1 dev-tools/hosted/controller/bootstrap-hosted-identity-controller.sh \
  --image "$controller_image" \
  --grpc-trust-anchor-sha256 "$grpc_trust_anchor_sha256" \
  --activation-mode paused
kubectl -n firemud-system get deployment firemud-hosted-identity-controller \
  -o jsonpath='{.spec.template.spec.containers[?(@.name=="controller")].env[?(@.name=="FIREMUD_HOSTED_IDENTITY_ACTIVATION_MODE")].value}{"\n"}' \
  | grep -Fx paused
```

Only after both the initial identity check and the final paused-mode readback succeed, remove the affected binding:

```bash
kubectl delete validatingadmissionpolicybinding.admissionregistration.k8s.io firemud-hosted-identity-secret-boundary
```

The deletion removes the fail-closed Secret boundary; it is not permission to reactivate the controller. While the controller remains paused, perform only the reviewed, incident-specific Secret repair that the binding blocked:

```text
BEGIN INCIDENT-SPECIFIC SECRET REPAIR
Run only the reviewed kubectl command or commands required to repair the affected hosted identity Secret.
END INCIDENT-SPECIFIC SECRET REPAIR
```

Immediately restore the complete checked-in boundary by rerunning the same bootstrap command above in `paused` mode. Then read back the repaired binding's exact policy reference and sole validation action:

```bash
kubectl get validatingadmissionpolicybinding.admissionregistration.k8s.io firemud-hosted-identity-secret-boundary \
  -o jsonpath='{.spec.policyName}{"\n"}' \
  | grep -Fx firemud-hosted-identity-secret-boundary
kubectl get validatingadmissionpolicybinding.admissionregistration.k8s.io firemud-hosted-identity-secret-boundary \
  -o jsonpath='{.spec.validationActions[*]}{"\n"}' \
  | grep -Fx Deny
```

These readbacks confirm only the configured paused mode and admission binding fields; they are not a live admission probe. Keep the controller paused if any command or readback fails, and do not consider `observe` or `active` mode until the incident repair has been independently validated.
