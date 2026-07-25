# ADR 0006: Gameplay Shard Routing Key Transport

## Status

Withdrawn (superseded by ADR 0007)

This ADR is retained for historical context, but it is not part of the current target-state edge contract. See `design/architecture/decisions/adr-0007-edge-sharding-and-close-taxonomy.md`.

## Historical Context

The withdrawn proposal assumed that Gateway would route `/ws/game/**`
WebSocket connections to the Game Session shard that owns the
`<tenantId, gameInstanceId, regionId>` lease. It also relied on the withdrawn
`1013/reroute` close category. Multiple historical documents described:

- Lease ownership and mapping stored in Coordination Redis keyed by `<tenantId, gameInstanceId, regionId>`,
- Gateway consuming that mapping for routing,
- `1013/reroute` on lease moves.

However, the documentation does not specify a concrete, end-to-end mechanism for how Gateway determines the correct `<tenantId, gameInstanceId, regionId>` for a new WebSocket connection at admission time, especially for initial connections before `LOGIN`/lobby selection (`PLAY`) has bound a session.

Without an explicit routing-key transport, shard routing cannot be implemented consistently and the system risks either misrouting gameplay connections or reintroducing hidden affinity that conflicts with lease ownership.

## Historical Proposal (Withdrawn)

The proposal would have adopted an explicit two-phase admission model:

1. **Admission connection (unsharded):** new `/ws/game/**` connections are admitted to a stable “gameplay admission” surface that can process `LOGIN` + lobby selection (`PLAY`) without requiring pre-known region routing.
2. **Region-bound connection (sharded):** once lobby selection binds a character and the platform can deterministically resolve the character’s current `<tenantId, gameInstanceId, regionId>`, the system provides the client a routing key and requests reconnect so the next connection is routed directly to the lease-owning shard.

The open decision in that proposal was the exact **routing key transport** used
for phase (2). Candidate options were:

- **A. WebSocket URL path parameter** – the client reconnects to `/ws/game/<routingKey>` where `routingKey` is an opaque, signed token. Gateway validates the token and derives `<tenantId, gameInstanceId, regionId>` for routing. This keeps gameplay clients token-free in the auth sense while making routing explicit.
- **B. WebSocket subprotocol** – the client reconnects using a `Sec-WebSocket-Protocol: firemud,<routingKey>` negotiation. Gateway extracts the routing key and routes accordingly.
- **C. Gateway-managed session cookie** – Gateway sets a cookie during admission and uses it on reconnect to determine routing. This is attractive for browsers but complicates non-browser clients and is brittle for WebSocket tooling.

Option A was recommended because it was transport-agnostic, did not require
custom subprotocol handling in every client, and kept routing inputs explicit
and observable.

## Historical Consequences

- The proposal would have required docs to specify how a routing key was carried
  by clients or derived by Gateway.
- It would have required client reconnection guidance to treat `1013/reroute`
  as a normal, fast-retriable outcome during phase transitions and lease moves.
- If Option A or B had been adopted, the gameplay protocol would have needed to
  define the world-selection response shape containing the routing key, with
  Gateway treating that key as routing material only.

ADR 0007 withdraws this two-phase admission, client-carried routing-key, and
distinct reroute-signal proposal. It is not current target-state behavior.

## References

- `design/architecture/system-architecture-overview.md` (Gameplay Shard Routing Contract)
- `design/architecture/system-architecture-gateway.md` (Gameplay Sharding boundary + close taxonomy)
- `design/architecture/system-architecture-authentication.md` (Tenant selection / enter-game)
- `design/architecture/system-architecture-reconnection.md`
