# Social & Groups Service

## Overview

Provides chat, guild, and social networking features across games. Basic REST and gRPC APIs are implemented for guilds, friends, chat, and mail. Real-time WebSocket delivery is available.

### Responsibilities

- Own friend and block relationships, including request, acceptance, rejection, removal, and blocking lifecycle
- Own guild and group metadata, declared membership-subject type, membership and role policy, alliances, and social ACLs
- Resolve social audiences and store messaging and mail envelopes and history
- Apply Social-owned moderation enforcement and integrate moderation reporting with Logging & Admin
- Coordinate authorized guild-container and mail-attachment operations without owning items, currency, or containment

## Key Features

- Global and guild chat rooms
- Private messaging between players
- Asynchronous player-to-player mail
- Guild creation and membership management
- Entity-backed shared guild storage and a Social-owned alliance and ACL system
- Separate tenant-local relationships and genuinely tenant-free account-global relationships; accepted account friendships can appear in-game when enabled
- In-game social chat plus account-to-account direct messaging
- Presence indicators notify when friends come online
- Game creators can broadcast announcements and send out-of-game emails

### Presence Scope Note

The gameplay `WHO` command is intentionally a current-game-instance presence view, not a broad social browser. If FireMUD later adds cross-game friend indicators, shared-type realm presence, or platform-wide online-status discovery, that should be modeled as a Social & Groups presence surface or a separate social command family rather than by broadening `WHO` beyond its in-game instance scope.

## Current Scope Notes

- The current gameplay-connected communication slice is intentionally narrow in implementation: it proves room-local `say` delivery from Game Session through Game Logic into Social & Groups, including canonical sender/listener room speech, while establishing the broader shared communication model.
- Future communication work is expected to broaden this into a richer communication model that distinguishes:
  - the communication act/type (`say`, `whisper`, `tell`, `shout`, guild/system/game-defined variants),
  - the target or propagation scope (room, area, region, map, continent, guild/group, account-directed, and other configured channels),
  - recipient resolution owned by that target/scope,
  - and per-recipient presentation for ordinary listeners versus observer/interceptor roles.
- Those broader scope and routing semantics should be modeled explicitly rather than treating all verbs as cosmetic aliases of one generic room broadcast.
- In particular, in-world communication should usually target a room, area, or other scope object and let that scope resolve listeners, overhearers, spies, magical observers, and similar mechanics.
- Only world/gameplay communication is eligible for gameplay observation. Each published type declares a small closed set of allowed observer views and explicit safe metadata fields; partial content is added only for a concrete authored mechanic, not through a free-form observer DSL. Game Logic resolves authoritative topology, capabilities, senses, and mechanics into bounded candidate-specific views. Game Session delivers them, while Social applies social, moderation, and history constraints without inferring spatial observers or broadening disclosure.
- `WHISPER` interception requires an explicit authored mechanic; the current boolean/flag seam is only initial implementation. Gameplay `TELL` has no observer path by default. Missing, stale, contradictory, or oversized resolution fails without over-delivery.
- `SHOUT` remains unimplemented until a game or default profile defines its named bounded topology scope. It is not universally area-, region-, radius-, or map-scoped: one profile may define area-wide `SHOUT`, another map-wide. Large scopes obey operator fanout caps and use bounded/chunkable delivery with diagnostics and metrics rather than silent audience truncation. See [ADR 0137](../../decisions/adr-0137-closed-observer-views-and-profile-scoped-shout.md).
- World/gameplay communication enters Game Logic first so topology, gameplay perception, surveillance, magical listening, and similar mechanics can participate consistently. Game Logic produces a bounded resolved plan; Social & Groups applies relevant social audience, moderation, history, and durable social-delivery responsibilities; Game Session owns connected gameplay transport delivery.
- Account messaging, ordinary guild/group channels, mail, and browser social interactions enter Social & Groups directly after authentication, membership, privacy, and moderation checks. In-game commands may adapt to those APIs without turning private platform communication into a Game Logic action or exposing it to tenant-authored scripts. Gameplay `tell` may remain distinct when world rules apply, but its standard type has no observer path; interception requires a deliberately distinct published type and mechanic.
- Operator and platform-system communication enters through the service that owns the originating authorization and audit contract. See [ADR 0134](../../decisions/adr-0134-explicit-communication-classes-and-owner-delivery.md) for the canonical communication classes and owner handoffs.
- Each guild or group declares whether membership identifies accounts or `{playableStateNamespaceId, characterId}` characters. Account owns account identity and profile visibility, Game Session owns raw presence and transports, and Social owns only the relationship and group projection.
- Entity Management owns real guild containers, items, currency, and mail attachments. Social may own the guild ACL and container binding, but attached value uses an owner-controlled transfer or escrow rather than Social-local item or quantity records. See [ADR 0135](../../decisions/adr-0135-social-relationship-authority-and-entity-owned-value.md).

## Document Map

- [API Contracts](./api-contracts.md)
  - chat/guild/friends/mail API surfaces, delivery contracts, and proto/OpenAPI ownership.
- [Runtime and Data](./runtime-and-data.md)
  - social-data ownership, Redis/cache behavior, and real-time delivery invariants.
- [Operations](./operations.md)
  - readiness/liveness, moderation/delivery operational notes, and local verification guidance.
- [Configuration](./configuration.md)
  - environment variables, service discovery, TLS, and service-local configuration locations.

## Dependencies

- **Internal:**
  - Account Service for user identities
  - Game Session Service for raw presence and connected gameplay delivery
  - Entity Management Service for guild containers, items, and attachment escrow
  - Game Logic Service only when a declared world-specific communication or mail rule requires gameplay semantics
  - Logging & Admin Service consumes chat logs for moderation
- **External:** PostgreSQL for social data

## Related Documentation

- [System Architecture Overview](../../system-architecture-overview.md)
- [Multi-Tenancy](../../system-architecture-multi-tenancy.md)
- [Service Responsibility Matrix](../../service-responsibility-matrix.md)
- [User Journeys – Social Interaction](../../user-journeys-players.md#4-social-interaction)
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
