# ADR 0007: Edge Sharding Scope and Close Taxonomy (No Distinct Shard Handoff Signal)

## Status

Accepted

## Context

Several documents and proposals assumed two target-state capabilities at the edge:

- A Gateway-owned gameplay shard routing plane that consumes lease/mapping state and deterministically routes `/ws/game/**` connections to a specific Game Session shard.
- A distinct client-visible close category (for example `1013/reroute`) to represent “normal shard handoff” separately from “backend outage”.

These assumptions introduce multiple gaps and contradictions:

- No end-to-end admission contract exists for how a new WebSocket connection supplies a routing key before `LOGIN`/lobby selection (`PLAY`) establishes session context.
- The close taxonomy implies clients can reliably distinguish lease moves from outages, but the platform does not yet have a concrete, secure, and observable shard handoff mechanism that works uniformly across WebSocket and Telnet paths.
- Treating shard handoff as an edge-visible first-class event makes operational and client behavior depend on an under-specified routing plane, increasing the risk of split-brain routing and mismatched semantics across docs and implementations.

## Decision

FireMUD’s target-state edge contract is:

- **No Gateway-owned gameplay shard routing plane.** Spring Cloud Gateway remains a protocol edge. `/ws/game/**` routes to a stable Game Session service endpoint; any sharding/lease ownership mechanics are internal to the Game Session layer and its coordination mechanisms.
- **No distinct “shard handoff” close category.** The standardized client-visible close taxonomy does not include a dedicated handoff signal (for example `1013/reroute`). Client guidance and runbooks must not rely on a special fast-retry handoff outcome.
- **Close outcomes remain limited and uniform.** Edge-visible outcomes are expressed using the small, unified categories already defined in the Gateway and Protocol Bridging contracts (for example `logout`, `idle_timeout`, `policy_violation`, `internal_error`, `backend_unavailable`), with `backend_unavailable` covering sustained backend failures and any edge-to-backend connectivity loss that prevents gameplay.

If a future architecture introduces explicit lease-aware routing or client-visible handoff semantics, it must be defined as a dedicated design update (including the routing-key transport, security/trust model, and reconnection/backoff policy) and then integrated into:

- `design/architecture/system-architecture-gateway.md`
- `design/architecture/system-architecture-protocol-bridging.md`
- `design/architecture/system-architecture-reconnection.md`

## Consequences

- Documents must not standardize or reference `1013/reroute` or a Telnet `reroute` reason token as part of the canonical close taxonomy.
- Proposals that depended on a two-phase admission or client-carried routing keys (for example ADR 0006) are withdrawn until a dedicated sharding/routing architecture update is explicitly adopted.
- Operator runbooks should interpret elevated `backend_unavailable` volume as “gameplay path unavailable” without attempting to classify it as handoff vs outage unless/ until a dedicated handoff contract exists.

## References

- `design/architecture/system-architecture-gateway.md`
- `design/architecture/system-architecture-protocol-bridging.md`
- `design/architecture/system-architecture-reconnection.md`
- `design/architecture/system-architecture-overview.md`
