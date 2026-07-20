# Social & Groups Service Runtime and Data

This document defines the Social & Groups Service runtime model, persistent data ownership, Redis role, and chat/voice delivery behavior.

## Architecture and Design Notes

- Uses WebSocket channels for chat delivery
- Stores relationship lifecycle, guild/group metadata, typed membership, roles, alliances, social ACLs, and message/mail envelopes in PostgreSQL
- Integrates with the Logging & Admin Service for moderation events
- Chat profanity triggers a gRPC call to the Logging & Admin Service to record a moderation report
- Guild creation and membership changes may participate in short synchronous saga workflows so other services remain consistent; see [Transaction Strategies](../../system-architecture-transactions.md)
- Tenant-local relationship, group, chat, and mail records carry `tenantId` so game-local state remains isolated; Redis list keys also include the applicable scope, as described in [Multi-Tenancy](../../system-architecture-multi-tenancy.md)
- Genuinely account-global relationship records are tenant-free canonical account pairs. APIs and calls include a tenant only when operating on tenant-local state; they must not manufacture a tenant scope for a global friendship.
- APIs require authenticated JWTs from the Account Service for role checks; these tokens are exchanged only between services, and all inter-service communication is encrypted via mutual TLS following the [Security Architecture](../../system-architecture-security.md)
- Utilizes the [Shared Libraries](../../system-architecture-shared-libraries.md) for DTO definitions, logging interceptors, and Micrometer metrics

## Data Model

- Communication persistence follows a versioned type-specific storage contract. Mail, account direct messages, and channels promising scrollback persist player-visible history; world speech is live by default and does not automatically enter a permanent player archive.
- `guild` and `guild_member` tables store group ownership, one declared membership-subject type, subject identity, and membership roles. A group uses either account subjects or `{playableStateNamespaceId, characterId}` subjects and does not mix them implicitly.
- Tenant-qualified relationship records store game-local friend and block lifecycle independently from global relationships.
- Tenant-free account-pair records store account-global request, acceptance, rejection, removal, and directional block state. Ordinary users control consensual transitions; operator authority is limited to explicit moderation and repair and cannot fabricate friendship.
- Games can mirror these links in their UI when the feature is enabled
- `mail_message` stores asynchronous mail envelopes, content references, and delivery lifecycle. Social owns no attached item or currency value.
- A Social-owned guild ACL may bind a guild to an Entity-owned container. Deposits, withdrawals, and mail attachments use owner-controlled transfer or escrow; Social stores stable references and lifecycle state, never independent `itemName + quantity` rows.
- Account owns identity, account status, and profile-visibility policy. Game Session owns raw current/recent presence and connected transports. Social reads those authorities to construct a bounded social projection.
- `faction` and `faction_standing` tables are defined in the [Automation & Scripting Service](../automation-scripting-service/README.md) to track player reputation; integration with this service for NPC behavior is available

### Communication Storage Classes

Every communication type independently declares whether it has durable player-visible history, finite protected moderation or safety evidence, transient live delivery only, and a content-free idempotency receipt. The type also declares retention and expiry, authorized access, export treatment, erasure or tombstone behavior, and which durable state must commit before acknowledgement.

- Mail, account direct messages, and channels that promise scrollback commit their durable history before the corresponding acceptance acknowledgement.
- World speech is live communication by default. `SAY`, nearby `WHISPER`, topology-aware `SHOUT`, gameplay `TELL`, and game-defined equivalents have no permanent player-visible archive unless their type explicitly promises bounded history.
- A world-speech type may retain a separately protected, finite safety-evidence record for reports or moderation. That record has its own purpose, access roles, retention, case-linkage, and erasure or legal-hold contract and is not exposed through player history.
- A content-free idempotency receipt may outlive expired content when duplicate prevention requires it. It retains only operation identity, equality proof, terminal acknowledgement class, and bounded lifecycle metadata; it cannot reconstruct content or audience.
- Logging & Admin case and audit history and Game Session reconnect context are separate records with separate authorities. Neither is an alternate Social player-history store.
- Player history, cache refill, retry, export, and later rendering preserve the recipient's original authorized semantic view. A metadata-only or redacted observer cannot retrieve complete content later.

Durable acceptance, safety-evidence capture, semantic admission, transport attempt, and end-user delivery are distinct outcomes unless a communication type explicitly binds them. See [ADR 0136](../../decisions/adr-0136-communication-type-specific-history-and-retention.md).

### Implementation Alignment

The current schema is not yet aligned with [ADR 0135](../../decisions/adr-0135-social-relationship-authority-and-entity-owned-value.md). `account_friend_links` still includes `tenant_id`, guild membership is account-only, and `guild_storage_items` persists `item_name` and `quantity` in Social. The current friend path also does not prove the complete bilateral lifecycle or operator limits. These are implementation and proof gaps, not alternate supported contracts.

The current `chat_message` path is also not aligned with [ADR 0136](../../decisions/adr-0136-communication-type-specific-history-and-retention.md). It applies blanket PostgreSQL persistence without proving type-specific expiry, player-history reads, export, deletion or tombstones, separately protected finite safety evidence, content-free post-expiry receipts, or retrieval of the exact recipient-authorized view. The configured Redis TTLs bound only cache entries and do not bound PostgreSQL retention.

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
  - These lists cache recent authorized projections or transient delivery state and are subject to TTL and max-message limits configured via `FIREMUD_CHAT_*` variables, following the cache key and TTL guidance in [Redis Cache & Rate Limiting](../../system-architecture-redis-cache.md)
  - They are best-effort TTL-only caches or fanout buffers. PostgreSQL is authoritative only for the durable player history, protected evidence, or idempotency records declared by the communication type; Redis loss must not change those contracts.
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
- Recent cache projections are retained in Redis with type-specific TTLs and message caps:
  - Says: 2 hours or 50 messages per character
  - Tells: 48 hours or 50 messages per character
  - Guild/City chat: 48 hours or 50 messages per guild or city
  - Account messages: 48 hours or 50 messages
- PostgreSQL retains content only under the communication type's declared player-history or finite safety-evidence contract. It does not retain every older world-speech message indefinitely by default.
- Voice chat is available as an optional feature built on top of a lightweight WebRTC gateway
- The gateway establishes peer-to-peer connections between players and relays media streams when direct communication is not possible
- The service records voice activity for moderation
