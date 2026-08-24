# ADR 0149: Communication-Type-Specific History and Retention

## Status

Accepted

## Implementation Status

This decision is not implemented. Communication-type storage contracts, safety evidence separation, retention, idempotency receipts, and disclosure-preserving views remain gaps.

## Decision Record

- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `MS-SOCIAL-HISTORY-DURABILITY`
- Decision date: 2026-07-20
- Decision key: `MS-SOCIAL-HISTORY-DURABILITY`
- Primary capability: `EA-2.1`
- Affected capabilities: `SF-2.1`, `SF-2.2`, `SF-2.3`, `PO-1.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of player-visible history, safety evidence, retention, privacy, acknowledgement, idempotency, and cache authority

## Context

The existing Social & Groups design persists all communication to PostgreSQL and keeps older messages there for moderation and historical logs. That treats ordinary world speech, account direct messages, mail, channels with promised scrollback, reconnect context, safety evidence, and retry deduplication as one durability class. It also implies indefinite content retention without defining expiry, read access, export, erasure or tombstone behavior, or what acknowledgement means for each type.

Those uses need different guarantees. Mail and explicit message history must survive restarts and be committed before the platform acknowledges the promise. Live world speech does not thereby need to become a permanent player archive. Moderation may need a finite protected copy of some content even when players have no history surface, while idempotent retry handling may need only a content-free receipt. Combining these records expands privacy exposure and makes product behavior depend on an accidental storage default.

Recipient-specific communication also creates a disclosure boundary. A player who received only metadata, redacted content, or a partial observer view must not later recover the full communication through history, export, moderation-facing APIs, or a cache refill.

## Decision

### Each Communication Type Declares Its Storage Contract

Every communication type has an explicit versioned storage contract rather than inheriting blanket persistence. The contract declares independently:

- whether it provides durable player-visible history;
- whether content or metadata enters a finite, separately protected moderation or safety-evidence store;
- its retention and expiry behavior for each stored class;
- who may read each class and through which surface;
- whether and how it participates in account or tenant export;
- its erasure, redaction, and tombstone behavior;
- and what durable state, if any, must commit before the operation is acknowledged.

Mail, account direct messages, and channels that promise scrollback are durable before acknowledgement. Their type contract defines the promised history window and lifecycle; durability does not imply indefinite retention.

World speech is live communication by default. `SAY`, nearby `WHISPER`, topology-aware `SHOUT`, gameplay `TELL`, and game-defined equivalents do not automatically create a permanent player-visible archive merely because Social & Groups participates in delivery. A specific communication type may deliberately promise bounded player history, but that is an explicit product and privacy choice.

### Safety Evidence Is Separate from Player History

A communication type may retain a separately protected, finite moderation or safety-evidence record even when it exposes no player-visible history. The evidence contract declares its purpose, access roles, retention deadline, report or case linkage, and erasure or legal-hold behavior. It is not an invisible extension of ordinary player history and cannot be retrieved through player history APIs.

Logging & Admin case and audit history remains distinct from Social-owned communication evidence. A moderation report may reference or receive an authorized evidence projection, but copying content into a case follows the case's separately declared access and retention contract. Analytics, diagnostics, or general logs do not become alternate unbounded communication archives.

### Idempotency Receipts May Outlive Content

A content-free idempotency receipt may remain after communication content expires when retry and duplicate-prevention windows require it. The receipt contains only the stable operation identity, payload digest or equivalent equality proof, terminal acknowledgement class, and bounded lifecycle metadata needed to return the same outcome or reject conflicting reuse. It cannot reconstruct message content or expand the original audience.

Acknowledgement is type-specific. A durable-history type acknowledges its history commitment before reporting the corresponding durable acceptance. A transient live type may acknowledge semantic admission or delivery attempt according to its declared contract without implying persisted content or successful end-user delivery. Safety-evidence capture and transport delivery remain separately reportable outcomes unless that type explicitly makes either a prerequisite.

### Redis Is a Rebuildable Cache and Fanout Mechanism

Cache/Rate-Limit Redis may hold recent history projections, delivery buffers, or transient fanout state with bounded TTL and size limits. It is never authoritative for promised durable player history, moderation evidence, erasure state, or idempotency whose retry window must survive cache loss. Losing Redis may remove transient delivery or make a history read slower; it must not change the authoritative retention or visibility contract.

Game Session's bounded reconnect or transcript context remains a separate namespace-scoped presentation facility under [ADR 0134](./adr-0134-bounded-durable-semantic-reconnect-context.md). It is not Social player history, delivery acknowledgement, a missed-message feed, or a moderation archive.

### Stored Views Preserve Original Disclosure

Any player-visible history stores or derives the exact semantic view that recipient was authorized to receive. History, export, cache refill, retry, and later client rendering must never disclose more content or metadata than that recipient's original authorized view. A metadata-only observer cannot retrieve full content later; redaction or partial-content views remain bounded to the originally authorized projection.

Safety and case surfaces may expose additional content only under their separately authorized evidence contract. They do not retroactively alter the player's original receipt or history entitlement.

## Consequences

- Durable mail, account direct messages, and promised channel scrollback retain restart-safe behavior without turning all speech into a permanent archive.
- Communication type definitions become responsible for explicit retention, access, export, erasure, acknowledgement, and safety-evidence choices.
- Social & Groups may need distinct tables or explicit class fields and lifecycle jobs instead of one indefinitely retained `chat_message` table.
- Finite protected evidence can support moderation without silently exposing that evidence as player history.
- Content-free receipts preserve retry safety after content expiry with less privacy and storage cost.
- Redis remains operationally disposable for chat history and delivery projections.
- Recipient-specific stored projections increase schema and test complexity but prevent later history or export paths from escalating disclosure.

## Alternatives Considered

### Persist Every Communication Indefinitely

This gives one simple history source and maximizes later moderation search. It retains world speech without a product promise or bounded purpose, leaves expiry and erasure undefined, increases privacy and breach impact, and risks replaying more than a recipient originally saw. It is rejected.

### Keep All History Only in Redis

TTL-only Redis lists are operationally simple and bounded. They cannot provide restart-safe mail, direct-message scrollback, promised channel history, durable safety evidence, or retry deduplication across cache loss. They remain suitable only as rebuildable projections and transient fanout state.

### Use One Retention Period for All Content

A uniform finite period is easier to configure than indefinite storage but still conflates product promises and purposes. Mail, live world speech, safety evidence, and content-free receipts have different access and durability needs. Type-specific declarations are required even if several types initially share the same numeric retention value.

### Treat Moderation Logs as the History Store

Logging & Admin could retain all messages and serve both player and operator reads. That makes an operator control-plane service the content authority for routine social features, broadens moderation access, and mixes player history with case evidence. Social owns the applicable communication records; Logging & Admin owns cases and audit.

## Implementation and Proof Obligations

The current implementation is not aligned. It writes chat messages to one PostgreSQL `chat_message` model and the documentation describes older messages as remaining there for moderation and historical logs. It does not prove type-specific expiry, player-history reads, export, erasure or tombstones, separately protected safety evidence, content-free post-expiry receipts, or recipient-view-preserving retrieval. Redis TTL and message-count settings bound only the cache, not PostgreSQL content retention.

Implementation must define a versioned storage contract for every supported communication type and reject publication or activation of an incomplete type. Persistence and retrieval must separate durable player history, finite safety evidence, transient delivery state, and content-free idempotency receipts. Lifecycle work must apply retention and erasure without allowing stale caches, retries, exports, backups, or case links to restore expired or unauthorized content.

Proof must cover durable acknowledgement before mail, account direct-message, and promised-scrollback acceptance; transient world speech without player-history persistence by default; finite evidence retention and access controls; content expiry while an idempotency receipt remains; same-identity/same-digest retry and conflicting-digest rejection; Redis loss and refill; service restart; and independent Game Session reconnect and Logging & Admin case behavior.

Privacy proof must exercise ordinary recipients, non-recipients, full-content observers, metadata-only observers, redacted or partial views, blocks or later access changes, export, erasure, tombstones, cache refill, and case evidence. No history or export path may return a richer view than the recipient originally received.

Select validation and runtime evidence according to [`validation and runtime proof`](../../developer-workflows/validation-and-runtime-proof.md); record actual execution results in PR/CI evidence or implementation-tracking documents, not in this ADR.

## Reversibility and Revisit Triggers

Storage schemas, lifecycle workers, retention values, and cache representations may evolve while preserving explicit type contracts, purpose separation, durable-before-ack promises, and original-view disclosure bounds. Revisit a communication type's history or evidence policy when a concrete player, safety, legal, or operator requirement justifies it; do not broaden retention merely because content is already technically available during delivery.

## Required Documentation Alignment

- [`design/architecture/microservices/social-groups-service/runtime-and-data.md`](../microservices/social-groups-service/runtime-and-data.md)
- [`design/architecture/microservices/social-groups-service/api-contracts.md`](../microservices/social-groups-service/api-contracts.md)
