# ADR 0011: Gameplay Session Front-End and Region Execution Routing

## Status

Accepted

## Context

FireMUD already makes two high-level decisions:

- Spring Cloud Gateway does not own a gameplay shard-routing plane.
- Gameplay execution is partitioned internally inside the Game Session layer by `<tenantId, gameInstanceId, regionId>` leases.

Those decisions leave an important internal question open: when a player is connected to a stable `/ws/game/**` session surface but gameplay work for that player's current region is owned by a different Game Session pod, which component owns the socket and which component owns execution?

Without an explicit answer, docs and implementations risk drifting toward incompatible models:

- socket affinity and region affinity being treated as the same thing,
- silent cross-pod forwarding without fencing rules,
- or forced reconnects on ordinary lease rebalancing.

## Decision

FireMUD adopts a **session front-end + lease-owner execution** model inside the Game Session layer.

- A connected gameplay socket is attached to a **session front-end** Game Session pod.
- Region-scoped gameplay execution remains owned by the pod that currently holds the relevant `<tenantId, gameInstanceId, regionId>` lease.
- The session front-end pod may accept input, authenticate the session, manage connection-local state, and stream results back to the client.
- The session front-end pod must not mutate tick-owned gameplay coordination state for a region it does not own.
- When command execution or tick-owned mutation targets a region leased by another pod, the session front-end forwards the request over internal gRPC to the current **lease owner**.
- Only the lease owner may stage or commit Redis coordination changes for that region.
- `playableStateScope` is part of the forwarded request identity and must be preserved and validated end to end because it distinguishes the gameplay state/effect scope being acted on.
- `playableStateScope` is not an additional lease-ownership dimension. Lease ownership remains keyed by `<tenantId, gameInstanceId, regionId>`; the current owner and its fence apply to the region, while the owner validates the forwarded playable-state scope against the authoritative gameplay binding.

## Consequences

- Spring Cloud Gateway remains shard-unaware and continues to route `/ws/game/**` to a stable Game Session service surface.
- Normal in-cluster lease rebalancing does not require client-visible reconnects solely because region ownership moved.
- Socket ownership and region execution ownership are decoupled; runbooks and observability must treat them as separate roles.
- Internal forwarding paths must carry enough fenced identity to prevent stale front-ends from mutating a region after lease loss.
- If internal forwarding becomes unavailable or inconsistent, Game Session may fail the affected command or close the gameplay session using the existing reconnect model; it must not invent a separate edge-visible shard-handoff taxonomy without a new design update.

## Required Internal Forwarding Contract

Any session front-end to lease-owner forwarding path must implement one canonical contract:

- Every forwarded request carries `tenantId`, `gameInstanceId`, `playableStateScope`, `sessionId`, `characterId`, target `regionId`, command or action identifier, and a monotonic per-session sequencing token so the lease owner can preserve session-local ordering.
- Every forwarded request carries the lease/epoch fence for the target region. A lease owner must reject requests with a stale or missing fence using an application-level stale-lease error.
- The session front-end may retry only idempotent forwarded operations and must not create duplicate tick-owned mutations when a response is ambiguous.
- If lease ownership changes before execution starts, the front-end must refresh ownership and retry against the new lease owner. If ownership changes after execution has begun, the in-flight attempt is owned by the executor that accepted the fenced request and later attempts must use the new fence.
- If forwarding times out or the target lease owner is unavailable, Game Session must either fail the affected command with the documented structured error path or terminate the gameplay session using the existing reconnection contract. It must not invent a separate client-visible shard-handoff behavior.

Service-level docs may add implementation detail, but they must not weaken these fencing, ordering, or failure rules.

## Non-Goals

- This ADR does not introduce a Gateway-owned gameplay shard-routing plane.
- This ADR does not define multi-cluster gameplay execution.
- This ADR does not introduce a client-visible shard-handoff close category.

## Required Documentation Alignment

The following docs must remain aligned with this decision:

- `design/architecture/system-architecture-overview.md`
- `design/architecture/system-architecture-reconnection.md`
- `design/architecture/system-architecture-diagram.md`
- `design/architecture/microservices/game-session-service/README.md`
- `design/architecture/microservices/spring-cloud-gateway/README.md`
