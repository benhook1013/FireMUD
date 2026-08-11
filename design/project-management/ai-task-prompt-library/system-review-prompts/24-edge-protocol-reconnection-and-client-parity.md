# Edge, Protocol, Reconnection, And Client Parity Review

Use this prompt to review player and control-plane traffic from the external edge through protocol translation and session handling, including visible behavior during failure and reconnection.

Apply the [shared review contract](./00-shared-review-contract.md).
Apply the [orchestrated review workstream contract](./02-orchestrated-review-workstream-contract.md).

## Orchestrated Execution

A full invocation is an orchestrated review workstream. The invoking main thread takes primary ownership and delegates these prompt-specific bounded evidence lanes:

- public and internal route ownership, edge trust, and identity binding;
- browser, WebSocket, Telnet, TLS Telnet, MCP, and client handshake, framing, encoding, and negotiation;
- ordering, backpressure, disconnect, reconnect, takeover, and replay; and
- transport and client parity, visible failures, and proof.

The primary reconciles route-to-session seams and intentional client differences across the lanes.

## Starting Sources

- `design/architecture/system-architecture-gateway.md`
- `design/architecture/system-architecture-protocol-bridging.md`
- `design/architecture/system-architecture-mud-client-protocol.md`
- `design/architecture/system-architecture-reconnection.md`
- `design/architecture/system-architecture-input-output-and-presentation.md`
- `design/architecture/system-architecture-player-command-model.md`
- `design/architecture/system-architecture-frontend.md`
- `design/architecture/microservices/spring-cloud-gateway/`
- `design/architecture/microservices/tcp-proxy-service/`
- `design/architecture/microservices/game-session-service/protocols.md`
- route catalogs, authorization matrices, web-client code, transport implementations, and focused proof

## Review

Check:

- the exact public and internal route inventory and which component owns each boundary;
- browser, generic WebSocket, Telnet, TLS Telnet, MCP, and first-party client handshakes;
- trusted headers, connect tokens, identity binding, protocol negotiation, and downgrade behavior;
- command ordering, backpressure, rate limits, framing, encoding, output rendering, prompts, and transcript semantics;
- disconnect classification, close-code translation, buffering, resume windows, reconnect, takeover, replay, and terminal failure;
- parity of supported behavior and intentional differences across transports and clients;
- client-visible errors, retry guidance, stale state, duplicate submission, and unavailable-service behavior; and
- agreement among target contracts, routes, implementation, tests, smoke tooling, and current product journeys.

Security findings about adversarial trust or abuse belong to the security review; this prompt owns transport correctness and visible parity.

## Output

Provide:

1. a route and client/transport coverage table;
2. exposure, handshake, framing, ordering, reconnect, and parity findings;
3. undefined or inconsistent client-visible failure behavior;
4. implementation and proof drift; and
5. the review state required by the shared contract.
