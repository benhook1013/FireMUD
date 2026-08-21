# ADR 0134: Bounded Durable Semantic Reconnect Context

## Status

Accepted

## Implementation Status

The current implementation substantially persists replay-eligible structured entries in ordered `resume_transcript_entry` rows and uses Redis only as a best-effort cache. Existing proof covers significant durable retention and replay behavior, but it does not prove oversized-entry omission or omission-marker behavior; the current runtime may retain an oversized entry. It does not yet implement this decision completely: durable context is keyed by `gameInstanceId` rather than `playableStateNamespaceId`, and a first-party explicit logout path can leave private context replayable. Previous documentation contradicted the implemented oversized-entry behavior; this decision resolves the target in favor of a strict ceiling without claiming complete envelope-accounting and marker proof.

## Decision Record

- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `CMD-04`
- Decision date: 2026-07-20
- Decision key: `CMD-04`
- Primary capability: `EA-1.3` resilient reconnect UX
- Affected capabilities: `AA-2.2`, `EA-1.2`, `SF-2.1`, `SF-2.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of reconnect context, delivery semantics, durable scope, privacy invalidation, byte bounds, and separation from transcript archive and export

## Context

A reconnecting player benefits from a small amount of recent output before FireMUD reconstructs current gameplay state. Without retained context, an ordinary transport loss produces a confusing discontinuity. That context does not prove which output the client received before the loss, however, and classic Telnet supplies no general acknowledgement protocol from which FireMUD could derive exact delivery.

Treating reconnect context as exact transport replay would require transport-specific delivery identities, client acknowledgements, resumable protocol state, and substantially more retention and recovery machinery. Even then, the platform would need to distinguish rendered bytes from semantic output across Telnet, WebSocket, first-party structured clients, locale changes, and renderer evolution. A complete transcript archive has different product, privacy, retention, and export obligations from the small window needed to make reconnect understandable.

The durable context must also follow the playable character state rather than a replaceable runtime process. A game instance may be replaced while the same durable playable-state namespace remains authoritative. Keying context by `gameInstanceId` would fragment or lose the relevant window across that replacement.

The semantic entries and their renderer-facing projection depend on the compact, versioned player-output and late-rendering boundary in [ADR 0135](./adr-0135-compact-versioned-player-output-and-late-rendering.md), even though that ADR has a later number.

## Decision

### Semantic Recent Context, Not Delivery Replay

Game Session retains a bounded durable window of replay-eligible structured player output as semantic recent context. It is not exact outbound-byte replay, transport delivery acknowledgement, command-input history, or a complete player transcript archive.

The context may repeat output the client already displayed before disconnect. It may omit output that was not replay eligible, was no longer retained, or was unavailable when the context was formed. Client and player-facing language must therefore describe it as recent context and must not label it as missed messages or imply exactly-once delivery.

Rendered text may remain a derived compatibility representation for Telnet and generic text clients, but canonical structured entries remain the durable source of truth under [ADR 0135](./adr-0135-compact-versioned-player-output-and-late-rendering.md). The reconnect feature does not preserve arbitrary raw bytes, frames, prompts, or unsent transport buffers.

### Durable Scope and Authorized Reconstruction

The durable context is scoped by `<tenantId, playableStateNamespaceId, characterId>`. It follows the same character in the same durable playable-state namespace across runtime instance replacement, while production and an isolated playtest remain separate because they use different namespaces. Persisted `reconnection.buffer` overrides use the stable `<tenantId, playableStateNamespaceId>` scope, so a replacement `gameInstanceId` inherits the effective bounds without copying or re-keying them. Existing rows keyed by a prior replacement instance must converge idempotently to this stable scope; migration preserves the effective bound and records ambiguous collisions for reconciliation rather than silently choosing a value.

Each admitted binding has a positive, monotonic `bindingGeneration`. It is not a fourth durable scope key: it fences the authorization/privacy episode for the durable scope above. Every `resume_transcript_entry` and cache envelope carries the exact binding generation; append, read, restore, and Redis cache fill/rebuild/mutation must require the current generation and recheck durable proof that the episode is not terminated. `LOGOUT` commits termination and generation evidence before acknowledgement; PostgreSQL append is conditional on the still-current, non-terminated episode, and cache work must refuse or invalidate old-generation state so stale in-flight work cannot repopulate replayable context. Restore uses only the exact current generation in canonical order and then obtains a fresh `LOOK`; physical deletion may remain asynchronous. This is target behavior, and current post-logout/generation proof is incomplete.

FireMUD replays private context only after current authentication and authorization establish access to that exact tenant, playable-state namespace, and character. An explicit gameplay logout, character ownership transfer, or loss of replay authorization clears the context or suppresses its private replay. Retention alone never grants access.

After an authorized reconnect, FireMUD presents the retained semantic context in canonical order, then obtains a fresh authoritative `LOOK` and emits exactly one reconnect prompt only when both effective `firemud.presentation.prompt.enabled` and `firemud.presentation.prompt.emit-after-reconnect-restore` are enabled; if either is disabled, it emits zero reconnect prompts. When enabled, duplicate-prevention still ensures one prompt. The fresh reconstruction, not the retained window, defines current gameplay state. Prompt-setting precedence is owned by [Input, Output, and Presentation](../system-architecture-input-output-and-presentation.md#prompt-behavior).

### Strict Bounds

The effective policy defines bounded retention, including a hard byte ceiling. The hard ceiling is absolute for the complete persisted context and its scope and metadata. Soft-ceiling message or line floors apply only where they fit beneath that hard ceiling.

FireMUD evicts complete oldest entries when adding a complete entry would exceed the applicable bound. A single entry larger than the hard ceiling cannot bypass the ceiling. It is omitted from reconnect retention or represented by a bounded omission marker whose own complete persisted size fits within the ceiling. FireMUD does not retain a partial or silently truncated semantic entry.

Optional inactivity expiry may remove the whole retained context. Retention settings cannot convert the reconnect window into an unbounded archive.

### Separate Archive and Export

A complete Player Transcript Archive and Export remains a separate future feature. If adopted, it requires its own finite retention, privacy, access, deletion, artifact, and export contracts. It does not enlarge or alter the reconnect context by implication.

## Consequences

- Reconnect remains understandable for Telnet and structured clients without claiming impossible delivery certainty.
- Recent context survives runtime instance replacement because it follows the durable playable-state namespace.
- A player may see a repeated line or miss output that was never retained; current state is repaired by fresh `LOOK` and, when both effective reconnect-prompt settings are enabled, exactly one prompt reconstruction.
- Private replay fails closed after logout, ownership transfer, or lost authorization.
- Target/post-implementation consequence: the hard byte ceiling is operationally trustworthy even for an unusually large single output; current complete-envelope accounting and omission-marker proof remain implementation gaps.
- Redis may accelerate replay but cannot be the source of truth for the promised durable window.
- A future exact resumable protocol or complete archive must be designed explicitly rather than growing accidentally out of reconnect storage.

## Alternatives Considered

### Exact Delivery-Acknowledged Replay

Assign delivery identities to outbound output, require client acknowledgements, retain unacknowledged output, and resume from the last acknowledged position. This is the strongest guarantee but is not selected as the general FireMUD contract. It requires a new stateful protocol and per-client delivery state, does not map naturally onto ordinary Telnet, increases storage and privacy exposure, and couples semantic recovery to transport rendering. A future capable client protocol may add an explicit bounded acknowledgement contract without redefining the baseline reconnect context.

### No Retained Context

Reconnect with only a fresh `LOOK` and the effective reconnect-prompt result (exactly one prompt when both prompt settings are enabled, otherwise zero). This is simpler and avoids replay ambiguity, but players lose useful conversation and action context after an ordinary transport interruption. The bounded semantic window provides substantial UX value without claiming delivery guarantees.

### Complete or Player-Selected Transcript Archive

Retain all player-visible output, potentially with player-selected duration, and use it for reconnect. This makes archive and export easy to expose but substantially expands privacy, retention, deletion, moderation, storage, and access-control obligations. It is deferred as a separate feature rather than required for reconnect.

### Runtime-Instance-Scoped Context

Key the context by `gameInstanceId`. This mirrors the current implementation but is rejected because planned replacement may use a new runtime instance for the same durable playable state. The reconnect scope must follow the state namespace rather than the process identity.

## Implementation and Proof Obligations

Proof must cover runtime instance replacement within one playable-state namespace, isolation between production and playtest namespaces, Redis loss, database restart, ordering, eviction at soft and hard bounds, a single oversized entry, and a bounded omission marker. It must also cover repeated output, unavailable output, context followed by fresh `LOOK` and exactly one current prompt when both effective reconnect-prompt settings are enabled, zero reconnect prompts when either is disabled, explicit logout, ownership transfer, authorization loss, cross-tenant and cross-namespace denial, expiry, and rendering for Telnet and structured clients.

UX proof must verify that no client labels the context as missed messages or implies delivery acknowledgement. Privacy proof must demonstrate that retained bytes cannot be replayed after authority changes even if physical deletion or cache invalidation is delayed.

## Reversibility and Revisit Triggers

Entry schemas, rendering projections, cache shape, byte defaults, expiry defaults, and omission-marker presentation may evolve while preserving bounded semantic recent context, namespace scoping, authorization, and non-delivery semantics. Revisit exact acknowledgement only if FireMUD adopts a capable resumable client protocol and is prepared to define transport-specific delivery state. Revisit archive and export only as a separately scoped product decision with explicit privacy and retention requirements.

## Required Documentation Alignment

- [`design/architecture/system-architecture-input-output-and-presentation.md`](../system-architecture-input-output-and-presentation.md)
- [`design/architecture/system-architecture-reconnection.md`](../system-architecture-reconnection.md)
