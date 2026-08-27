# ADR 0171: Separated Redis Role Processes and Owned Keyspaces

## Status

Accepted

Extends [ADR 0009](./adr-0009-coordination-redis-ownership-boundary.md).

## Implementation Status

This decision is partially implemented. Separate Redis role topology exists in current manifests, but role-specific application clients, ACLs, key and script registration, and ownership proof remain gaps. The authoritative implementation and proof status for `REDIS-02` is [`SF-2.2` in the Shared Runtime, Service Contracts, and Persistence tracker](../../project-management/implementation-tracking/shared-runtime-contracts-and-persistence.md#capability-status).

## Decision Record

- Human review status: Completed
- Human review date: 2026-07-21
- Human review disposition: Accepted
- Review source: `REDIS-02`
- Decision date: 2026-07-21
- Decision key: `REDIS-02`
- Primary capability: `SF-2.2` Redis coordination and caching
- Affected capabilities: `GR-1.3`, `AA-2.2`, `AS-1.5`, `PO-3.2`, `PO-4.4`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of keyspace ownership, physical isolation, hobby deployment cost, eviction and latency coupling, topology migration, and implementation reality

## Context

ADR 0009 assigns coordination keyspaces to owning services but does not decide physical Redis topology. Later canonical design requires separate Coordination and Cache/Rate-Limit Redis deployments in persistent player-facing environments. The distinction matters because persistence, eviction, memory pressure, event-loop load, restart, and failover are process-wide; prefixes, logical databases, and ACLs do not isolate those resources.

## Decision

ADR 0009's service-owned keyspace and owner-managed bridge rules remain accepted. Region/session keys use the canonical hash-tag discipline, and a Lua script or transaction must not cross ownership, Redis-role, or cluster-slot boundaries.

Every persistent player-facing environment, including hobby/self-hosted, uses distinct Coordination and Cache/Rate-Limit Redis processes and endpoints. They may be colocated as two containers or processes on the same host or cluster node; this decision does not require separate hardware, Kubernetes clusters, or managed-service accounts.

Services select explicit role-specific clients, configuration, credentials, metrics, and health checks. A service that participates in both roles receives two named clients rather than one generic ambient Redis connection. Endpoint-collision validation fails closed outside the explicitly ephemeral test/CI exception.

Only explicitly labelled one-shot test/CI environments may collapse both roles into one Redis process. Such environments forfeit coordination-isolation and SLO evidence and cannot serve as production-like proof.

## Consequences

- Cache eviction, rate-limit bursts, and cache persistence choices cannot directly evict or stall correctness-sensitive tick/session coordination through a shared Redis process.
- A small self-hosted installation operates a second Redis process, though it may remain on the same machine and requires no second infrastructure provider.
- Supporting a separate cache-free compact runtime profile is avoided for now, so cache, limiter, and automation paths retain one canonical production behavior.
- Current manifests provision much of the intended topology, but generic application client wiring frequently defeats it. Role-specific client adoption, ACLs, key builders, Lua registration, and ownership enforcement remain implementation work.

## Alternatives Considered

### One Redis Process for Both Roles

Role-specific clients, prefixes, ACLs, and quotas preserve logical ownership but cannot isolate process-global eviction, persistence, memory, restart, or event-loop behavior. It is rejected for persistent player-facing deployments.

### Compact Hobby Profile Without Cache Redis

A single-replica hobby profile could use Coordination Redis only, process-local caches and rate limits, and PostgreSQL/outbox polling. This avoids unsafe workload mixing and is easy to migrate because cache state is disposable. It is not adopted because it creates a second runtime mode and proof matrix across caching, limits, and automation to save one colocated process.

## Implementation and Proof Obligations

Implementation must replace the generic Redis client with explicit role clients, prove every prefix reaches the declared role, prevent non-owner direct writes, register shared key/script descriptors, and enforce role-specific ACLs. Proof must detect endpoint collision, wrong-role injection, eviction pressure, cache reset, coordination tail loss, cross-slot scripts, and unauthorized prefix mutation. Existing direct Entity writes to tick locks and Automation prefix drift must be reconciled through owning contracts.

## Reversibility and Revisit Triggers

Adding or replacing Cache Redis requires no durable data migration. Revisit a cache-free compact profile only after measuring the second process against a declared minimum hobby hardware target and demonstrating that the extra supported mode costs less than it saves.
