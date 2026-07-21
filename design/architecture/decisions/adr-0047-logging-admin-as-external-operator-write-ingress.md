# ADR 0047: Logging and Admin as External Operator-Write Ingress

## Status

Accepted

## Decision Record

- Decision date: 2026-07-19
- Decision key: `SEC-04`
- Primary capability: `PO-1.1` Administration and operator control
- Affected capabilities: `PO-1.2`, `PO-1.3`, `PO-1.4`, `PO-2.1`, `GR-1.1`, `AR-3.3`, `AR-2.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `SEC-04`

## Context

Operator actions need one predictable external security and audit boundary without moving authoritative domain state into an administration service. Allowing every domain service to expose independent operator-write APIs would multiply authorization, audit, and failure contracts. Making dashboards or observability stores part of write success would make remediation unavailable during the incidents when operators need it most.

The added ingress hop is acceptable for operator work, but must not become a dependency of ordinary gameplay or a reason for Logging and Admin to own another service's state.

## Decision

### Canonical External Ingress

External operator writes for moderation, runtime feature flags, quota overrides, admission control, and tick or coordination remediation enter through HTTPS at Spring Cloud Gateway and then Logging and Admin.

For each request, Logging and Admin:

- authenticates the operator and checks the required tenant, global, or cross-tenant scope;
- validates the operator-facing request;
- records durable operator intent and audit identity;
- forwards a typed, scope-complete request to the owning domain service; and
- correlates the owner response with the audit record and returns an explicit outcome.

The domain owner alone validates domain facts and commits authoritative state. Logging and Admin may persist operator intent, audit, and workflow status, but it does not persist a competing copy of feature-flag, quota, admission, moderation-enforcement, tick, or coordination truth. It never mutates another service's database or Redis keys directly.

### Bounded Owner Delegation

Account Service is the authority for operator delegation and supports two explicit issuance paths. Human issuance validates the current `control-ui` token `jti`, account generation, current tenant or global role, tenant/scope, action family, and canonical mutation digest. A `platformAdmin` action and a cross-tenant `billingAdmin` action additionally require the bounded `privileged_control` window from ADR 0045; tenant-scoped `tenantAdmin` and `moderator` actions do not pretend to hold a global-role elevation. Account then issues an opaque, bounded authorization reference bound to the actor account, current `control-ui` `jti`, account generation, role, tenant/scope, action family, `controlPlaneRequestId`, mutation digest, issue time, and expiry. For a global `platformAdmin` tenant operation, the reference also binds the current target-tenant generation without inventing tenant membership.

Unattended operator automation uses a separate workload issuance path. Account authenticates the exact allowlisted mTLS workload and validates a durable, versioned automation policy that authorizes that workload for the requested tenant/scope, action family, and canonical mutation digest. Its reference is bound to workload identity, automation-policy identity and version, tenant/scope, action family, `controlPlaneRequestId`, mutation digest, issue time, and expiry; it has no invented user account, `control-ui` token, or privileged human window. Logging and Admin records whether the actor is human or workload automation and retains the applicable human or policy evidence.

Logging and Admin computes `mutationDigest` as SHA-256 over a versioned canonical encoding of every scope, target, expected-version, mutation, and audit-reason field, excluding only transport credentials and the authorization reference. It forwards the reference unchanged with the typed request, digest, and same `controlPlaneRequestId`. The owner recomputes the digest, redeems and validates the exact reference with Account, then independently checks current domain facts, ownership, fencing, and idempotency. Logging and Admin's asserted actor, role, tenant, or scope is request context and audit input, not authority. Owner-side admission RPCs are classified `internal_workload`, require the exact Logging and Admin mTLS identity plus Account redemption, and accept no end-user JWT.

This protocol applies to the current feature-flag, admission-pointer/version-upgrade, and tick pause/resume families. The current moderation endpoint records policy input, but no owner-side enforcement RPC is exposed. Quota override has no current OpenAPI or owner route; it remains coverage drift rather than an invented endpoint.

### Direct Domain Surfaces

Domain write APIs used by this workflow are internal service-to-service surfaces by default. An external domain write is allowed only when the owning contract explicitly designates it as bypass-safe and documents its exact route, domain-local authority, validation, audit behavior, and reason that no Logging and Admin policy or cross-domain orchestration is required.

Safe reads may route directly to the owning domain through Gateway when their authorization, tenant isolation, and redaction contract is explicit. Edge routability alone does not make a write bypass-safe.

### Availability and Runtime Boundary

Core operator writes must remain independent of Elasticsearch, Prometheus, Jaeger, Grafana, Kibana, Alertmanager, and other observability systems. Those systems may supply investigation context, but their outage cannot determine whether an operator mutation succeeds. Write success depends only on the durable audit/intent path and the owning domain contract.

The additional Logging and Admin hop is accepted for human and automated operator workflows. It is not introduced into ordinary gameplay command processing, domain-to-domain gameplay calls, or owner-local enforcement. Logging and Admin remains an ingress, audit, and coordination boundary rather than a gameplay dependency or general domain owner.

## Consequences

- External operator writes have one reviewed authentication, scope, validation, and audit entry point.
- Domain ownership remains explicit: authoritative mutations and domain preconditions stay in the owning service.
- Operators cannot bypass audit by calling an owner-side write route directly unless that exact workflow is documented as bypass-safe.
- Safe read paths need not pay the extra coordination hop.
- Operator writes remain usable during observability outages.
- The extra network hop adds latency and another failure point, which is accepted because these are control-plane rather than gameplay-hot-path operations.
- Logging and Admin and each owner need correlated request identity, retry-safe outcomes, and clear failure reporting so an uncertain response does not invite an unsafe duplicate mutation.

## Alternatives Considered

### Expose Independent Domain Admin Writes

Rejected as the general rule because authorization, audit, operator UX, and outage behavior would diverge across services. Narrow domain-local writes remain possible only through the explicit bypass-safe exception.

### Create a Separate Operator Write Plane

Rejected for the current scope because it adds another deployable, security boundary, and workflow authority without removing the need for owner-side validation and mutation.

### Let Logging and Admin Own Operator-Mutable Domain State

Rejected because it creates competing authorities and couples gameplay and runtime correctness to an administration service.

### Use Dashboards or Observability Stores as the Write Backend

Rejected because observability systems are not authoritative domain stores and may be degraded during incident response.

## Implementation and Proof Obligations

The current implementation is partial. Existing ingress and forwarding paths do not by themselves prove this boundary for every action family.

Implementation and focused proof must:

- classify the exact Gateway and Logging and Admin routes and reject unauthorized, wrong-tenant, wrong-scope, and unclassified requests;
- prove external clients cannot reach the corresponding owner-side mutation APIs directly;
- require exact `control-ui`, current role, and role-appropriate assurance for human ingress, while proving the separate unattended path requires exact mTLS workload identity plus current versioned automation policy and cannot impersonate a user;
- prove Account issues and owns the bounded operator authorization reference and that the owner redeems it rather than trusting Logging and Admin assertions;
- prove Logging and Admin records actor, scope, action, reason, request identity, and final outcome without trusting caller-supplied actor identity;
- prove the owner performs the authoritative validation and durable mutation without Logging and Admin writing owner state;
- prove retries, duplicate delivery, owner timeout, audit failure, and uncertain completion converge through correlated idempotent outcomes rather than duplicate mutation;
- prove core operator writes continue when each observability dependency is unavailable;
- maintain an explicit inventory and focused audit proof for every bypass-safe external write; and
- prove ordinary gameplay and owner-local enforcement do not call Logging and Admin merely to process a command or commit domain state.

Quota, admission, moderation, feature-flag, and remediation families may converge incrementally, but none is complete until its external route, audit, typed forwarding, Account reference redemption, owner mutation, negative authorization, retry, and outage behavior are all demonstrated. A family with no current route or owner contract is recorded as coverage drift and is not represented by a placeholder endpoint.

## Reversibility and Revisit Triggers

The routing rule is reversible one workflow class at a time, but changing it requires replacing the centralized audit and scope guarantees at every newly exposed domain boundary. Revisit this decision if operator-write volume makes the additional hop material, a domain demonstrates a broad class of genuinely domain-local bypass-safe writes, Logging and Admin availability cannot meet incident-response needs, a durable operator command plane becomes justified, or a new workflow requires coordinated mutation across multiple domain owners with stronger completion semantics than correlated audit and owner idempotency provide.
