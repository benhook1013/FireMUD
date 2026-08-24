# Social & Groups Service

## Overview

Provides relationships, groups, audiences, social-channel communication, mail, applicable history, and social delivery state across games. Basic REST and gRPC APIs are implemented for guilds, friends, chat, and mail; the player-facing Gateway `/api/social/friends` edge route is currently gated until directional block-state modeling and revalidation exist, while the service-local friend implementation remains available for direct-service and future non-edge use. REST `/chat` is implemented, but its Gateway edge route is currently gated until authenticated-sender binding and `mutationDigest/v1` conflict proof exist. Its current caller-binding, block-state, and moderation limitations are documented in [API Contracts](./api-contracts.md). Real-time delivery is available through the transport owner; Social & Groups does not own connected gameplay transports.

## Implementation Status

The current friend-presence implementation reads tenant-scoped reciprocal-active links but has no directional block-state model or block revalidation. The player-facing Gateway `/api/social/friends` route is therefore gated; direct service-local friend reads retain the current reciprocal-active filtering for direct-service and future non-edge use and do not claim the target player-facing safety boundary. The current live moderation seam is `SendMessage` consuming the synchronous `EvaluateModerationPolicy` read at `CHAT_SEND`; it fails closed when required policy evidence is unavailable or stale, but only after a cache/replay miss. The externally reachable Gateway `/api/social/chat` route is currently gated until authenticated-sender binding and `mutationDigest/v1` conflict proof exist. The direct REST `/chat` controller currently checks the request's `senderAccountId` through the Social access guard (current-account or tenant access), and chat replay is keyed only by `{tenantId,effectId}` with no authenticated-caller or canonical full-request-digest binding. Target owner-local `chat_mute`/`chat_ban` restriction tables, commands, durable revisions, notices, and bounded appeal outcomes are target-only/partial and are not current persisted controls. Every REST and gRPC chat write must eventually traverse that same owner-local restriction boundary before persistence/publication. The target owner-local enforcement model avoids making Logging & Admin a routine chat hot-path dependency; until that target model is implemented, the current `CHAT_SEND` path depends synchronously on the `EvaluateModerationPolicy` read. Logging & Admin remains the policy-input, case, evidence, and audit owner.

Target player-safe outcomes remain distinct: `CHAT_MUTE_SEND_DENIED` denies sending while ordinary receipt remains available; `CHAT_BAN_PARTICIPATION_DENIED` denies ordinary participation, sending, and history while essential system and moderation notices remain deliverable. These target codes do not claim that the current read seam has converged on durable owner-local enforcement.

## Gameplay Proof Status

Current gameplay-connected proof covers `SAY`, `WHISPER`, and `TELL` delivery from Game Session through Game Logic into the Social & Groups stub, including canonical actor and live-recipient views. Target typed memberships, Entity-owned value/attachments, and communication-type-specific storage/history/acknowledgement are not complete runtime proof.

### Responsibilities

- Apply social membership, privacy, moderation, history, and delivery-state rules to authorized communication plans
- Deliver social-channel and mail notifications through typed delivery state; Game Session remains the connected gameplay transport owner
- Manage guild/group definitions, declared membership subject type, roles, ownership, and alliances
- Maintain account-global and tenant-local friend/block relationships and bounded social audiences
- Store each supported communication type according to its declared history/retention/acknowledgement contract; target state is to enforce owner-local `chat_mute`/`chat_ban` restrictions, while the current `CHAT_SEND` seam synchronously consumes `EvaluateModerationPolicy`; profanity events remain evidence/report input to Logging & Admin
- Keep Entity-owned containers, items, currency, inventory, and mail attachments outside Social authority; retain ACLs and stable owner references only

## Key Features

- Global and guild chat rooms
- Private messaging between players
- Asynchronous player-to-player mail
- Guild creation and membership management
- Guild ACLs and typed bindings to Entity-owned containers, plus alliance metadata
- Friend/block relationships scoped either to tenant-free account pairs or distinct tenant-local records; account-level friends may appear in-game when enabled
- In-game social chat plus account-to-account direct messaging
- Presence indicators notify when friends come online
- Game creators can broadcast announcements and send out-of-game emails
- **Target state:** Enforce fixed-category communication restrictions at send, participation, history, and essential-notice boundaries from owner-local state without making Logging & Admin a routine hot-path dependency; the current `CHAT_SEND` seam remains the synchronous `EvaluateModerationPolicy` read described above.

### Presence Scope Note

The gameplay `WHO` command is intentionally a current-game-instance presence view, not a broad social browser. If FireMUD later adds cross-game friend indicators, shared-type realm presence, or platform-wide online-status discovery, that should be modeled as a Social & Groups presence surface or a separate social command family rather than by broadening `WHO` beyond its in-game instance scope.

## Current Scope Notes

- World/gameplay communication enters Game Logic when topology, perception, abilities, effects, authored interception, or other gameplay state determines its meaning. Social & Groups then applies social audience, moderation, history, and delivery-state rules; Game Session owns final connected gameplay transport delivery.
- Account messaging, ordinary guild/group channels, browser social interactions, and ordinary account or social mail enter Social & Groups directly after authentication and applicable membership, privacy, and moderation checks. An in-game adapter does not turn those operations into Game Logic actions or expose private content to tenant-authored scripts; world-specific mail with explicit gameplay semantics follows the gameplay communication class in [ADR 0147](../../decisions/adr-0147-explicit-communication-classes-and-owner-delivery.md), while [ADR 0148](../../decisions/adr-0148-social-relationship-authority-and-entity-owned-value.md) remains the authority for social relationships and Entity-owned value/attachments.
- Target gameplay communication uses explicit type/version and target/scope metadata. Candidate-specific observer views are selected from a closed type-declared vocabulary; Social does not infer spatial observers or broaden a Game Logic plan. Gameplay `TELL` is non-observable by default. The target declarations and mechanics remain deferred as recorded in [Implementation Status](#implementation-status).
- `SHOUT` remains deferred until a selected game profile publishes a named bounded topology scope and fanout limits. No area/region policy or platform-global scope is implied.
- Active [ADR 0147](../../decisions/adr-0147-explicit-communication-classes-and-owner-delivery.md), [ADR 0148](../../decisions/adr-0148-social-relationship-authority-and-entity-owned-value.md), [ADR 0149](../../decisions/adr-0149-communication-type-specific-history-and-retention.md), and [ADR 0150](../../decisions/adr-0150-closed-observer-views-and-profile-scoped-shout.md) record the reviewed outcomes mapped from archive ADRs 0134–0137. The former room-local `SOCIAL-01` staging assumption is superseded provenance, not a competing owner contract.

## Document Map

- [API Contracts](./api-contracts.md)
  - chat/guild/friends/mail API surfaces, delivery contracts, and local restriction consequences.
- [Runtime and Data](./runtime-and-data.md)
  - social-data ownership, owner-local restriction state, Redis/cache behavior, and real-time delivery invariants.
- [Operations](./operations.md)
  - readiness/liveness, moderation/delivery operational notes, and local verification guidance.
- [Configuration](./configuration.md)
  - environment variables, service discovery, TLS, and service-local configuration locations.

## Dependencies

- **Internal:**
  - Account Service for identity, profile-visibility policy, and account security facts
  - Game Session Service for raw gameplay presence and connected transport delivery
  - Entity Management Service for containers, items, currency, inventory, and mail attachment/escrow operations
  - Logging & Admin Service for moderation intent, cases, bounded appeals/evidence, and audit
- **External:** PostgreSQL for social state; Cache/Rate-Limit Redis for rebuildable fanout/history projections, delivery queues, and rate limits

## Related Documentation

- [System Architecture Overview](../../system-architecture-overview.md)
- [Multi-Tenancy](../../system-architecture-multi-tenancy.md)
- [Service Responsibility Matrix](../../service-responsibility-matrix.md)
- [User Journeys – Social Interaction](../../../product/user-journeys/players.md#5-social-interaction--safety)
- [Redis Architecture](../../system-architecture-redis.md)
- [gRPC API Style & Versioning Guidelines](../../system-architecture-grpc.md)
- [Shared Libraries Overview](../../system-architecture-shared-libraries.md)
- [Database Migrations](../../system-architecture-database-migrations.md)
- [Backup & Disaster Recovery](../../system-architecture-backup-recovery.md)
- [Logging & Monitoring](../../system-architecture-logging-monitoring.md)
- [Authentication & Authorization](../../system-architecture-authentication.md)
- [Security Architecture](../../system-architecture-security.md)
- [Testing Strategy](../../system-architecture-testing.md)
- [CI/CD Pipeline](../../system-architecture-cicd.md)
- [System Architecture Diagram](../../system-architecture-diagram.md)
- [System Context Diagram](../../system-context-diagram.md)
