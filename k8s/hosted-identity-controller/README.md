# Hosted environment identity controller

This install owns one fixed control namespace, `firemud-system`, and the
namespaced `platform.firemud.dev/v1alpha1` `HostedEnvironmentIdentity` API.
The API is intentionally closed:

```yaml
apiVersion: platform.firemud.dev/v1alpha1
kind: HostedEnvironmentIdentity
metadata:
  name: pr-123 # or dev-demo
  namespace: firemud-system
spec:
  desiredState: Active # Active -> Retired only
```

There is no user-supplied hostname, namespace, Secret, Certificate, issuer,
port, key, rollout, or consumer field. `pr-N` derives the runtime namespace
`pr-N`, identity namespace `pr-N-identity`, and host
`pr-N.preview.firedevops.net`. `dev-demo` derives `dev`, `dev-identity`, and
`dev.preview.firedevops.net`. The controller is the only component that may
materialize these values.

## Resource and status contract

Controller-managed resources carry these stable labels:

- `app.kubernetes.io/name=hosted-environment-identity-controller` where the
  object is owned by this controller;
- `firemud.dev/managed-by=hosted-identity-controller`;
- `firemud.dev/identity-name=<dev-demo|pr-N>`; and
- `firemud.dev/role=ingress|telnet|grpc` for identity material.

Existing runtime namespaces retain their workflow labels. PR namespaces must
have `firemud.dev/preview=true` and a matching `firemud.dev/pr-number`; the
demo namespace must have `firemud.dev/dev-demo=true` and
`firemud.dev/environment-class=dev-demo-cluster`.

Status contains only non-secret evidence: `observedGeneration`, phase,
conditions, a derived `profile` (including `runtimeNamespaceUid` and
`deployedHeadSha`), and ingress/telnet/grpc revision, source-generation,
provenance, and state. `Ready` is true only when the current runtime Namespace
UID and deployed 40-character head SHA match the observed profile and every
consumer has converged. A runtime UID or head change clears `Ready`; it never
silently reuses material from another runtime.

The phases are `Pending`, `Provisioning`, `WaitingForCertificate`,
`RuntimeAbsent`, `Syncing`, `Verifying`, `Ready`, `Degraded`, `Blocked`,
`Retiring`, and `Retired`.
Normal Active reconciliation observes the derived profile, ensures independent
cert-manager ingress and Telnet Certificates/Secrets, retains the shared
`firemud-grpc-tls` transport bundle without claiming per-workload identity,
syncs source material to runtime with resource-version compare-and-swap, and
rolls all eleven gRPC consumers before fixed-SNI/SAN/issuer endpoint probes.
Existing runtime Secret material is copied to its fixed `*-previous` snapshot
before replacement. A clean destination may be created without a predecessor.
Renewal repeats the same generation-safe sequence.

Missing runtime namespaces retain identity material and report a blocked or
degraded condition. Retirement waits for an exact runtime Namespace `404`, then
removes the identity/runtime material and finalizer. Timeouts, non-404 API
errors, resource-version conflicts, unknown API responses, mismatched SAN/EKU,
or failed rollout/probe evidence stop the state machine without destructive
cleanup. Reconciliation is idempotent and retries after interruption.

## Authority boundary

The requester ServiceAccount can create or change only identity objects in
`firemud-system`; admission rejects any other caller, name, namespace, spec,
reserved ownership metadata, or Active reactivation. The controller ServiceAccount
can update only status/finalizers in that namespace, and it establishes exactly
one derived identity/runtime Role and RoleBinding pair during reconciliation. A
trusted operator may apply one named scope with
`ensure-hosted-identity-scope.sh` for bootstrap/debug, but that helper is not a
workflow permission and never enumerates environments. The resulting scope
grants the controller named Secret/Certificate access in the identity namespace
and named Secret, workload rollout, Service, Ingress, and Pod observation in the
runtime namespace. Runtime ServiceAccounts receive none of these grants.
ValidatingAdmissionPolicies add a second boundary around controller-managed TLS
names, cert-manager ownership, and the exact Role/RoleBinding rules and subject.

Kubernetes RBAC cannot constrain `create`, `escalate`, or `bind` with
`resourceNames`, nor can it inspect Role rules. The controller therefore has a
small ClusterRole containing those unavoidable scope-writer verbs plus named
update/delete access; fail-closed admission is the field, namespace, and
subject boundary. If these policies are unavailable or not enforced, the
controller must remain paused. It has no wildcard data-plane grant and no
cluster-wide Secret or Certificate access. It owns creation and retirement
deletion of only the exact derived retained identity Namespace (`dev-identity`
or `pr-N-identity`), after validating its labels and ownership. The runtime
Namespace (`dev` or `pr-N`) remains workflow-owned and must already exist with
its exact preview or dev-demo labels before identity materialization or
projection. The controller has no authority to create or delete runtime
Namespaces. Kubernetes cluster-admin/system:masters can always bypass these
policy-level constraints.

The NetworkPolicy permits DNS and TCP/443 for the Kubernetes API plus fixed
public HTTPS probes. It also permits the allocator's 32000-32016 Telnet range.
Kubernetes NetworkPolicy cannot identify an API server or public hostname, so
the controller must enforce the derived SNI/SAN/issuer allowlist itself; the
443 rule is an explicit infrastructure limitation, not unrestricted identity
trust.

The checked-in Deployment contains fail-closed image and activation markers.
`bootstrap-hosted-identity-controller.sh` accepts only the approved image
repository with a full SHA-256 digest and an explicit `paused`, `observe`, or
`active` mode, substitutes the image, gRPC trust-anchor fingerprint, and mode
in a private render, and uses server-side apply without force-conflict takeover.
Its default is `paused`; Active bootstrap verifies every required validating
admission policy and binding before the controller is permitted to reconcile.
