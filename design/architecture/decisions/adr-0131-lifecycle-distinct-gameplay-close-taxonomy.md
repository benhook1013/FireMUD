# ADR 0131: Lifecycle-Distinct Gameplay Close Taxonomy

## Status

Accepted

## Implementation Status

The current Gateway and TCP Proxy implementation and focused tests prove the displaced mapping: planned Gateway drain uses `1000/logout;subreason=gateway_restart`, takeover uses `1000/logout;subreason=takeover`, and TCP Proxy preserves those as Telnet `logout` outcomes. This ADR records a target-state change and does not claim that code or proof has been aligned.

The target bounded typed Game Session → Gateway lifecycle-intent contract is not yet versioned or implemented. Current behavior infers lifecycle intent from upstream close/error behavior; this ADR does not invent a new wire protocol or claim that the target intent contract is available.

Existing liveness gaps remain separate implementation obligations: the configured backend-unavailable limit must be enforced as a continuous elapsed-time cutoff no greater than 30 seconds, retry cadence must be bounded and jittered, recovery hysteresis and ping/pong behavior need focused proof, and stalled-input buffer exhaustion must produce an explicit `backend_unavailable` close rather than a silent drop. Current Gateway code instead uses a fixed attempt count/delay and can log a failed inbound buffer emission without closing the apparently healthy downstream connection.

## Decision Record

- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `EDGE-05`
- Decision date: 2026-07-20
- Decision key: `EDGE-05`
- Primary capability: `PO-2.4` player-edge liveness and shutdown behaviour
- Affected capabilities: `PO-2.2`, `GR-1.1`, `AA-2.2`, `PO-4.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of terminal logout, connection takeover, planned edge restart, abnormal failure, backend unavailability, WebSocket/Telnet equivalence, and operational subreason semantics

## Context

The existing close taxonomy collapsed several materially different lifecycle outcomes into WebSocket `1000/logout`: a player or administrator ending a gameplay session, replacement of one connection by another active controller, and a planned Gateway drain. Clients cannot safely infer the same response from all three. A true logout is terminal, takeover means the character remains controlled through another connection, and planned maintenance invites a fresh connection after the edge is ready.

The distinction was carried only in an optional `subreason`. That made correct client lifecycle behavior depend on metadata that the contract also allowed transports and frameworks to omit. It also made the Telnet translation describe planned restart and takeover as logout even though neither necessarily ends the gameplay identity or durable session state.

The platform still needs one bounded, transport-equivalent taxonomy. It must not revive the withdrawn `reroute` category or let backend services emit arbitrary external close semantics. Subreasons remain useful for operations but are not a sound lifecycle authority.

## Decision

Spring Cloud Gateway remains the sole translation owner for external gameplay WebSocket close frames. It maps bounded upstream/session outcomes into these top-level lifecycle categories, and TCP Proxy exposes equivalent Telnet reason tokens:

- WebSocket `1000/logout` and Telnet `logout` are reserved for terminal gameplay logout or forced session termination. This includes user logout and an administrator or security control deliberately ending the gameplay session.
- WebSocket `1000/session_replaced` and Telnet `session_replaced` identify the old transport displaced by a successful controller takeover. The replacement connection remains authoritative; the displaced client may reconnect only through the ordinary `LOGIN` and `PLAY` path and may itself take over again if still authorized.
- Standard WebSocket `1012/service_restart` and Telnet `service_restart` identify a planned Gateway drain or restart. Clients reconnect through the ordinary fresh-transport path using normal maintenance retry behavior.
- WebSocket `1001/idle_timeout` and Telnet `idle_timeout`, `1008/policy_violation` and `policy_violation`, `1011/internal_error` and `internal_error`, and `1013/backend_unavailable` and `backend_unavailable` retain their established meanings.

There is no `reroute` reason. Lease movement and shard ownership remain internal to Game Session. Exhausted bounded upstream recovery remains `backend_unavailable`.

An unexpected Gateway failure emits `1011/internal_error` when a close frame can still be sent. A process crash, node loss, or abrupt transport reset may prevent any final frame; WebSocket clients classify missing close metadata as abnormal transport loss and apply the `internal_error` retry policy. A WebSocket `1000` close without a recognized lifecycle reason is invalid/unattributed close metadata, not an implicit `logout` or `session_replaced`; a direct WebSocket client follows the same documented `internal_error` retry fallback. When an established authenticated Proxy-to-Gateway bridge is lost without a valid top-level close, TCP Proxy cannot prove the cause and preserves the established `backend_unavailable` fallback. It does not invent terminal logout or planned-maintenance intent. Both outcomes are retryable, but their top-level attribution reflects what each transport can actually observe.

Bounded subreasons may be recorded in logs and metrics and may be sent as optional wire hints. They are operational detail only. A client, TCP Proxy, or service must be able to determine terminal logout, takeover, planned restart, policy, internal failure, and backend-unavailable lifecycle behavior from the top-level close code and reason alone. Missing, unknown, or omitted subreason values must not change lifecycle behavior.

A close class reports connection/session lifecycle, not whether an in-flight gameplay command committed. Clients and tools reconcile any known `commandId` through the authoritative command-status surface; they do not infer command success or failure from `logout`, `session_replaced`, `service_restart`, `internal_error`, or `backend_unavailable`.

When observations overlap, a close code or reason alone never proves lifecycle commitment. If any failure condition is positively evidenced, select the highest-priority failure using `policy_violation` > `backend_unavailable` > `internal_error` > `idle_timeout`. Evaluate lifecycle evidence only when no failure condition is positively evidenced; then use authoritative lifecycle evidence from Game Session durable terminal logout or takeover evidence, or Gateway planned-drain evidence, and apply lifecycle precedence `logout` > `session_replaced` > `service_restart`: a committed logout stays terminal against competing lifecycle observations, but not against an independently positively evidenced higher-priority failure; otherwise a successful controller takeover wins over a concurrent planned drain; otherwise the outcome is planned `service_restart`. Missing or ambiguous evidence uses the existing observation-specific fallback; a subreason never supplies proof.

## Consequences

- Client reconnect policy no longer depends on optional `gateway_restart` or `takeover` suffixes.
- True logout remains unambiguously terminal, while takeover and maintenance no longer masquerade as logout.
- WebSocket and Telnet clients receive equivalent explicit lifecycle categories even though their wire encodings differ; unattributed hard transport loss retains the documented observation-specific fallback.
- Gateway remains the external translation authority; backend services signal typed internal intent and do not choose arbitrary client-facing categories.
- Operations may retain bounded subreason and bridge-shutdown labels for diagnosis without making them protocol authority.
- Existing clients that recognize only the older taxonomy need updating before the new behavior is enabled. FireMUD is pre-v1, so the target converges directly without a permanent dual mapping.
- The change does not introduce a shard-handoff signal or promise invisible recovery after an edge transport is actually lost.

## Alternatives Considered

### Keep `logout` and Require Subreasons

Continue using `1000/logout` for terminal logout, takeover, and planned restart, and require clients to inspect `subreason`. Rejected because optional metadata would become required to choose correct lifecycle behavior, and transports that omit or truncate it would silently restore the ambiguity.

### Keep `logout` and Treat Every Clean Close the Same

Use one clean-shutdown behavior for logout, takeover, and maintenance. Rejected because reconnecting after a true logout and stopping after a maintenance close are both incorrect outcomes. The distinctions already exist in the product lifecycle and should be present in the top-level protocol.

### Add `reroute`

Expose a fast-retry signal for lease or shard movement. Rejected because Gateway does not own gameplay shard routing and the platform has no accepted client-visible handoff contract. ADR 0007's no-reroute decision remains in force.

## Implementation and Proof Obligations

Implementation must update the Gateway close constants, upstream classification, observability normalization, TCP Proxy bridge translation, Telnet disconnect tokens, client guidance, and focused cross-service proof. Tests must show terminal logout, forced termination, takeover, planned drain, explicit internal failure, abrupt no-frame failure, backend-unavailable expiry, idle timeout, policy violation, missing or unknown subreasons, parity for explicit WebSocket and Telnet lifecycle outcomes, and the documented observation-specific fallback when no close frame exists. Liveness proof must separately measure one continuous backend-unavailable interval, including every retry and delay, and show that it closes within the configured limit and never after 30 seconds; prove the bounded jittered retry cadence; exercise recovery hysteresis plus ping/pong behavior; and show that failed stalled-input buffer emission closes explicitly as `backend_unavailable` rather than silently dropping the input while the downstream connection appears healthy. Tests must also prove that no `reroute` category is admitted and that optional subreasons cannot change lifecycle classification.

## Reversibility and Revisit Triggers

Numeric WebSocket codes, reason-token spellings, optional diagnostic labels, and retry timing may evolve through a versioned protocol update while preserving distinct terminal logout, displaced-controller, planned-maintenance, internal-failure, and backend-unavailable semantics. A client-visible handoff or `reroute` category requires a separate accepted routing and security design. Collapsing these outcomes again requires evidence that all supported clients can choose correct lifecycle behavior without the distinction.

## Required Documentation Alignment

- [Gateway](../system-architecture-gateway.md)
- [Protocol Bridging](../system-architecture-protocol-bridging.md)
