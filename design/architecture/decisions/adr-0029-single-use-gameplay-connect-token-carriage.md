# ADR 0029: Single-Use Gameplay Connect-Token Carriage

## Status

Accepted

## Implementation Status

Gateway currently accepts one header or cookie, rejects simultaneous carriers, parses routing claims, performs Redis `SET IF ABSENT`, and has focused denial tests. Hard maximum lifetime and signed-`iat` enforcement, duplicate-value rejection, complete carrier stripping, exact consume ordering, reset/failover quarantine, dedicated connect-context key rotation, and end-to-end proof of the authenticated TCP Proxy exception remain incomplete.

## Decision Record

- Decision date: 2026-07-19
- Primary capability: `PO-2.1` Gateway routing and edge controls
- Affected capabilities: `SF-1.3`, `AA-2.1`, `PO-2.2`, `PO-2.4`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `EDGE-04`

## Context

The browser authentication sequence established by ADR 0021 uses a short-lived connect token to admit a gameplay WebSocket before Game Session performs bare `LOGIN` and `PLAY`. Browser WebSocket APIs cannot attach an arbitrary authentication header, while non-browser clients can. The edge therefore needs a narrowly defined carrier contract that does not expose bearer values in URLs, silently choose among conflicting credentials, or permit the same token to be replayed against another Gateway pod.

The existing target already requires a dedicated header or HttpOnly cookie, shared replay state, verified routing scope, and a positively authenticated TCP Proxy exception. The review retains that design and makes the lifetime, ambiguity, consumption, and retry boundaries exact.

## Decision

### Supported Carriers

- First-party browsers receive the token only as the `Secure`, `HttpOnly`, `SameSite=Strict`, `/ws/game`-scoped `Firemud-Connect-Token` cookie. Browser-readable response data contains only non-secret connection metadata.
- Explicitly classified non-browser WebSocket clients use the dedicated `X-Firemud-Connect-Token` handshake header. This is the public generic-WebSocket admission contract and supersedes ADR 0021's earlier credential-bearing generic-WebSocket wording; in-band credential login remains limited to Telnet and other non-WebSocket text transports.
- A public gameplay handshake must contain exactly one non-empty, single-valued supported carrier. Duplicate header values, duplicate token cookies, or both carrier types are rejected; no precedence rule applies.
- Query parameters are not a connect-token carrier. Gateway does not promote or accept a query-carried token in player-facing environments.
- Gateway removes all external token carriers before forwarding and emits only the verified signed connect context.

For browser logout, Gateway exposes exactly `POST /ws/game/connect-token/revoke` as an HTTP revocation operation; it is not a WebSocket upgrade and is handled on the same Gateway path covered by the `/ws/game` cookie. The operation reads the HttpOnly `Firemud-Connect-Token` cookie server-side, records a bounded deny marker or equivalent edge revocation fence for its `jti` when present, clears the cookie with the same `Path=/ws/game` and security attributes, and is idempotent when the cookie is absent. It never accepts a query, request-body, or caller-readable token value. The browser `Origin` and anti-CSRF requirements in ADR 0021 apply before this mutation is performed.

### Lifetime, Validation, And Atomic Consumption

- Connect-token lifetime has a platform hard maximum of 30 seconds from signed `iat` to `exp`. An issuer may shorten but not widen it, and Gateway independently rejects missing or future-skewed `iat`, invalid `iat`/`exp` ordering, and a declared lifetime above the maximum. `firemud.gateway.connectTokenClockSkewMs` (environment variable `FIREMUD_GATEWAY_CONNECT_TOKEN_CLOCK_SKEW_MS`) is the single clock-skew setting for this contract. It defaults to `5000` ms and must be within `0..5000` ms; the same deployed value governs future-`iat` tolerance, expiry acceptance, replay-marker retention, and replay quarantine. A profile with an invalid, unknown, or inconsistent value is not eligible for player-facing admission.
- Gateway validates signature and key identity, issuer and audience, lifetime and expiry, required claims, and request routing scope before consuming the token.
- `firemud.gateway.replayConsumeAckTimeoutMs` (environment variable `FIREMUD_GATEWAY_REPLAY_CONSUME_ACK_TIMEOUT_MS`) is the one replay-durability acknowledgement timeout. It defaults to `1000` ms and must be an integer in the inclusive range `1..5000` ms; `5000` ms is the deployment hard upper bound. The same value is passed as the `WAITAOF` timeout and bounds the acknowledgement wait. There is no separate replay, AOF, or marker-write timeout. This setting does not replace the token lifetime or the quarantine deadline.
- The marker Lua script must retain Redis 7.2's default `redis.REPL_ALL` command-effects propagation for every replay-marker write. It must not use `redis.set_repl` to propagate a marker to AOF without the replication stream, suppress replication, or otherwise exclude that write from the replication offset used by `WAITAOF`; any such mode is incompatible with the durability proof and is not eligible for player-facing admission. The deprecated pre-Redis-7 `redis.replicate_commands()` switch is not a required application call.
- Gateway atomically consumes `jti` in the player-facing Coordination Redis deployment before attempting the upstream WebSocket connection. That deployment uses Redis 7.2 or newer with AOF enabled, `noeviction`, ACLs that prevent non-replay callers from mutating replay and fence keys, and reserved capacity above the bounded maximum live marker set. Replay markers never share a cache/rate-limit deployment or an eviction policy. Capacity alerts and an admission-readiness threshold fail new handshakes before memory exhaustion can make marker writes unreliable. The marker script atomically creates the replay key with its expiry at `exp + firemud.gateway.connectTokenClockSkewMs` and returns a fixed integer status (`1` for this request winning the `jti` race, `0` for an existing marker). Gateway must parse that script reply as exactly one integer under both RESP2 and RESP3; null, error, wrong type, wrong arity, or any value other than `0`/`1` is an uncertain outcome and fails closed. For a newly created marker, Gateway immediately executes `WAITAOF requiredLocalAofCount requiredReplicaAofCount firemud.gateway.replayConsumeAckTimeoutMs` on the same pinned Redis connection that executed the marker script, without returning it to a pool, reconnecting, or issuing an intervening write. `WAITAOF` acknowledges writes sent by the current connection, so a result from another connection cannot satisfy this contract. Its reply is an array of exactly two integers under both RESP2 and RESP3: `[localAofFsyncCount, replicaAofFsyncCount]`. Gateway must reject nulls, errors, wrong arity, wrong types, overflow, and negative counts, and accept `DURABLE_REPLAY_CONSUME_ACK` only when both counts meet their configured thresholds. Configuration validation requires `requiredLocalAofCount = 1`, `requiredReplicaAofCount >= 0`, `requiredReplicaAofCount = 0` only for the explicitly supported single-node profile and at least `1` for clustered player-facing profiles, and the canonical timeout above; impossible, negative, overflowed, or profile-inconsistent thresholds fail startup/preflight. `WAITAOF` is a Redis 7.2+ AOF-fsync acknowledgement, not a claim of protection from disk or hardware destruction. A successful `SET`, `WAIT`, read-back, or client write completion alone is not sufficient. If the marker write or `WAITAOF` result is lost, times out, is malformed, or is below threshold, the outcome is uncertain and the handshake fails closed. A player-facing profile that cannot prove the Redis version, AOF configuration, capacity headroom, and acknowledgement is not eligible to admit new handshakes.
- Consumption is final. If the backend connection or protocol upgrade subsequently fails, the client obtains a new connect token rather than retrying the spent token.
- Any Coordination Redis event that affects the shared Gateway replay-marker or replay-fence continuity domain, including a cold start, reset, unexpected replay-key eviction, failover, durability-acknowledgement timeout/failure, capacity-safety breach, or any other event that cannot prove marker continuity, atomically advances a shared `replayAdmissionFence` and records `state=QUARANTINED`, `quarantineStartedAt`, a shared signed-`iat` cutoff `quarantineCutoffIat`, and a finite `quarantineUntil` in Coordination Redis. A Cache/Rate-Limit Redis event, or a tenant/region-scoped Coordination Redis reset that does not affect this shared replay domain, does not trigger this quarantine. `quarantineCutoffIat` is the recorded detection cutoff, not a pod-local receipt time; every Gateway must reject a verified token whose signed `iat` is at or before that cutoff, and the fenced consume operation must repeat that comparison against the current shared record. Every Gateway must read that shared state and fence before admission; a pod-local circuit breaker, cache, or stale Gateway cannot reopen admission or retain an old cutoff. If the shared readiness record is absent and Coordination Redis is readable, one shared compare-and-set initialization may start a new quarantine from the current detection time and persist its new cutoff. If the readiness record cannot be read, admission remains closed and no initialization is permitted. A later replay-domain loss repeats that process with a new shared cutoff and restarts the complete quarantine window; recovery and reopen preserve the current persisted cutoff until the fenced transition to `OPEN`, and never infer, clear, or shorten it from local state. The quarantine deadline is at least the detection time plus the 30-second token maximum and two `firemud.gateway.connectTokenClockSkewMs` intervals: one covers the latest tolerated future `iat`, and the other covers acceptance through `exp + skew`. When the latest affected token expiry is known, the deadline also covers that expiry plus one skew interval. Tokens issued at or before the persisted quarantine cutoff are rejected, so pre-event tokens expire before admission can reopen. Gateways may reopen only after the shared deadline has passed, Coordination Redis is available, configuration and capacity probes pass, and a fresh disposable marker script followed by the configured `WAITAOF` thresholds proves `DURABLE_REPLAY_CONSUME_ACK`; one compare-and-set transition then records `state=OPEN` for the current fence. An explicitly configured local test profile may use isolated replay state with visible drift telemetry; no player-facing profile silently falls back to pod memory.

### Supported Replay Topology And Promotion Proof

- Player-facing replay protection is supported only on a dedicated Coordination Redis continuity domain using either a persistent single-node AOF deployment with no failover, or a `production_clustered` primary/replica or Redis Cluster deployment whose replay-marker keys and readiness fence remain within the documented continuity domain. The single-node and clustered choices may satisfy different deployment profiles, but neither may share replay state with Cache/Rate-Limit Redis, use an eviction policy, or rely on an ephemeral no-AOF profile.
- `WAITAOF` acknowledges the local and configured replica AOF-fsync positions for the marker write. It does not by itself prove that a future promoted primary will contain the marker, that a replica is eligible for promotion, or that disk/hardware loss is survivable. `DURABLE_REPLAY_CONSUME_ACK` therefore means the configured write acknowledgement only; failover durability requires the separate promotion proof below.
- Before reopening admission after a primary promotion or recovery, the recovery controller must record the continuity domain and fence, the old and new Redis identities/epochs, successful AOF load or equivalent recovery completion, the promoted node's replication/AOF position relative to the last acknowledged marker write, and read-back evidence for the replay-marker prefix and shared readiness state. A clustered deployment must also prove the affected marker key remained in the supported slot/continuity domain. If the promoted target or evidence cannot establish that acknowledged replay markers and the fence are preserved, the event is uncertain and follows the full quarantine path; Gateway must not infer continuity from a promoted role, Redis availability, a new `WAITAOF`, or an isolated successful read.
- A recovery that cannot prove marker continuity does not reopen early. It waits until the affected token acceptance window is dead, then passes the fresh disposable-marker and configured `WAITAOF` readiness probe before the shared compare-and-set transition to `OPEN`. This preserves single-use admission semantics even when the underlying Redis failover lost recent marker state.

### Scope Handoff And Proxy Exception

- The connect token is edge admission evidence, not gameplay authorization. Gateway converts its validated identity and routing scope into the short-lived signed connect context; Game Session still requires `LOGIN` and `PLAY` and validates the context against the selected target.
- Connect-token bypass is allowed only when the request arrives on the internal mTLS listener as the exact TCP Proxy workload identity and passes header-trust policy. The resulting `trusted_tcp_proxy` mode is positively asserted and validated; absence of a connect context or presence of proxy-shaped headers is not sufficient.
- Public listeners strip untrusted proxy, token, and connect-context headers before classification. A public client cannot select the proxy exception.

## Consequences

- URLs, browser history, access logs, and common proxy telemetry do not receive a query-carried bearer token.
- Ambiguous credentials cannot exploit carrier precedence, and a stolen token can open at most one accepted handshake during its very short lifetime.
- Horizontally scaled Gateway pods make one consistent replay decision. This costs one shared atomic Redis operation per non-proxy WebSocket handshake, not per WebSocket frame or gameplay action.
- Coordination Redis replay-state availability and continuity become dependencies for new player-facing connections. Existing admitted WebSockets continue, while new handshakes fail closed until protection recovers and any required quarantine completes.
- A transient upstream failure spends the token and requires one new issuance request. This is a deliberate bounded retry cost that avoids conditional un-consume semantics and replay races.
- Browser and non-browser clients use different carriers but converge on the same verified context, `LOGIN`, and `PLAY` state machine.

## Alternatives Considered

### Query-Parameter Carrier

Query transport works with basic WebSocket clients but exposes a bearer token to URL logging, tracing, browser history, and intermediary handling. A scoped HttpOnly cookie solves the browser API constraint without those routine leak paths.

### Carrier Precedence

Choosing the header or cookie when both are present appears convenient, but permits stale ambient cookies or injected headers to change which identity is admitted. Rejecting ambiguity is deterministic and easier to audit.

### Stateless Or Per-Pod Replay Checking

A signed token with no shared one-time-use check is replayable until expiry. Per-pod memory avoids Redis but loses correctness under load balancing, pod restart, and horizontal scaling. The handshake-only Redis operation is small enough to retain the stronger single-use property.

### Fail Open During Replay-Store Outage

Failing open improves connection availability during Redis incidents but turns an uncertain protection state into bearer-token reuse. FireMUD instead preserves existing connections and fails only new affected handshakes.

## Implementation and Proof Obligations

- Enforce the signed `iat` claim, 30-second `iat`-to-`exp` issuance and acceptance hard maximum, the single bounded `firemud.gateway.connectTokenClockSkewMs` setting, the positive-ms `firemud.gateway.replayConsumeAckTimeoutMs` setting, full required-claim profile, and `exp + firemud.gateway.connectTokenClockSkewMs` replay-marker retention.
- Reject multiple values within either carrier as well as dual carriers and query-only presentation.
- Prove validation and scope comparison occur before atomic consumption, consumption occurs before upstream connection, and retries after post-consumption failure require a new token.
- Prove the Redis 7.2+ replay deployment's supported topology, AOF configuration, same-connection marker-script/`WAITAOF` sequencing, exact RESP2/RESP3 reply parsing, validated `WAITAOF` local/replica thresholds, `noeviction`, ACL, capacity, and readiness controls; prove the shared persisted `quarantineCutoffIat` is compared to signed token `iat` before admission and again by the fenced consume path across repeated Redis loss, recovery, and reopen, that missing readiness state starts a new full quarantine with a new cutoff, the cutoff is preserved until `OPEN`, the atomic marker script plus threshold-satisfying `WAITAOF` acknowledgement is the only successful `DURABLE_REPLAY_CONSUME_ACK`, and record promotion/recovery evidence that establishes AOF load, continuity epoch, acknowledged write position, replay-marker-prefix preservation, and readiness-fence preservation before reopening. Replay decisions must remain atomic across Gateway instances, and admission must remain unavailable through the full reset/failover/eviction quarantine whenever that evidence is absent or marker continuity is uncertain.
- The proof must also verify that every marker write retains the default `redis.REPL_ALL` propagation and that the script never selects an AOF-only, replication-suppressed, or other mode excluded from the replication offset used by `WAITAOF`.
- Strip the raw header and named cookie before upstream forwarding; prove Game Session accepts only the signed connect context on first-party handshakes.
- Prove the proxy exception depends on the internal listener, exact authenticated TCP Proxy workload identity, trusted header promotion, and positive `trusted_tcp_proxy` mode.
- Complete dedicated asymmetric connect-context signing, verification-key overlap, unknown-key refresh, and fail-closed rotation proof.

## Required Documentation Alignment

- [Gateway architecture](../system-architecture-gateway.md)
- [Authentication architecture](../system-architecture-authentication.md)
- [Reconnection architecture](../system-architecture-reconnection.md)
- [ADR 0021](./adr-0021-staged-player-authentication-and-gameplay-binding.md)
- [Player access and session tracker](../../project-management/implementation-tracking/player-access-and-session.md)
- [Platform operations and delivery tracker](../../project-management/implementation-tracking/platform-operations-and-delivery.md)

## Reversibility and Revisit Triggers

The carrier set and token lifetime are localized edge contracts. Revisit if browser platform capabilities provide a safer standard header path, measured Redis availability makes new-connection denial unacceptable, or a future external protocol cannot use either supported carrier. Any new carrier must receive a security review covering disclosure, ambiguity, replay, logs, and client retry behavior.
