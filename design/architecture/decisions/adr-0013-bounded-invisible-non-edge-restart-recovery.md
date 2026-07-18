# ADR 0013: Bounded Invisible Non-Edge Restart Recovery

## Status

Accepted

## Decision Record

- Decision date: 2026-07-18
- Primary capability: `GR-1.4` Runtime recovery, replay, and reconciliation
- Affected capabilities: `AA-2.2`, `GR-1.1`, `PO-2.4`, `PO-4.2`, `SF-2.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `SESSION-04`

## Context

FireMUD externalizes meaningful gameplay-session and execution state so replaceable Game Session, Game Logic, and other non-edge workers can recover without making routine backend turnover a player-visible reconnect. The edge connection itself remains process-local: loss of the serving Gateway WebSocket or TCP Proxy socket is an explicit reconnect boundary.

The previous target said non-edge restarts should be invisible but did not define which failures qualified, how long recovery could remain hidden, what happened to input during the stall, how backend lifecycle closes differed from terminal session closes, or what proof was required before claiming an availability guarantee. Canonical reconnection guidance also said both that any closed Gateway-to-Game-Session upstream required client reconnect and that Game Session restart should normally be invisible.

Gateway has a bounded upstream-rebind implementation, Game Session stores session context in shared state, and focused tests prove a stub upstream process bounce and a real Game Logic restart. The implementation does not yet prove authenticated continuity across a real Game Session process replacement, and some lifecycle, presence, connect-context, and stalled-input paths remain incomplete.

## Decision

FireMUD uses bounded invisible recovery for ordinary non-edge failures. It does not make every restart invisible and does not extend that promise across loss of the edge-owned client transport.

### Failure Classification

| Failure | Canonical client-visible result |
| --- | --- |
| Serving Gateway or TCP Proxy loses the client socket | Visible reconnect, followed by fresh connect-token admission where applicable, `LOGIN`, and `PLAY` |
| Attached Game Session front end or its upstream hop fails while the edge socket survives | Gateway retains the client socket and performs bounded rebind to a replacement Game Session instance; no new `LOGIN` or `PLAY` |
| Game Session lease ownership moves | Internal fenced handoff; no reconnect solely because ownership moved |
| Game Logic or another replaceable non-edge worker restarts | No transport reconnect; at most a short stall or explicit command failure |
| Safe recovery cannot complete within the bounded window | Close with `1013/backend_unavailable`; the client follows the normal reconnect path |

An ordinary qualifying restart is the loss or planned replacement of one non-edge instance while the edge socket, healthy same-type replacement capacity, and required shared authority remain available. Multi-instance outages, lost edge transport, unavailable shared authority, terminal session policy, and unsafe ownership ambiguity are outside the invisible-recovery guarantee.

### Meaning and Timing

Invisible recovery means the existing client-facing edge socket remains open and the player is not required to repeat `LOGIN` or `PLAY`. It does not promise zero stall, raw frame or byte replay, or exactly-once command completion.

- An ordinary qualifying restart has an initial functional recovery target of no more than 10 seconds.
- Hidden recovery has a hard elapsed-time cutoff of 30 seconds. Retry-attempt counts alone do not satisfy this bound.
- At the cutoff, Gateway closes the affected established session with `1013/backend_unavailable` rather than retaining an indefinitely half-open connection.
- These thresholds are target-state functional acceptance criteria, not a published percentile availability SLO. A stronger external SLO requires production evidence and a later explicit commitment.

### Command and Buffer Semantics

Commands remain per-connection FIFO where delivered and at-most-once at the edge. A command whose delivery is ambiguous at the failure boundary may be lost; Gateway must not replay it blindly and risk duplicate effects.

Input accepted after Gateway has detected an upstream stall must remain ordered and be delivered once after successful rebind. If the bounded buffer cannot accept it, Gateway must produce an explicit failure or close the connection with the applicable bounded taxonomy. It must not silently discard input while leaving the client apparently healthy. Buffer limits do not authorize raw transport replay.

### Lifecycle and Authority Semantics

The internal Gateway-to-Game-Session hop must distinguish rebindable backend lifecycle or transport loss from terminal session outcomes such as logout, takeover, policy rejection, revocation, and absolute session expiry. Public close code alone is insufficient for that internal distinction. A backend lifecycle classification is not a new client-visible close category: successful recovery remains hidden, while exhausted recovery maps to `1013/backend_unavailable`.

A replacement Game Session continues the established edge session from current server-side session authority and the stable edge transport identity. It must not require an originally admitted first-party connect token to remain valid as though rebind were a fresh public handshake. Current membership, entitlement, revocation, tenant/game scope, absolute session expiry, and fencing still apply. If continuation authority cannot be established safely, recovery fails closed through the applicable terminal-session result or `backend_unavailable` fallback.

Closure of only the internal Game Session upstream is not itself proof that the player transport was lost. Presence, disconnect events, and gameplay-binding teardown must follow authoritative edge liveness and replacement registration so a successful hidden rebind does not publish a false player disconnect or leave presence removed.

## Consequences

- Routine Game Session and downstream-worker turnover can avoid forcing manual Telnet recovery or first-party reconnect admission.
- Gateway owns bounded per-session retry and input-buffer state even though it owns no authoritative gameplay state.
- The common case improves player continuity, while long outages, missing shared authority, and edge-process loss remain explicit reconnect boundaries.
- A player can experience a connected-but-stalled interval. The finite cutoff prevents that interval from becoming indefinite.
- At-most-once edge semantics still permit loss of one ambiguous in-flight command. Stronger exactly-once client completion would require a separate protocol decision.
- Hidden rebind reduces reconnect storms but creates bounded internal retry and buffer load that must be observable and capacity-tested.
- Security and presence handling become more demanding because backend replacement continues an admitted edge session without treating it as a fresh public connection.

## Alternatives Considered

### Make Attached Game Session Loss Visible

Close the affected edge connection whenever its Game Session upstream is lost, then require fresh admission, `LOGIN`, and `PLAY`. This gives one simple recovery path and naturally repeats current security checks, but routine backend deploys interrupt players, manual Telnet recovery becomes common, and reconnect storms move load to Gateway, Account, and Game Session admission paths.

### Keep Invisibility as an Unbounded Aspiration

Continue describing non-edge restart invisibility without failure classes, timing, command behavior, or proof. This avoids choosing thresholds but leaves incompatible documentation, permits indefinitely stalled sockets, and cannot be tested or promised consistently.

### Make Every Service Restart Visible

Expose all restarts through reconnect. A restart of an unused replica has no meaningful client event, and making lease movement or stateless downstream replacement reconnect-visible would not improve routing or consistency. This alternative conflicts with the accepted session-front-end and fenced lease-owner separation.

## Implementation and Proof Obligations

- Bound hidden recovery by elapsed time, with the 10-second ordinary target and 30-second hard cutoff.
- Define and test the bounded internal lifecycle classification that separates rebindable backend loss from terminal session outcomes.
- Preserve stable transport identity and current server-side session authority across replacement without relying on an expired original connect token as fresh admission.
- Reconcile active transport registration, gameplay presence, disconnect events, session expiry, membership, entitlement, revocation, and fencing after replacement.
- Preserve FIFO for input accepted during a detected stall and close or fail explicitly on buffer overflow; never silently discard it.
- Prove the following real-service sequence: authenticated client through real Gateway and Game Session, successful `LOGIN` and `PLAY`, Game Session process loss, replacement Game Session, same client socket, and successful gameplay without another `LOGIN` or `PLAY`.
- Include planned and abrupt Game Session loss, Telnet and WebSocket paths, terminal close classifications, buffer exhaustion, cutoff expiry, and subsequent authoritative redraw in focused proof.
- Keep the capability implementation state partial until that combined proof exists. Stub-upstream bounce and separate downstream-worker restart tests do not independently satisfy the full guarantee.

## Reversibility and Revisit Triggers

The normal reconnect protocol remains mandatory, so FireMUD can fall back to visible reconnect without introducing a new client protocol. Withdrawing a relied-upon invisibility guarantee after production adoption would nevertheless be a player-visible availability regression and requires a new decision review.

Revisit the timing thresholds if production measurements show that healthy-fleet replacement cannot reliably meet them, if bounded Gateway retry state creates unacceptable fleet cost, if security or presence correctness cannot be maintained across continuation, or if client command acknowledgements later support a stronger delivery contract.

## Required Documentation Alignment

The following sources must remain aligned with this decision:

- `design/architecture/system-architecture-overview.md`
- `design/architecture/system-architecture-reconnection.md`
- `design/architecture/system-architecture-gateway.md`
- `design/architecture/system-architecture-protocol-bridging.md`
- `design/architecture/microservices/spring-cloud-gateway/README.md`
- `design/architecture/microservices/spring-cloud-gateway/client-behavior.md`
- `design/architecture/microservices/spring-cloud-gateway/configuration.md`
- `design/architecture/microservices/game-session-service/README.md`
- `design/architecture/microservices/game-session-service/runtime-and-data.md`
- `design/architecture/microservices/game-session-service/operations.md`
- `design/architecture/microservices/game-logic-service/runtime-and-data.md`
- `design/architecture/microservices/tcp-proxy-service/api-contracts.md`
