# ADR 0008: Multi-Cluster Gameplay Sharding Scope and Adoption Gate

## Status

Accepted

## Implementation Status

The current deployment model uses one gameplay cluster and in-cluster lease ownership. Explicit recovery or replacement-cluster fencing, authority cutover, and focused proof that only one cluster can hold active gameplay authority remain gaps.

## Decision Record

- Human review status: Completed
- Human review date: 2026-07-21
- Human review disposition: Accepted
- Review source: `EDGE-02`

## Context

Architecture documents currently define FireMUD’s target state around a single Kubernetes cluster per deployment, with gameplay scaling achieved through Game Session lease ownership and region rebalancing.

Previous drafts introduced cross-cluster shard handoff concepts (`shard_id` ownership, session migration across clusters) without a complete end-to-end contract for routing keys, trust boundaries, reconnect/backoff behavior, and Redis/data topology. This created contradictory guidance across system and service docs.

## Decision

FireMUD’s current target-state architecture remains:

- Single cluster per deployment for gameplay traffic.
- Exactly one gameplay cluster may hold active execution authority for a deployment. A separately provisioned recovery or replacement cluster is allowed only while fenced or quarantined from gameplay authority until an explicit cutover revokes the former cluster's authority.
- `/ws/game/**` routes to a stable Game Session surface at the edge.
- Horizontal scale is achieved by lease-based executor rebalancing inside Game Session.
- Close-and-reconnect remains the client-facing behavior model for backend movement and outages.

Multi-cluster gameplay sharding is explicitly out of scope until a dedicated design package is accepted.

A future multi-cluster design is allowed only when all of the following are specified together in one change set:

- Routing-key transport and admission contract.
- Gateway and TCP Proxy trust model updates.
- Client-visible close/reconnect behavior updates.
- Redis topology and ownership updates.
- Data durability and cross-cluster consistency boundaries.
- Operational failover and rollback procedures.

## Consequences

- Service documents must not define ad hoc cross-cluster session handoff behavior as current target-state behavior.
- Region ownership language in runtime docs must refer to lease-based in-cluster rebalancing unless and until a dedicated multi-cluster ADR is accepted.
- If scale/SLA requirements exceed the single-cluster model, the response is a design proposal, not incremental per-service drift.
- Disaster-recovery preparation may maintain replacement infrastructure, but it must not create simultaneous active gameplay authority under this decision.

## References

- `design/architecture/decisions/adr-0007-edge-sharding-and-close-taxonomy.md`
- `design/architecture/system-architecture-overview.md`
- `design/architecture/system-architecture-gateway.md`
- `design/architecture/system-architecture-protocol-bridging.md`
- `design/architecture/system-architecture-reconnection.md`
- `design/architecture/microservices/game-session-service/README.md`
