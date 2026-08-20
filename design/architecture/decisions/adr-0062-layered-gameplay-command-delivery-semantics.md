# ADR 0062: Layered Gameplay Command Delivery Semantics

## Status

Accepted

## Implementation Status

The edge path has current FIFO/at-most-once transport seams and durable command foundations, but trusted acceptance-before-acknowledgement, complete durable lifecycle convergence, optional safe-replay classification, and capable-client identity/status proof are not complete across all command paths. Base Telnet remains free of client-managed identities.

## Canonical Design

- [Protocol Bridging](../system-architecture-protocol-bridging.md#ordering--delivery-invariants)
- [Reconnection Strategy](../system-architecture-reconnection.md)
- [gRPC API Style and Versioning](../system-architecture-grpc.md#event-and-streaming-semantics)

## Decision Record

- Decision date: 2026-07-19
- Decision key: `SESSION-06`
- Primary capability: `SF-1.1` Client connection lifecycle and command delivery
- Affected capabilities: `SF-2.3`, `GR-1.2`, `AA-2.2`, `PO-2.4`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review with independent contract validation and universal client-identity/retry alternative analysis
- Human review status: Completed
- Human review date: 2026-07-19
- Human review disposition: Revised
- Review source: `SESSION-06`

## Context

The prior contract described client commands as FIFO, at-most-once, and fire-and-forget while other architecture already required durable command status and recovery. Those terms refer to different delivery boundaries and cannot form one end-to-end guarantee. The edge cannot guarantee that a disconnected client command arrived, while trusted Game Session acceptance must not permit a player-significant command to vanish silently.

ADR 0016 defines the canonical gameplay-command status lifecycle, stable identity, and the distinction between volatile and explicitly durable acceptance. ADR 0058 requires accepted player commands to retain durable evidence and converge to application or explicit terminalization after Redis loss. This decision specifies how the external transport boundary, trusted acceptance boundary, replay policy, outbound delivery, and internal events fit those decisions.

## Decision

### Client-to-Game-Session Edge Delivery

Per-connection client commands are FIFO where delivered and at-most-once at the edge. The TCP proxy and equivalent edge transports do not replay a command after disconnect and do not promise that bytes sent immediately before connection loss reached Game Session.

### Trusted Acceptance and Command Identity

When Game Session accepts a command, it assigns or retains a stable `commandId` and durably records the command status before acknowledging acceptance. This durable status is the authority for whether the command was accepted and for its later outcome.

Ordinary interactive commands are tracked but are not automatically replayable. If an accepted ordinary command is lost before durable staging, its status terminalizes as `NOT_APPLIED`; stale movement, combat, or similar intent is not executed later merely to provide delivery.

A capable WebSocket, MCP, or other structured client may supply a command identity and query the canonical status API. Classic Telnet clients are never required to create, retain, or type command identities; trusted ingress assigns them.

Only a feature with an explicit durable-intake and safe-replay contract may use the durable-intent lane. Replayability is a property of that declared command class, not a general consequence of acceptance.

### Server-to-Client Delivery

Raw outbound frames are not replayed after reconnect. Under [ADR 0134](./adr-0134-bounded-durable-semantic-reconnect-context.md), authorized reconnection may restore a bounded semantic recent-context window from Game Session-owned durable persistence. It is not a transcript or archive, delivery ledger, or evidence that any exact frame was delivered or observed.

### Internal Delivery

Internal events and advisory signals use at-least-once delivery with idempotent consumers. Advisory deduplication state may be disposable only when duplicate replay is harmless when checked against authoritative session state. Durable deduplication remains required when duplication could create a distinct player-significant or correctness-bearing effect.

## Consequences

- FireMUD states an honest guarantee at each boundary instead of implying exactly-once end-to-end delivery.
- Trusted acceptance gains durable identity and terminal outcome evidence without replaying stale interactive actions.
- Classic Telnet remains simple, while capable clients gain optional identity-based status correlation.
- Features that require replay must explicitly prove safe stale-intent semantics and durable intake.
- Reconnect restores useful bounded semantic recent context without turning it into a transcript, archive, or delivery ledger.
- Internal advisory paths may avoid unnecessary durable dedupe writes when authoritative state makes duplicates harmless.

## Alternatives Considered

### Universal Client-Assigned Identity and Automatic Retry

Require every client to assign a command identity, retry unconfirmed commands, and receive exactly-once completion semantics. Rejected because it complicates or breaks classic Telnet, still cannot prove player observation of a response, and risks applying stale movement or combat after context changes.

### Fire-and-Forget Beyond Trusted Acceptance

Allow an acknowledged Game Session command to disappear without durable status. Rejected because it contradicts the canonical lifecycle and Redis-loss outcomes in ADR 0016 and ADR 0058 and can report false success.

### Replay Raw Outbound Frames

Retain and replay every server frame after reconnect. Rejected because replayed text may be stale, duplicated, or misleading after authoritative state advances. A bounded semantic recent-context window restores context more honestly.

## Implementation and Proof Obligations

- Prove per-connection edge ordering and absence of edge replay after disconnect.
- Prove Game Session writes stable command identity and authoritative status before acknowledging trusted acceptance.
- Prove accepted ordinary commands lost before staging converge to `NOT_APPLIED` and are not later executed.
- Prove explicitly replayable command classes have declared durable intake, idempotency, and stale-intent safety.
- Prove capable-client identity reuse returns the same logical status while Telnet requires no client-visible identity management.
- Prove reconnect context does not claim exact frame delivery and cannot replay authoritative mutations.
- Prove duplicate internal events are harmless or durably deduplicated according to their effect class.

## Reversibility and Revisit Triggers

Client protocols may add optional status and retry features without changing classic Telnet semantics. Revisit automatic replay only for a command class with demonstrated stale-intent safety and a product requirement for durable execution. Revisit raw-frame replay only if a protocol introduces explicit delivery acknowledgements and a bounded retention contract; connection reconnection alone is insufficient.
