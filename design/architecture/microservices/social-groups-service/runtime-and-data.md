# Social & Groups Service Runtime and Data

This document defines the Social & Groups Service runtime model, persistent data ownership, Redis role, and chat/voice delivery behavior.

## Architecture and Design Notes

- Uses WebSocket channels for chat delivery
- Stores guild and friend relationships in PostgreSQL
- Integrates with Logging & Admin for moderation evidence, policy intent, and owner-command outcomes; it does not depend on that service for routine chat enforcement
- Chat profanity may generate a gRPC evidence/report call to Logging & Admin; the report does not itself create a `chat_mute` or `chat_ban`
- Guild creation and membership changes may participate in short synchronous saga workflows so other services remain consistent; see [Transaction Strategies](../../system-architecture-transactions.md)
- Chat history and guild data are stored with a `tenantId` so conversations are isolated per game; Redis list keys also include this prefix, as described in [Multi-Tenancy](../../system-architecture-multi-tenancy.md)
- Cross-service calls always forward the `tenantId` so features remain isolated; see [Multi-Tenancy](../../system-architecture-multi-tenancy.md) for details
- APIs require authenticated JWTs from the Account Service for role checks; these tokens are exchanged only between services, and all inter-service communication is encrypted via mutual TLS following the [Security Architecture](../../system-architecture-security.md)
- Utilizes the [Shared Libraries](../../system-architecture-shared-libraries.md) for DTO definitions, logging interceptors, and Micrometer metrics

## Owner-Local Communication Restrictions

Social & Groups is the sole enforcement owner for `chat_mute` and `chat_ban`. Logging & Admin owns policy intent, moderation cases, bounded appeals, and audit; the complete fixed-category and digest-bound command contract is [Moderation Policies](../logging-admin-service/moderation-policies.md). Routine communication does not synchronously call Logging & Admin.

The target local projection is indexed by exact subject and normalized tenant/realm/channel scope, category, monotonic owner revision/enforcement epoch, effective/expiry times, source case/request identity, payload digest, and player-safe notice. Every create, extension, expiry, removal, correction, or appeal outcome is a new owner command. Social & Groups atomically commits the revision, current projection, and idempotent result; same identity/same digest replays, conflicting digest is rejected, and delayed/reordered commands cannot erase newer state or resurrect older state. Missing or unreadable required local state fails closed.

At send, participation, and history boundaries, `chat_mute` blocks sending while ordinary receipt remains available. `chat_ban` blocks ordinary participation, sending, and history access, while essential system and moderation notices remain deliverable so the player can receive the restriction and appeal/support guidance. A broader restriction may deny a request without deleting narrower scope records; removing one category or scope never changes another. Appeal filing itself does not change enforcement; a modified or overturned decision is a newer command and must not erase a later unrelated restriction.

The current chat path and policy read remain partial and do not prove the durable local restriction table, owner command/idempotency, expiry/reordering, essential notices, owner-read failure behavior, or bounded appeal outcome handling.

## Data Model

- `chat_message` table persists guild and private messages
- `guild` and `guild_member` tables store group ownership and membership roles
- `friend_links` table stores per-game friendships scoped by `tenantId`
- `account_friend_links` table stores account-to-account friendships shared across games
- Games can mirror these links in their UI when the feature is enabled
- `mail_message` table stores asynchronous player mail
- Target `chat_restriction` and `chat_restriction_revision` tables store owner-local `chat_mute`/`chat_ban` state and immutable revisions; current migrations do not yet implement this complete model.
- `faction` and `faction_standing` tables are defined in the [Automation & Scripting Service](../automation-scripting-service/README.md) to track player reputation; integration with this service for NPC behavior is available

## Redis Role and Prefixes

- **Coordination Redis**
  - Social & Groups does not own or modify Coordination Redis prefixes
  - It does not touch `tick:*`, `timer:*`, `retry:*`, `session:*`, or automation coordination keys; gameplay coordination and Automation-owned scheduler/timer coordination remain the responsibility of the Game Session and Automation & Scripting services as described in [Redis Architecture](../../system-architecture-redis.md)
- **Cache/Rate-Limit Redis**
  - Uses Cache/Rate-Limit Redis for chat history buffers and similar transient social aggregates under prefixes such as:
    - `chat:say:<tenantId>:<characterId>`
    - `chat:whisper:<tenantId>:<accountId>`
    - `chat:tell:<tenantId>:<conversationId>`
    - `chat:guild:<tenantId>:<guildId>`
    - `chat:account:<tenantId>:<accountId>`
    - `chat:city:<tenantId>:<cityId>`
  - These lists mirror persisted history in PostgreSQL for quick retrieval and are subject to TTL and max-message limits configured via `FIREMUD_CHAT_*` variables, following the cache key and TTL guidance in [Redis Cache & Rate Limiting](../../system-architecture-redis-cache.md)
  - They are treated as best-effort TTL-only caches: correctness comes from PostgreSQL, while Redis provides short-lived history windows bounded by the configured TTLs and message counts, consistent with the `chat:*` entries in the cache/rate-limit key catalog
  - Cache metrics for these prefixes should follow the `chat:*` recommendations in `system-architecture-redis-cache.md` (for example `cache.chat_hits_total` / `cache.chat_misses_total` with chat-type labels) so hit/miss behavior and key counts are observable
  - Concrete TTL and max-message budgets for these prefixes are documented in [Configuration](./configuration.md) and must remain aligned with the size/complexity envelopes described in `system-architecture-redis-cache.md`
- New chat/cache prefixes or changes to Redis usage should be validated against the [Redis Design Checklist](../../system-architecture-redis-design-checklist.md) so they remain aligned with the global key catalog and SLOs, and should be added to the cache/rate-limit Redis key catalog maintained in the Redis cache design docs

If you change Redis usage for this service, you must read and apply:

- [Redis Architecture](../../system-architecture-redis.md)
- [Redis Cache & Rate Limiting](../../system-architecture-redis-cache.md)
- [Redis Operations & Migrations](../../system-architecture-redis-operations.md)

## Chat and Voice Delivery

- Messages are cached in Redis lists and delivered to WebSocket channels through the Spring Cloud Gateway
- Guild and direct messages share a common persistence model for history
- The long-term gameplay communication model should treat cached history and delivery metadata as outputs of a configurable communication system rather than as `SAY`-specific special cases. In-world communication should arrive with explicit target/scope and resolved-recipient metadata so persisted history can distinguish:
  - the communication type,
  - the target object or propagation scope,
  - ordinary recipients,
  - observer/interceptor recipients such as spies or eavesdroppers,
  - and the presentation form each recipient saw when that matters for audit or replay.
- Recent history is retained in Redis with type-specific TTLs and message caps:
  - Says: 2 hours or 50 messages per character
  - Tells: 48 hours or 50 messages per character
  - Guild/City chat: 48 hours or 50 messages per guild or city
  - Account messages: 48 hours or 50 messages
- Older messages remain in PostgreSQL for moderation and historical logs
- Voice chat is available as an optional feature built on top of a lightweight WebRTC gateway
- The gateway establishes peer-to-peer connections between players and relays media streams when direct communication is not possible
- The service records voice activity for moderation
