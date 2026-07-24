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
- Explicitly classified non-browser clients may use the dedicated `X-Firemud-Connect-Token` handshake header.
- A public gameplay handshake must contain exactly one non-empty, single-valued supported carrier. Duplicate header values, duplicate token cookies, or both carrier types are rejected; no precedence rule applies.
- Query parameters are not a connect-token carrier. Gateway does not promote or accept a query-carried token in player-facing environments.
- Gateway removes all external token carriers before forwarding and emits only the verified signed connect context.

### Lifetime, Validation, And Atomic Consumption

- Connect-token lifetime has a platform hard maximum of 30 seconds from signed `iat` to `exp`. An issuer may shorten but not widen it, and Gateway independently rejects missing or future-skewed `iat`, invalid `iat`/`exp` ordering, and a declared lifetime above the maximum.
- Gateway validates signature and key identity, issuer and audience, lifetime and expiry, required claims, and request routing scope before consuming the token.
- Gateway atomically consumes `jti` in shared Coordination Redis before attempting the upstream WebSocket connection. The security-critical marker uses a non-evicting prefix and remains until `exp` plus the bounded clock-skew allowance. The canonical `DURABLE_REPLAY_CONSUME_ACK` is an atomic marker commit plus local AOF persistence and the profile's configured minimum replica-AOF acknowledgement; a successful `SET`, `WAIT` replica-delivery acknowledgement, read-back, or client write completion alone is not sufficient. A player-facing profile that cannot provide its configured acknowledgement is not eligible to admit new handshakes.
- Gateway waits only the bounded `replayConsumeAckTimeoutMs` (a positive finite setting with a deployment hard upper bound). A timeout, connection loss, or malformed acknowledgement after the consume attempt leaves marker state uncertain; it is never interpreted as an unconsumed token or retried with an unconsume operation. The handshake fails closed with `CONNECT_REPLAY_PROTECTION_UNAVAILABLE`.
- Consumption is final. If the backend connection or protocol upgrade subsequently fails, the client obtains a new connect token rather than retrying the spent token.
- Any Redis cold start, reset, failover, durability-acknowledgement timeout/failure, or other event that cannot prove marker continuity atomically advances a shared `replayAdmissionFence` and records `state=QUARANTINED`, `quarantineStartedAt`, and a finite `quarantineUntil` in Coordination Redis. Every Gateway must read that shared state and fence before admission and the consume operation must compare the current fence; a pod-local circuit breaker, cache, or stale Gateway cannot reopen admission. The quarantine deadline is at least the incident time plus the 30-second token maximum and clock-skew bound, and also covers the latest affected token expiry plus skew when that expiry is known. Tokens issued at or before the quarantine cutoff are rejected, so pre-event tokens expire before admission can reopen. Gateways may reopen only after the shared deadline has passed, Coordination Redis is available, a fresh `DURABLE_REPLAY_CONSUME_ACK` probe succeeds, and one compare-and-set transition records `state=OPEN` for the current fence. An explicitly configured local test profile may use isolated replay state with visible drift telemetry; no player-facing profile silently falls back to pod memory.

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

- Enforce the signed `iat` claim, 30-second `iat`-to-`exp` issuance and acceptance hard maximum, full required-claim profile, clock-skew bound, and `exp`-based replay-marker retention.
- Reject multiple values within either carrier as well as dual carriers and query-only presentation.
- Prove validation and scope comparison occur before atomic consumption, consumption occurs before upstream connection, and retries after post-consumption failure require a new token.
- Prove Redis replay decisions are atomic across Gateway instances, receive the required durability acknowledgement, and remain unavailable through the full reset/failover quarantine whenever marker continuity is uncertain.
- Strip the raw header and named cookie before upstream forwarding; prove Game Session accepts only the signed connect context on first-party handshakes.
- Prove the proxy exception depends on the internal listener, exact authenticated TCP Proxy workload identity, trusted header promotion, and positive `trusted_tcp_proxy` mode.
- Complete dedicated asymmetric connect-context signing, verification-key overlap, unknown-key refresh, and fail-closed rotation proof.

## Required Documentation Alignment

- `design/architecture/system-architecture-gateway.md`
- `design/architecture/system-architecture-authentication.md`
- `design/architecture/system-architecture-reconnection.md`
- `design/architecture/decisions/adr-0021-staged-player-authentication-and-gameplay-binding.md`
- `design/project-management/implementation-tracking/player-access-and-session.md`
- `design/project-management/implementation-tracking/platform-operations-and-delivery.md`

## Reversibility and Revisit Triggers

The carrier set and token lifetime are localized edge contracts. Revisit if browser platform capabilities provide a safer standard header path, measured Redis availability makes new-connection denial unacceptable, or a future external protocol cannot use either supported carrier. Any new carrier must receive a security review covering disclosure, ambiguity, replay, logs, and client retry behavior.
