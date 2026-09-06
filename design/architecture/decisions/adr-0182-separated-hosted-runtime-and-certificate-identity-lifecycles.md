# ADR 0182: Separated Hosted Runtime and Certificate-Identity Lifecycles

## Status

Accepted

## Implementation Status

The repository now contains the selected in-cluster materializer/reconciler API, manifests, Helm mode, and trusted workflow contracts for this decision. Local/static checks cover the trust split, lifecycle ordering, status gate, and rendered Secret consumers; no live hosted rollout, certificate issuance, renewal, or consumer-convergence proof is claimed yet. Stage and production continue to use their existing externally managed certificate and identity lifecycle; no new stage or production external integration is claimed or proven here.

## Decision Record

- Human review status: Completed
- Human review date: 2026-09-06
- Human review disposition: Revised
- Review source: `OPS-07`
- Decision date: 2026-09-06
- Decision key: `OPS-07`
- Primary capability: `PO-3.2` environment, configuration, secret, certificate, and service-discovery delivery
- Affected capabilities: `PO-3.1`, `PO-4.4`, `SF-1.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: explicit human approval and consequential-design refinement on 2026-09-06

## Context

Hosted PR previews and the fixed dev-demo environment need clean, disposable runtime namespaces so redeploys and reviewer proof do not inherit application state. Certificates and internal workload identity have a different operational lifetime: deleting their material with every runtime reset causes unnecessary trust churn, while allowing untrusted PR workflows to control retained identity material widens the deployment trust boundary.

[ADR 0178](./adr-0178-disposable-transport-complete-pr-preview-proof.md) establishes disposable, head-bound preview proof. Its namespace deletion rule needs a narrower distinction between disposable runtime resources and retained certificate identity. The controller lifecycle preserves ADR 0178's transport-complete proof obligations; it does not replace gameplay proof with certificate checks.

## Decision

### A small in-cluster materializer owns retained identity

Each hosted PR preview and the fixed dev-demo environment has a disposable runtime namespace and a retained identity namespace. The in-cluster `HostedEnvironmentIdentity` controller is the materializer and reconciler for the retained identity and its declared consumers; cert-manager remains the public certificate issuer. The canonical API is `platform.firemud.dev/v1alpha1`, kind `HostedEnvironmentIdentity`, in namespace `firemud-system`, named `pr-<PR_NUMBER>` or `dev-demo`. Its request spec contains only `desiredState: Active|Retired`; the controller owns derived namespaces, hosts, ports, Secret consumers, and status projections.

The controller establishes identity scope only through its one-time bootstrap/admission guard and constrained service-account access. Pull-request-controlled jobs, PR source, runtime workloads, and ordinary runtime cleanup credentials must not create, rotate, replace, or delete retained identity material. A trusted default-branch workflow validates the reviewed render artifact and applies only the canonical request with requester credentials. It uses a separate runtime credential to prepare the disposable namespace and workloads; it does not invoke shell identity creation, synchronization, retirement, or token-minting logic.

### Runtime-first activation and explicit retirement

Runtime namespaces remain clean and disposable. A trusted lifecycle may reclaim, redeploy, release, or let a lease expire on the runtime namespace while retaining the active identity namespace. Runtime preparation creates or reuses the exact `pr-<PR_NUMBER>` or `dev` namespace, binds the reviewed head and image, allocates the bounded Telnet port, and applies the runtime before it requests `Active`; missing identity TLS Secrets may leave workloads pending without starting a new issuance before runtime preparation succeeds.

A PR close or merge, or an explicit identity-retirement operation, requests `Retired` only after runtime cleanup has observed the exact runtime `NotFound` result. Runtime cleanup and identity retirement are separate operations. If the provider or lifecycle API cannot reliably establish that distinction, the safe result is to retain identity, report the lifecycle as incomplete or uncertain, and fail closed; an uncertain response never triggers broader deletion.

### Independent public keys and retained internal identity

Ingress and public Telnet certificates use independent key material and certificate identities. Renewal or replacement of one does not silently replace the other. The internal gRPC identity and trust material are retained across ordinary runtime namespace replacement, reclaim, release, and lease expiry, subject to the identity lifecycle and explicit retirement rules. This retention avoids needless gRPC trust churn while preserving independent public endpoint compromise and renewal boundaries.

Identity resources are environment/PR-scoped and may be used only by their declared consumers. No identity or certificate resource is shared across PR previews, and dev-demo remains separate from PR preview capacity and cleanup. A gRPC bundle is transport-only and is not per-workload authorization; workload authorization remains governed by the explicit mTLS/JWT contracts in [ADR 0038](./adr-0038-explicit-jwt-profiles-and-mtls-workload-identity.md). [ADR 0032](./adr-0032-kubernetes-native-secret-delivery-without-mandatory-vault.md) remains the Secret-delivery authority.

### Generation-bound readiness and certificate convergence

The controller publishes non-secret status only. A `Ready=True` result is valid only when `status.observedGeneration` equals `metadata.generation`, the live runtime namespace UID and requested deployed head SHA match the status profile, rollout and served proofs have converged, and non-empty ingress, Telnet, and gRPC revisions are present. The workflow treats `Pending`, `Provisioning`, `WaitingForCertificate`, `RuntimeAbsent`, `Syncing`, `Verifying`, `Degraded`, `Blocked`, `Retiring`, and `Retired` as non-ready and uses the Ready condition's reason/message for failure detail.

Certificate issuance and replacement for one identity proceed through a serialized lifecycle. The controller and issuer path validate actual served certificates and keys, including validity, required SANs, key correspondence without exposing private material, expected issuer and trust chain, monotonic identity generation, and distinct SPKI for intentional replacement. After issuance, affected ingress, Telnet, and, where applicable, gRPC consumers must reload or restart and serve or accept the new identity. A consumer that has not converged leaves the lifecycle failed or pending and does not authorize removal of the prior usable identity solely because a new resource exists.

Certificate and identity evidence contains only non-secret references, generations, fingerprints or content digests, validity/SAN/issuer results, revisions, and pass/fail outcomes. Private keys, certificate bundles containing private material, credentials, and secret-bearing logs or artifacts are never emitted, uploaded, or retained as proof. [ADR 0152](./adr-0152-phased-environment-bound-deployment-preflight-and-expected-bindings.md) continues to govern phased environment-bound deployment proof; this controller status is not a substitute for player-facing preflight or traffic-open evidence.

### Fixed dev-demo reconciliation

The fixed `dev-demo-cluster` identity is reconciled indefinitely while the environment is active. Its runtime namespace may be recreated to preserve clean reproducibility, but the controller retains and repairs the fixed identity namespace and verifies identity/consumer convergence. Dev-demo remains non-player-facing, non-promotable, and non-attestable; reconciliation does not turn its identity or runtime evidence into stage or production evidence.

## Consequences

- Preview and dev-demo runtime resets no longer require avoidable certificate or internal gRPC identity churn.
- Trusted default-branch lifecycle credentials remain sensitive retained-identity authority and require narrow RBAC, audit, concurrency, and retirement handling.
- Public ingress and Telnet compromise or renewal can be isolated from one another, while internal gRPC identity remains stable across runtime replacement.
- Cleanup and reconciliation handle provider/API uncertainty conservatively, preserving identity until an explicit trusted retirement is known to have succeeded.
- Certificate lifecycle proof is stronger than rendered-resource presence: it requires observed validity, SAN, key correspondence, issuer, distinct-SPKI replacement, serialized renewal, and consumer convergence.
- Runtime state remains disposable and clean; retained identity is not a backup, a runtime data store, or a substitute for ADR 0178's two-public-path transport proof.
- Stage and production external certificate/identity lifecycle remains unchanged and unproven by this ADR.

## Relationship to ADR 0178

This ADR amends ADR 0178's namespace-deletion semantics only: the namespace removed by routine redeploy, reclaim, explicit release, or lease expiry is the disposable runtime namespace, while the separate identity namespace remains until PR/environment identity retirement. ADR 0178 remains authoritative for head-bound disposable preview behavior and transport-complete Telnet/browser proof and is not superseded.

## Alternatives Considered

### Delete identity with every runtime namespace

Rejected because routine redeploy, reclaim, and lease expiry would cause unnecessary trust churn and could invalidate unaffected consumers without an identity-retirement decision.

### Let PR workflows own retained identity

Rejected because PR-controlled code is untrusted and must not receive authority to retain, rotate, or delete cross-deployment certificate and workload identity.

### Reuse one public key for ingress and Telnet

Rejected because independent public paths need independent compromise and renewal boundaries; one key would couple their failure and replacement lifecycles.

### Treat requested certificate resources as proof

Rejected because resource intent does not prove served validity, SAN, key correspondence, issuer, distinct replacement identity, serialized renewal, or consumer convergence.

### Delete on an uncertain provider/API response

Rejected because uncertainty cannot safely distinguish runtime cleanup from identity retirement. The lifecycle retains identity and fails or requests explicit trusted reconciliation.

## Implementation and Proof Obligations

Implement the constrained in-cluster controller, one-time bootstrap/admission guard, separate runtime and identity namespace references, least-privilege identity RBAC, explicit runtime-cleanup versus identity-retirement operations, and indefinite fixed dev-demo reconciliation. Implement per-identity serialized certificate issuance/renewal and non-secret evidence for actual validity, SAN, key correspondence, issuer, monotonic generation, and distinct-SPKI replacement. Prove that redeploy, reclaim, runtime release, and lease expiry retain active identity; PR close and explicit retirement remove it after exact runtime `NotFound`; provider/API uncertainty retains identity and fails closed; ingress and Telnet keys are independent; gRPC identity survives runtime replacement; renewal synchronization is serialized; every affected consumer reloads or restarts and converges; and no secret appears in logs or artifacts. These proofs must not be substituted for ADR 0178's two-public-path transport proof.

The current implementation has local/static contract evidence only. Live hosted rollout, certificate issuance, renewal synchronization, served certificate validation, consumer reload/restart, and controller-backed gameplay proof remain open. Stage and production external certificate/identity lifecycle integration and provider-specific automation remain outside this slice.

## Reversibility and Revisit Triggers

Namespace naming, certificate-provider adapters, renewal timing, and consumer reload mechanics may change while preserving separate authority, active-identity retention, explicit retirement, independent public keys, retained gRPC identity, serialized validation, fail-closed uncertainty, and secret-free evidence. Revisit this decision if environments require a different trust domain, multiple identity authorities, a provider with incompatible lifecycle semantics, or a deliberate stage/production external-lifecycle change; each requires explicit human review.

## Required Documentation Alignment

- [ADR 0032](./adr-0032-kubernetes-native-secret-delivery-without-mandatory-vault.md)
- [ADR 0038](./adr-0038-explicit-jwt-profiles-and-mtls-workload-identity.md)
- [ADR 0152](./adr-0152-phased-environment-bound-deployment-preflight-and-expected-bindings.md)
- [ADR 0178](./adr-0178-disposable-transport-complete-pr-preview-proof.md)
- [CI/CD Pipeline](../system-architecture-cicd.md)
- [Deployment Environments](../infrastructure/deployment-environments.md)
- [Deployment Runbook](../system-architecture-deployment-runbook.md)
- [gRPC TLS Requirements](../system-architecture-grpc.md#tls-requirements)
- [Platform Operations and Delivery tracker](../../project-management/implementation-tracking/platform-operations-and-delivery.md)
