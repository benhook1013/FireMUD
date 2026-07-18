# ADR 0018: Declarative Production Gateway Routes

## Status

Accepted

## Decision Record

- Decision date: 2026-07-18
- Primary capability: `PO-2.1` Public and internal edge routing
- Affected capabilities: `PO-1.1`, `PO-1.4`, `PO-3.1`, `PO-3.2`, `AA-3.3`, `SF-2.1`, `SF-2.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led coupled review of `EDGE-06` and `MS-GW-DYNAMIC-ROUTES`

## Context

Spring Cloud Gateway currently exposes REST and gRPC operations that write process-local route overrides and refresh the active pod. These operations are useful for disposable integration tests and fault injection, but they do not converge across the two-pod Gateway deployment, survive restart, or provide the complete validation and audit required for a player-facing edge control plane.

The previous target scoped mutation to dev/test until persistence, multi-pod convergence, audit, and readiness predicates existed. Those gates are necessary but insufficient for arbitrary production route mutation: they do not by themselves prevent unsafe destinations, route-family capture, security-filter changes, baseline conflicts, stale emergency overrides, or ambiguous rollout and restore behavior. FireMUD does not currently have a demonstrated product or operational requirement for a second production configuration authority.

## Decision

Player-facing and production-like Gateway routing has one authority: a version-controlled declarative route catalog released with environment-bound service endpoints through the normal deployment workflow. Runtime route mutation is not part of the initial production target and cannot become supported merely by adding persistence, convergence, audit, and readiness flags.

### Player-Facing Routing

- Production route changes update the canonical release catalog and converge through the normal reviewed rollout and rollback mechanism.
- Emergency rerouting uses an expedited declarative rollout or a predeclared, bounded failover switch between approved targets. It does not use a generic route editor.
- Runtime mutation components and endpoints are absent or disabled by default. Player-facing startup fails if an ephemeral mutation profile or endpoint is enabled.
- The implementation must converge the current Java-versus-`routes.yml` discrepancy onto one version-controlled baseline representation. File format is subordinate to the single immutable release authority.

### Dev/Test Overrides

Ephemeral mutation may exist only in explicitly classified local, development, or test environments:

- It overlays the baseline on one disposable runtime and visibly reports that it is non-durable and non-convergent.
- Restart restores the baseline.
- Protected baseline, management, authentication, gameplay, and header-trust routes cannot be replaced or shadowed.
- Route identifiers, destinations, predicates, and filters are allowlisted and reject unsafe internal/external targets and security-sensitive behavior.
- Mutation endpoints are not reachable through player-facing ingress and require explicit trusted test/operator authorization.
- Audit records include actor, authorization basis, before and after values, outcome, and correlation identity.

### Future Production Control Plane

A production runtime-routing control plane requires a separate future decision triggered by a demonstrated need for changes faster than declarative rollout can safely provide. Its design must address versioned desired state, single ownership, compare-and-set updates, validation, multi-pod reconciliation, staged activation, expiry, rollback, conflict handling, authorization, complete audit, recovery, and fail-closed behavior. Dev/test APIs are not promoted in place by satisfying a short readiness checklist.

## Consequences

- Production routing is deterministic across pods, restarts, rolling deployments, rollback, and recovery.
- FireMUD avoids a second distributed configuration authority and its security and operational burden.
- Dev/test retains rapid mock routing and fault injection without implying production support.
- Emergency changes may take the duration of an expedited configuration rollout rather than seconds.
- Arbitrary live production canaries and improvised incident targets are unavailable unless predeclared.
- Endpoint/profile isolation, safe dev/test validation, and baseline-authority convergence still require implementation and proof.

## Alternatives Considered

### Keep Production Mutation Deferred Behind Existing Gates

Persistence, convergence, audit, and readiness improve the current API but do not bound what routes, targets, predicates, and filters may be changed or define staged activation, expiry, and recovery. This could accidentally promote a generic developer route editor into a production control plane.

### Build a Durable Production Route Control Plane Now

This enables rapid failover and canaries but requires substantial desired-state, reconciliation, security, rollout, recovery, and proof machinery. No current requirement justifies that cost.

### Remove Runtime Mutation Everywhere

This is the smallest design, but it discards useful local integration-test and fault-injection workflows. Explicitly isolated dev/test overrides preserve that value safely.

## Implementation and Proof Obligations

- Make mutation bean and endpoint registration conditional on an explicit dev/test classification and fail player-facing startup if present.
- Prove mutation endpoints and the gRPC management mutation methods are unreachable from public ingress and player networks.
- Enforce protected-route and route-component allowlists before applying any dev/test override.
- Emit complete bounded audit records and prove restart restoration to the canonical baseline.
- Prove production deployments expose one identical route catalog across every pod and converge route changes through rollout and rollback.
- Remove or explain unused persistence artifacts and reconcile documentation with the one live baseline representation.

## Reversibility and Revisit Triggers

The decision is reversible through a new ADR without changing the declarative baseline contract. Revisit only when measured incident or rollout requirements show that an expedited deployment or predeclared failover switch is too slow, and when an owner and operational budget exist for a durable bounded control plane.

## Required Documentation Alignment

- `design/architecture/system-architecture-overview.md`
- `design/architecture/system-architecture-gateway.md`
- `design/architecture/microservices/spring-cloud-gateway/api-contracts.md`
- `design/architecture/microservices/spring-cloud-gateway/configuration.md`
- `design/architecture/microservices/spring-cloud-gateway/operations.md`
