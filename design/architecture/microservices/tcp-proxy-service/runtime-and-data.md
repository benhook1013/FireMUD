# TCP Proxy Service Runtime and Data

## Implementation Status

Immediate closure on established bridge loss and the current close mapping and shutdown classification are implemented as described in [Operations](./operations.md#implementation-status). Preserving every valid authenticated Gateway token, including standalone `session_replaced` and `service_restart`, remains target behavior; see [Bridge Lifecycle Ownership](#bridge-lifecycle-ownership) for the local runtime invariants.

## Redis Role and Prefixes

- The TCP Proxy Service does **not** access Coordination Redis and never depends on Redis for correctness or session recovery.
- The proxy currently uses **no Redis keys**. All gameplay and session state live in the Game Session Service and Redis as described in the [Reconnection Strategy](../../system-architecture-reconnection.md) and [Redis Architecture](../../system-architecture-redis.md); proxy buffers are purely in-memory and connection-local.
- Future enhancements may use Cache/Rate-Limit Redis only for optional, non-authoritative caches or throttling decisions. When introduced, such keys must:
  - use the Cache/Rate-Limit Redis deployment, never Coordination Redis;
  - follow a dedicated prefix family such as `tcpproxy:rate:*` or `tcpproxy:cache:*` with TTLs and reset semantics declared in the Redis cache design; and
  - degrade safely when Redis is unavailable so cache failures are treated as cache misses rather than availability blockers.
- These caches must not change the proxy’s fundamental design as a stateless edge: any derived entries may be dropped, cold, or unavailable at any time without affecting correctness.

## Reconnection Behaviour at the Proxy Layer

The TCP Proxy Service treats each Telnet TCP connection as independent and keeps reconnection logic centralized in the Game Session Service:

- Multiple Telnet connections using the same `{gameInstanceId, tenantId}` are allowed. The proxy simply forwards commands for each connection; Game Session enforces the “one session per character” behavior by applying takeover rules when a second client logs in as the same character.
- The proxy does not emit a positive reconnect event. It only calls `NotifyDisconnect` when a Telnet socket closes, using a server-generated `proxyConnectionId` and a per-connection `disconnectSequence` counter for idempotency; Game Session interprets a subsequent `LOGIN` + `PLAY` flow as either a fresh login or a resume or takeover based on Redis session state.
- After `NotifyDisconnect`, Game Session may resume the binding only within the effective `firemud.reconnection.policy.resume-window-ms` and while the derived absolute `session_expiration_ms` lifetime remains valid. The proxy does not evaluate either policy; see the [Reconnection Strategy](../../system-architecture-reconnection.md) and [Environment & Secrets catalog](../../infrastructure/environment-and-secrets-catalog.md#authentication--jwt).

## Data Model

The proxy is stateless in the sense that it does not own any authoritative gameplay or session state. Any buffered input lives only in memory until forwarded to Spring Cloud Gateway while the Telnet connection is still active, and incoming bytes are queued and forwarded in order while the bridge remains healthy.

Optional Redis-backed caches, if ever introduced, are treated as derived non-authoritative state: they may be empty or unavailable without affecting correctness, and all session recovery behavior remains governed by the Game Session Service and the Redis/session contracts documented in the cross-service architecture docs.

## Trust Surfaces Summary

The TCP Proxy Service participates in three distinct trust boundaries:

- Telnet plaintext or Telnet-over-TLS: client <-> TCP Proxy Service
- WebSocket mTLS bridge: TCP Proxy Service <-> Spring Cloud Gateway
- Internal gRPC mTLS: internal clients <-> TCP Proxy Service

These trust surfaces are related but not interchangeable. In very small local or hobby deployments, certificate reuse across surfaces may be acceptable, but in shared and player-facing environments operators should provision separate identities per surface so a compromise in one boundary does not automatically extend to the others.

## Bridge Lifecycle Ownership

[Gateway Architecture](../../system-architecture-gateway.md#canonical-close-translation-matrix) owns the top-level close taxonomy, [Protocol Bridging](../../system-architecture-protocol-bridging.md#telnet-disconnect-reasons) owns WebSocket-to-Telnet translation, and local [`protocols.md`](./protocols.md#bridge-state-machine-established-telnet-sessions) records this service's bridge state-machine consequences. The minimum runtime invariants are:

- the proxy must establish the Gateway bridge before forwarding the first gameplay or MCP line;
- pre-admission bridge failures distinguish `backend_unavailable` from `policy_violation`;
- established-session bridge loss closes the Telnet socket immediately, with no hidden bridge reattach;
- every valid authenticated Gateway top-level close (`logout`, `session_replaced`, `service_restart`, `idle_timeout`, `policy_violation`, `internal_error`, or `backend_unavailable`) preserves its corresponding Telnet token; absent or invalid top-level attribution alone falls back to `backend_unavailable`; and
- edge backpressure and unattributed bridge loss follow the bounded disconnect taxonomy defined by [Protocol Bridging](../../system-architecture-protocol-bridging.md#telnet-disconnect-reasons).

See:

- [Gateway Architecture](../../system-architecture-gateway.md)
- [Deployment Environments](../../infrastructure/deployment-environments.md)
- [Protocol Bridging](../../system-architecture-protocol-bridging.md)
