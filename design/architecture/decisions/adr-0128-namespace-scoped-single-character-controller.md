# ADR 0128: Namespace-Scoped Single Character Controller

## Status

Accepted

## Decision Record

- Decision date: 2026-07-20
- Decision key: `SESSION-02`
- Primary capability: `AA-2.3` takeover, logout, idle expiry, and revocation
- Affected capabilities: `AA-2.1`, `AA-2.2`, `EA-3.1`, `SF-2.2`, `AR-3.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of concurrent character control, runtime replacement overlap, isolated playtests, automatic takeover, binding generations, accepted work, and possible read-only attachments

## Context

FireMUD permits only one active gameplay controller for one durable character state. The previous uniqueness key included `gameInstanceId`. That is too narrow after ADR 0101 separated replaceable runtime identity from stable `playableStateNamespaceId`.

During a replacement cutover, an old instance may drain existing sessions while the replacement instance admits new or reconnecting sessions. Both instances intentionally address the same playable-state namespace. Keys `<tenantId, oldGameInstanceId, characterId>` and `<tenantId, newGameInstanceId, characterId>` are different, so instance-scoped uniqueness could allow two connections to control the same character state.

An isolated playtest is different. It has a distinct playable-state namespace, so simultaneous control of the production character and its isolated playtest copy does not create concurrent writers to the same state.

## Decision

Exactly one active gameplay controller is permitted for `<tenantId, playableStateNamespaceId, characterId>`. A gameplay binding still carries `gameInstanceId`, realm-routing identity, region placement, and runtime fences, but replaceable instance identity is not the uniqueness boundary for control of durable character state.

A successfully authorized `PLAY` for an occupied uniqueness key performs automatic takeover through one atomic controller-transfer compare-and-set. The transfer advances a monotonic `bindingGeneration`, installs the new binding, and makes the previous binding non-admitting at one linearization point. Delete-then-create sequences that expose an unowned or dual-owned interval are not the target contract.

Input submitted by the displaced transport after the committed transfer is rejected. Work durably admitted before that boundary retains its existing command and effect identity and follows the ordinary durable execution contract; takeover neither recreates it from client input nor blindly cancels it. Tick state, timers, cooldowns, and character state belong to the gameplay entity or namespace rather than the socket and continue under their owning contracts.

Closing the displaced transport is best-effort cleanup after the authority transfer. The edge uses the canonical `session_replaced` outcome. Failure to deliver a close frame does not restore authority to the old binding because command admission checks the current binding generation.

Automatic takeover remains the default rather than requiring an additional confirmation prompt. A valid authenticated player frequently needs recovery from a stale socket or clean device handoff. Rate limiting, audit, player notification, and account-security controls address suspicious takeover patterns.

FireMUD may later add separately modelled authenticated read-only attachments for accessibility or second-screen presentation. Such an attachment has no command authority and does not weaken the single-controller invariant. Concurrent multi-writer gameplay sessions are not supported.

## Consequences

- Replacement instances can overlap during bounded drain without permitting two controllers for the same durable character.
- Production and an isolated playtest may be controlled concurrently because their namespaces are intentionally distinct.
- Takeover requires namespace-aware session records and indexes plus one atomic monotonic transfer.
- Already admitted durable work can complete without allowing new input from the displaced socket.
- The model does not provide same-character cooperative multi-device control.
- Read-only mirrors remain possible later but require their own privacy, fan-out, lifecycle, and abuse contract.

## Alternatives Considered

### Keep Instance-Scoped Uniqueness

This is simple and matches current keys, but it permits simultaneous control when old and replacement instances share one playable-state namespace during cutover.

### Forbid Runtime Overlap

Stopping the old instance before admitting the replacement would keep instance-scoped keys unique, but removes bounded draining and makes deployments and recovery more disruptive. It also couples a character-control invariant to runtime topology.

### Permit Concurrent Multi-Writer Sessions

Multiple devices could control one character, but FireMUD would need cross-connection ordering, prompt and input arbitration, duplicate-action rules, and clear semantics for simultaneous movement and resource spending. No current product requirement justifies that complexity.

### Require Takeover Confirmation

An additional prompt could prevent accidental device flapping, but adds friction to ordinary reconnect and does not materially stop an attacker who already has valid account authority. Observable automatic transfer is the better initial policy.

## Implementation and Proof Obligations

Current implementation uses instance-scoped indexes and does not prove one atomic ABA-safe controller transfer. It deletes and recreates parts of the binding, lacks the target namespace key and durable binding-generation contract end to end, and may emit region-exit lifecycle behavior even though takeover leaves the character in place. These are implementation gaps, not evidence for the old target.

Proof must cover same-instance takeover, old-to-replacement-instance takeover within one namespace, concurrent competing `PLAY` calls, stale socket input after transfer, Redis restart and retry, failed close delivery, accepted work crossing the boundary, region binding convergence, and simultaneous production plus isolated-playtest control. It must show that takeover does not publish a false character exit or reset entity-owned timers and state.

## Reversibility and Revisit Triggers

Key encoding, Redis scripts, close delivery, and notification UX may evolve while preserving namespace-scoped single-controller authority and atomic monotonic transfer. Read-only attachments may be added separately. Supporting multiple command authorities for one character requires a new product and ordering decision.

## Required Documentation Alignment

- `design/architecture/system-architecture-session-behavior.md`
- `design/architecture/system-architecture-authentication.md`
- `design/architecture/system-architecture-redis.md`
- `design/architecture/system-architecture-reconnection.md`
- `design/architecture/microservices/game-session-service/runtime-and-data.md`
- `design/architecture/microservices/game-session-service/protocols.md`
