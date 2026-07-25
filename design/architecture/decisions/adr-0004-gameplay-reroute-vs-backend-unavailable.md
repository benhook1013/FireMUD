# ADR 0004: Gameplay `reroute` vs `backend_unavailable` Close Taxonomy

## Status

Superseded by [ADR 0007](./adr-0007-edge-sharding-and-close-taxonomy.md)

This ADR is retained for historical context only. Its proposed `1013/reroute` taxonomy and Gateway-owned sharding assumptions are not part of the current target-state edge contract. See [ADR 0007](./adr-0007-edge-sharding-and-close-taxonomy.md).

## Historical Context

Multiple historical architecture documents used inconsistent client-visible outcomes for lease moves and shard handoff events:

- Some sections treated lease moves as `1013/backend_unavailable`, which conflates normal shard handoff with real outages and pushes clients into long exponential backoff.
- Other sections implied a distinct “reroute” category but did not standardize the close reason or how Telnet disconnects map to it.

The withdrawn proposal sought a deterministic, operator-visible way to distinguish “move to a new shard owner” from “core gameplay backend is down.”

## Historical Proposal (Superseded)

The superseded proposal would have standardized two distinct `1013` categories for gameplay connections:

- `1013/reroute`
  - Used when the gameplay shard mapping for a session’s `<tenantId, gameInstanceId, regionId>` has moved (lease transfer, planned drain, shard handoff).
  - Intended semantics: reconnect promptly so the next connection is admitted to the new shard owner.
  - Client guidance: use only a small randomized delay (for example 0–250ms) to avoid stampedes; do not apply long exponential backoff solely due to reroute.
  - Telnet mapping: TCP Proxy emits a Telnet disconnect reason token `reroute`.

- `1013/backend_unavailable`
  - Used only for sustained gameplay-backend outages or overload conditions beyond the configured grace window.
  - Intended semantics: core gameplay admission is currently unhealthy; clients should reconnect with exponential backoff.
  - Telnet mapping: TCP Proxy emits a Telnet disconnect reason token `backend_unavailable`.

Under that proposal, Spring Cloud Gateway would have emitted the client-visible WebSocket close frame and metric-tagged the category, while Game Session would have signaled reroute intent via upstream behavior. [ADR 0007](./adr-0007-edge-sharding-and-close-taxonomy.md) withdraws this Gateway-owned routing and close-taxonomy model.

## Historical Consequences

- The proposal would have required metrics, dashboards, and runbooks to treat `reroute` volume as a normal scaling/handoff signal and `backend_unavailable` as an outage signal.
- The current contract is instead defined by [ADR 0007](./adr-0007-edge-sharding-and-close-taxonomy.md): it does not include a distinct `1013/reroute` category or Telnet `reroute` reason token.

## References

- `design/architecture/system-architecture-gateway.md`
- `design/architecture/system-architecture-reconnection.md`
- `design/architecture/system-architecture-protocol-bridging.md`
- `design/architecture/system-architecture-overview.md`
- `design/architecture/system-architecture-telnet-degraded-runbook.md`
- [ADR 0007](./adr-0007-edge-sharding-and-close-taxonomy.md)
