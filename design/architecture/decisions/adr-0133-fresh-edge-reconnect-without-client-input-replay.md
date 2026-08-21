# ADR 0133: Fresh Edge Reconnect Without Client-Input Replay

## Status

Accepted

## Implementation Status

The repository implements substantial `LOGIN`/`PLAY`, Redis rebinding, bounded semantic recent context, durable command, TCP Proxy advisory-disconnect, and Gateway rebind seams. It does not yet prove the complete authenticated real-service replacement sequence, configured elapsed recovery bounds, lifecycle classification, presence convergence, or all stalled-input outcomes. The effective disconnected-resume window is resolved but not fully enforced, and explicit first-party logout can currently present retained context contrary to ADR 0019 and CMD-04.

## Decision Record

- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `SESSION-03`
- Decision date: 2026-07-20
- Decision key: `SESSION-03`
- Primary capability: `AA-2.2` reconnect, resume eligibility, and cross-device continuity
- Affected capabilities: `AA-2.1`, `EA-1.3`, `PO-2.2`, `GR-1.4`, `SF-2.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of edge loss, invisible upstream recovery, Telnet and WebSocket interoperability, client-input replay, already admitted durable commands, transcript reconstruction, and explicit logout

## Context

FireMUD must distinguish loss of the client-facing edge transport from loss of an internal Gateway-to-Game-Session hop. When the TCP or WebSocket connection owned by the edge is gone, third-party clients need one interoperable recovery path. When Gateway retains that edge socket and completes ADR 0013's bounded internal rebind, requiring another public login would expose an internal worker lifecycle unnecessarily.

Replaying client input after a visible disconnect is unsafe. At the failure boundary the platform may not know whether the last line or frame crossed into durable admission. Blind replay could duplicate movement, purchases, combat, or other state changes. That does not mean work already accepted into FireMUD's durable command/effect machinery is canceled: it continues under its existing server identity and may produce an outcome after the original socket disappears.

## Decision

### Client-Visible Edge Loss

Loss of the client-facing TCP or WebSocket transport creates a fresh transport boundary. Telnet clients open a new TCP connection. First-party WebSocket clients obtain a fresh connect token and open a new WebSocket. Both repeat `LOGIN` and `PLAY`; Game Session then decides whether current authority permits resuming the old binding or requires fresh admission.

No reconnect path replays raw TCP bytes, WebSocket frames, unsent Telnet output, MCP state, or client commands from the prior transport. Input whose admission is ambiguous at disconnect may be lost under the edge's at-most-once contract. A client must not infer that reconnecting or repeating `PLAY` will resubmit it.

### Invisible Internal Recovery

Closure of only the Gateway-to-Game-Session upstream is not a client disconnect while Gateway retains the edge socket and safely completes ADR 0013's bounded rebind. That path keeps the existing client transport and does not repeat public connect-token admission, `LOGIN`, or `PLAY`. If recovery cannot finish within its bound, Gateway closes with `backend_unavailable` and the resulting visible edge loss follows the ordinary fresh reconnect path.

### Already Admitted Work and Reconstruction

A command already durably admitted before transport loss retains its recorded command/effect identity and continues under its ordinary execution fences and reconciliation contract. This ADR makes no exactly-once execution claim. FireMUD does not recreate it from client input. Its output may be appended to the authorized bounded semantic recent context even while no socket is attached and may appear as recent context after reconnect. This bounded semantic recent context is not an archive, delivery ledger, or exact frame replay.

After authorized `LOGIN` and `PLAY`, when current authorization succeeds and an eligible retained window exists under CMD-04, Game Session deterministically presents/restores that bounded semantic recent context in canonical order, then obtains a fresh authoritative `LOOK`. If the retained context is empty, expired, or omitted by CMD-04 bounds, presentation/restoration produces no context output. Game Session emits exactly one reconnect prompt only when both effective `firemud.presentation.prompt.enabled` and `firemud.presentation.prompt.emit-after-reconnect-restore` are enabled; if either is disabled, it emits zero reconnect prompts. This is state and narrative reconstruction, not proof of which bytes the old client received. Explicit gameplay `LOGOUT` is terminal and suppresses private retained-context presentation under ADR 0019. Planned Gateway `service_restart` is a retryable edge loss, not logout. `session_replaced` tells the displaced connection that another controller took over; a later reconnect still uses ordinary admission and may take over again if authorized. The setting precedence is owned by [Input, Output, and Presentation](../system-architecture-input-output-and-presentation.md#prompt-behavior).

## Consequences

- Telnet, generic WebSocket, and first-party clients share one documented recovery model after actual edge loss.
- The platform does not need acknowledgement offsets or a replayable client transport protocol.
- One command near a failure boundary may be lost if it never reached durable admission.
- A durable command accepted before disconnect may complete later, so reconnect context can contain results whose originating input is not replayed.
- Routine non-edge process replacement can remain invisible only within ADR 0013's bounded conditions.
- First-party clients may automate recovery but cannot depend on a private correctness model unavailable to ordinary text clients.

## Alternatives Considered

### Exact Transport and Command Resumption

Maintain per-client input/output offsets, acknowledgements, retained frames, and resumption tokens. This could identify precisely what each device missed, but would create a stateful protocol across WebSocket and legacy Telnet, add privacy and retention obligations, and risk duplicate effects unless every input participated in a new end-to-end idempotency contract.

### Always Expose Internal Worker Loss

Close the client transport whenever Game Session or another non-edge worker changes. This creates one simple path but interrupts routine deployments, causes reconnect storms, and discards the availability benefit of externalized session authority.

### Fresh Entry Without Context

Require new admission and emit only current state. This is correct and simpler, but produces a materially worse player experience around asynchronous outcomes and short disconnects. The bounded semantic recent context provides context without becoming delivery acknowledgement.

## Implementation and Proof Obligations

Proof must cover Telnet and WebSocket edge loss before admission, during ambiguous delivery, after durable command admission, and after output persistence; fresh token behavior for Web; fresh `LOGIN`/`PLAY`; resume versus fresh binding; durable work completing offline; reconstruction ordering; explicit logout; takeover; planned restart; abrupt no-frame loss; successful hidden upstream rebind; failed rebind cutoff; and absence of client-input, frame, or byte replay.

## Reversibility and Revisit Triggers

Reconnect automation, backoff, bounded semantic recent context presentation, and token carriers may evolve without changing the visible-edge boundary or no-input-replay rule. Acknowledged command delivery or exact connection resumption requires a separate protocol decision spanning both Telnet and WebSocket clients.

## Required Documentation Alignment

- [`design/architecture/system-architecture-reconnection.md`](../system-architecture-reconnection.md)
- [`design/architecture/microservices/game-session-service/runtime-and-data.md`](../microservices/game-session-service/runtime-and-data.md)
- [`design/architecture/microservices/game-session-service/protocols.md`](../microservices/game-session-service/protocols.md)
- [`design/architecture/system-architecture-input-output-and-presentation.md`](../system-architecture-input-output-and-presentation.md)
