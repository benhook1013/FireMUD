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

There is no user-supplied hostname, Secret, Certificate, issuer, port, key, rollout, or consumer field. The trusted lifecycle workflow supplies and prepares the runtime namespace (`pr-N` or `dev`); the controller derives the retained identity namespace (`pr-N-identity` or `dev-identity`) and the public host (`pr-N.preview.firedevops.net` or `dev.preview.firedevops.net`). The controller materializes only the retained identity namespace and controller-owned identity material; runtime preparation remains workflow-owned.

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

Missing runtime namespaces retain identity material and report a blocked or degraded condition. Retirement waits for an exact runtime Namespace `404`, then removes identity material and publishes generation-bound `Retired` with `Ready=False` while retaining the finalizer. The lifecycle requester observes that terminal status and deletes the request; only the subsequent deletion reconcile removes the finalizer. Timeouts, non-404 API errors, resource-version conflicts, unknown API responses, mismatched SAN/EKU, or failed rollout/probe evidence stop the state machine without destructive cleanup. Reconciliation is idempotent and retries after interruption.

## Authority boundary

The requester ServiceAccount can create or change only identity objects in `firemud-system`; admission rejects any other caller, name, namespace, spec, reserved ownership metadata, or Active reactivation. The controller ServiceAccount can update only status/finalizers in that namespace, and it establishes exactly one derived identity/runtime Role and RoleBinding pair during reconciliation. The resulting scope grants the controller named Secret/Certificate access in the identity namespace and named Secret, workload rollout, Service, Ingress, and Pod observation in the runtime namespace. Runtime ServiceAccounts receive none of these grants. ValidatingAdmissionPolicies add a second boundary around controller-managed TLS names, cert-manager ownership, and the exact Role/RoleBinding rules and subject. The fixed `firemud-system` Namespace is created or deleted only by `system:masters`. That `system:masters` alternative is limited to CREATE and DELETE: every caller's UPDATE or PATCH of `firemud-system`, including `system:masters`, is accepted only when labels, annotations, ownership metadata, finalizers, and spec are unchanged. The namespace-controller teardown exception is limited to DELETE of canonical managed identity material by `system:serviceaccount:kube-system:namespace-controller`.

Kubernetes RBAC cannot constrain `create`, `escalate`, or `bind` with `resourceNames`, nor can it inspect Role rules. The controller therefore has a small ClusterRole containing those unavoidable scope-writer verbs plus named update/delete access; fail-closed admission is the field, namespace, and subject boundary. If these policies are unavailable or not enforced, the controller must remain paused. It has no wildcard data-plane grant and no cluster-wide Secret or Certificate access. It owns creation and retirement deletion of only the exact derived retained identity Namespace (`dev-identity` or `pr-N-identity`), after validating its labels and ownership. The runtime Namespace (`dev` or `pr-N`) remains workflow-owned and must already exist with its exact preview or dev-demo labels before identity materialization or projection. The controller has no authority to create or delete runtime Namespaces. Kubernetes cluster-admin/`system:masters` uses only the explicit policy alternatives; the fixed `firemud-system` UPDATE path remains metadata- and spec-preserving for every caller.

The NetworkPolicy permits DNS and TCP/443 plus TCP/6443 for the Kubernetes API and fixed public HTTPS probes; its IPv4/IPv6 destinations exclude link-local `169.254.0.0/16` and `fe80::/10`. The hosted k3s cluster uses flannel VXLAN with kube-router policy enforcement: `kubernetes.default` is exposed on ClusterIP `:443`, DNATed to the hosted node API endpoint `:6443`, and kube-router evaluates the effective post-DNAT destination port. Both API ports are therefore required, while 443 remains required for public HTTPS probes. It also permits the preview allocator's 32000-32015 Telnet range plus the fixed dev-demo port 32016, TCP/6565 only to Account, and TCP/443 to Gateway in runtime Namespaces carrying the externally owned canonical preview or dev-demo label; the earlier public/API TCP/443 allowance remains destination-broad by necessity. Kubernetes NetworkPolicy cannot identify an API server or public hostname, so the controller must enforce the derived SNI/SAN/issuer allowlist itself; these rules are explicit infrastructure allowances, not unrestricted identity trust.

The checked-in Deployment contains fail-closed image and activation markers. `bootstrap-hosted-identity-controller.sh` accepts only the approved image repository with a full SHA-256 digest and an explicit `paused`, `observe`, or `active` mode, substitutes the image, gRPC trust-anchor fingerprint, and mode in a private render, and uses server-side apply without force-conflict takeover. Its default is `paused`; Active bootstrap verifies every required validating admission policy and binding before the controller is permitted to reconcile.
