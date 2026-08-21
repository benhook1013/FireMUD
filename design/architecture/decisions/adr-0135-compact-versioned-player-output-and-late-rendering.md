# ADR 0135: Compact Versioned Player Output and Late Rendering

## Status

Accepted

## Implementation Status

The existing `PlayerOutput`, text renderer, structured WebSocket projector, prompt pipeline, room-view path, and structured reconnect metadata provide partial implementation evidence. They do not prove a supported versioned structured-client schema, an actual browser consumer, schema compatibility, or ADR 0059 temporal `LOOK` evidence.

## Decision Record

- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `CMD-03`
- Decision date: 2026-07-20
- Decision key: `CMD-03`
- Primary capability: `EA-1.2` structured output, rendering, prompts, and presentation policy
- Affected capabilities: `EA-1.1`, `EA-3.1`, `PO-2.2`, `SF-1.1`, `SF-1.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of domain outcomes, presentation ownership, text compatibility, structured-client support, schema growth, browser implementation reality, and temporal proof for composed `LOOK` results

## Context

Telnet, generic WebSocket, first-party web, accessibility surfaces, and later structured clients need different projections of the same gameplay outcome. Letting each domain service author final prose would duplicate presentation policy and couple gameplay semantics to a transport. Moving in the opposite direction to a general nested presentation language or a class for every feature would create a large compatibility surface before concrete clients need it.

The repository already has a useful internal `PlayerOutput` seam and projects structured envelopes at the first-party WebSocket edge. That does not yet establish a supported structured-client contract: the envelope has no explicit public schema version and no implemented and proven first-party browser application consumes it.

`LOOK` adds a separate correctness concern. Its structured result combines World and Entity data. The current implementation compares fields derived from the same static room scope. Equality proves that both reads named the same room, but it does not prove that Entity had applied through the temporal boundary represented by the World result. Late rendering cannot repair a mixed-time domain result.

## Decision

### Semantic Outcomes and Presentation Ownership

Domain services return typed semantic outcomes rather than final player-facing transport strings. Game Session maps those outcomes into `PlayerOutput`, applies player and game presentation policy, and owns final rendering and delivery for gameplay traffic.

This is an authority split, not a requirement that every domain and output feature have a unique class. Domain contracts carry the facts and outcome identity needed for correct presentation. Game Session owns prose templates, localization selection, `BRIEF`, color, prompt treatment, replay classification, and client projection.

### Compact Player-Output Contract

`PlayerOutput` remains a compact envelope with a small bounded set of top-level kinds, typed payloads, presentation tags, and delivery or replay policy. It does not become a general nested document tree, arbitrary layout language, or class-per-feature hierarchy.

Every supported output envelope must define a deterministic plain-text projection that preserves its essential meaning. Telnet and generic text WebSocket clients are normal supported text transports and consume that projection directly; they are not a silent downgrade path for structured schema incompatibility. A structured client that cannot consume the envelope's schema version is rejected explicitly with the error token `unsupported_schema_version` rather than receiving an unrequested or lossy structured substitute. Structured clients may consume typed payloads under documented compatibility rules, but text compatibility remains part of the contract rather than a best-effort fallback.

A structured first-party client contract must carry an explicit schema version and documented compatibility rules before it is described as supported. Internal Java records and the current unversioned WebSocket projection may evolve while they remain internal. The current edge projection is partial implementation; first-party browser consumption remains unimplemented until a browser application actually consumes and proves the supported versioned contract.

Richer bounded payload fields and tags may be added when concrete presentation requirements need them. A general presentation-document language requires a separate consequential decision with demonstrated client value and explicit complexity and compatibility limits.

### Temporal Proof for `LOOK`

The canonical same-fence `LOOK` requirement remains. It is satisfied through the causal-floor model in [ADR 0059](./adr-0059-causal-floor-cross-service-presentation-reads.md), not by equality between unrelated component versions or static scope-derived tokens.

World Management must return the temporal read requirement that anchors the composition, including the requested scope and epoch plus a committed-tick floor. Entity Management must match that same scope and epoch, prove `servedThroughTickId >= requested floor`, and return its own opaque actual component version. Game Logic rejects, retries, or fails the room view when that evidence is missing or below the requested floor.

A participant may be newer than the requested floor, but this ADR makes no numeric bounded-newer-skew promise and component-version values are opaque: they are not compared numerically or for equality across components. Exact historical same-instant snapshots are not required for ordinary presentation unless a later feature adopts the separate coordinated historical-snapshot contract described by ADR 0059.

## Consequences

- Gameplay semantics remain independent of Telnet, browser, localization, and accessibility rendering choices.
- Game Session has one clear presentation authority and text clients retain a complete supported projection.
- The bounded envelope avoids committing FireMUD to a premature UI document language or an ever-growing output-class taxonomy.
- Structured browser support cannot be claimed merely because the WebSocket edge emits JSON; schema versioning, compatibility, and a real consumer must be proven first.
- A valid structured `LOOK` requires trustworthy cross-service temporal evidence before rendering, exposing the current static-token seam as partial implementation.
- Game Session carries presentation responsibility and must keep its mapping and renderer behavior covered across supported client surfaces.

## Alternatives Considered

### Domain-Owned Final Text

Each gameplay service could return final player prose. This is initially direct, but it scatters localization, accessibility, `BRIEF`, replay, client capability, and style policy across services. Different transports would either parse prose or require client-specific domain responses. It is rejected.

### General Presentation Document Language

All output could use an extensible block, span, layout, and action tree. This can support rich clients, but introduces a broad schema, rendering engine, security surface, and compatibility burden without a current browser consumer requiring it. Bounded envelope evolution preserves the option to revisit with concrete evidence and is selected instead.

### Text-Only Canonical Output

Game Session could make rendered text the only canonical output and let rich clients display transcript lines. This is simpler at the edge, but loses typed views and status, makes accessible or dedicated UI presentation depend on text scraping, and weakens semantic reconnect context. It is rejected.

### Static Scope Equality as `LOOK` Fence Proof

World and Entity could continue returning the same room-derived token and treat equality as temporal alignment. This checks scope only and can accept independently read states from different times. It is rejected in favor of ADR 0059's causal floor and participant served-through evidence plus opaque component versions.

## Implementation and Proof Obligations

Proof for the output boundary must cover each supported envelope kind, deterministic Telnet and generic WebSocket text projection, structured-client schema version negotiation or compatibility handling, unsupported-version behavior, localization and presentation policy, replay eligibility, and semantic parity between text and structured projections. A first-party browser capability may be marked implemented only when the browser consumes the supported schema in focused integration or end-to-end proof.

Proof for `LOOK` must cover the causal floor, correct scope and epoch, Entity `servedThroughTickId >= requested floor` evidence, distinct opaque participant component versions, newer-than-floor evidence without numeric skew or version comparison, below-floor rejection, unavailable evidence, retry or explicit failure, and prevention of best-effort mixed-time composition. Static token equality tests remain useful scope checks but do not satisfy temporal proof.

## Reversibility and Revisit Triggers

Envelope fields, kinds, tags, and projections may evolve through explicit schema versions while preserving typed domain outcomes, Game Session presentation ownership, and mandatory text compatibility. Revisit a richer presentation language only when concrete client or accessibility requirements cannot be expressed cleanly through bounded payload evolution. Revisit exact historical `LOOK` snapshots only under ADR 0059's trigger for a feature that truly needs one historical instant.

## Required Documentation Alignment

- [`design/architecture/system-architecture-input-output-and-presentation.md`](../system-architecture-input-output-and-presentation.md)
- [`design/architecture/microservices/game-session-service/protocols.md`](../microservices/game-session-service/protocols.md)
