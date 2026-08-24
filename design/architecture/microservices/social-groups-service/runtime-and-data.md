# Social & Groups Service Runtime and Data

This document defines the Social & Groups Service runtime model, persistent data ownership, Redis role, and chat/voice delivery behavior.

## Implementation Status

The current chat path and policy read remain partial: migrations do not yet implement complete `chat_restriction` and `chat_restriction_revision` owner-local state, and focused proof does not cover owner command/idempotency, expiry and reordering, essential notices, owner-read failure behavior, or bounded appeal outcomes. This status does not change the target owner-local contract below.

The current live moderation seam is the synchronous `EvaluateModerationPolicy` read consumed by `SendMessage` at `CHAT_SEND`; this makes Logging & Admin a current chat hot-path dependency, and the path fails closed when required policy evidence is unavailable or stale. Owner-local restriction tables, commands, durable revisions, notices, and bounded appeal outcomes for `chat_mute`/`chat_ban` are target-only/partial and are not current persisted controls. Only the target owner-local enforcement model removes that routine dependency; until it is implemented, the synchronous read remains the current seam. Social & Groups owns only local communication consequences, while Logging & Admin remains the policy-input, case, evidence, and audit owner.

The target player-safe outcomes are distinct: `CHAT_MUTE_SEND_DENIED` denies sending while ordinary receipt remains available; `CHAT_BAN_PARTICIPATION_DENIED` denies ordinary participation, sending, and history while essential system and moderation notices remain deliverable. These codes describe the target local enforcement projection, not proof that the current read seam has converged.

## Architecture and Design Notes

- Uses typed social delivery state and transport handoffs for chat/mail; current chat delivery uses WebSocket channels, and Game Session owns final delivery to connected gameplay transports
- Stores Social-owned relationship, group, audience, mail-envelope, and applicable history state in PostgreSQL; guild and friend relationships remain persisted here
- Integrates with Logging & Admin for moderation evidence, policy intent, bounded appeal outcomes, and audit; target owner-local chat enforcement does not depend on that service for routine chat enforcement
- Chat profanity may generate a gRPC evidence/report call to Logging & Admin; the report does not itself create a `chat_mute` or `chat_ban`
- Guild creation and membership changes may participate in short synchronous saga workflows so other services remain consistent; see [Transaction Strategies](../../system-architecture-transactions.md)
- Tenant-local social records carry `tenantId`, while account-global relationship pairs are genuinely tenant-free; Redis key prefixes remain tenant-qualified where the record is tenant-local, as described in [Multi-Tenancy](../../system-architecture-multi-tenancy.md)
- Entity Management owns guild containers, items, inventory, currency, and mail attachments; Social stores ACLs and stable owner/escrow references, never independent value rows
- Tenant-qualified cross-service calls forward the `tenantId` so features remain isolated; genuinely account-global relationship calls use the tenant-free account-pair contract and must not fabricate a `tenantId`. See [Multi-Tenancy](../../system-architecture-multi-tenancy.md) for details
- External HTTP APIs consume the end-user Account JWT authorization context forwarded unchanged by Gateway and validate its role/tenant claims locally. Direct internal gRPC callers authenticate with the exact workload mTLS/service identity required by the method contract; any current bearer or delegation metadata remains authorization context and never substitutes for that caller identity. Inter-service transport follows the [Security Architecture](../../system-architecture-security.md).
- Utilizes the [Shared Libraries](../../system-architecture-shared-libraries.md) for DTO definitions, logging interceptors, and Micrometer metrics

## Owner-Local Communication Restrictions

**Target state:** Social & Groups is the sole enforcement owner for `chat_mute` and `chat_ban`. Logging & Admin owns policy intent, moderation cases, bounded appeals, and audit; the complete fixed-category and digest-bound command contract is [Moderation Policies](../logging-admin-service/moderation-policies.md). Routine communication does not synchronously call Logging & Admin.

The target local projection is indexed by exact subject and normalized tenant/realm/channel scope, category, monotonic owner revision/enforcement epoch, effective/expiry times, source case/request identity, payload digest, and player-safe notice. Every create, extension, expiry, removal, correction, or modified/overturned appeal outcome is a new owner command; an upheld appeal creates no owner command. Social & Groups atomically commits the revision, current projection, and idempotent result; same identity/same digest replays, conflicting digest is rejected, and delayed/reordered commands cannot erase newer state or resurrect older state. Missing or unreadable required local state fails closed.

At send, participation, and history boundaries, `chat_mute` blocks sending while ordinary receipt remains available and returns the safe outcome `CHAT_MUTE_SEND_DENIED`. `chat_ban` blocks ordinary participation, sending, and history access and returns `CHAT_BAN_PARTICIPATION_DENIED`, while essential system and moderation notices remain deliverable so the player can receive the restriction and appeal/support guidance. When both categories deny the same boundary, Social & Groups returns only the broader `CHAT_BAN_PARTICIPATION_DENIED` outcome and does not expose the lower-priority mute; both records remain independently active. Removing one category or scope never changes another. Appeal filing itself does not change enforcement; a modified or overturned decision is a newer command and must not erase a later unrelated restriction.

## Data Model

- Type-specific Social history tables/rows persist only communication classes that promise player-visible history; live world speech is not a permanent archive by default
- Separate finite protected safety-evidence records and content-free idempotency receipts are not player history and have their own retention/access lifecycle
- `guild` and `guild_member` tables store group ownership and roles against one declared membership subject type: `ACCOUNT` or `{tenantId, playableStateNamespaceId, characterId}`
- `friend_links` stores tenant-local relationships with explicit lifecycle and `tenantId`; `account_friend_links` stores genuinely tenant-free account-pair relationships with request/accept/reject/remove state; blocks are explicit directional records and take precedence for interaction/visibility
- Games can mirror these links in their UI when the feature is enabled; a projection never becomes relationship authority
- `mail_message` (or its type-specific successor) stores Social-owned mail envelopes, delivery/history state, and stable Entity attachment/escrow references, not item/currency value
- Social may retain a typed binding from a guild to an Entity-owned container, but it does not create `itemName + quantity`, currency, inventory, or attachment rows
- Target `chat_restriction` and `chat_restriction_revision` tables store owner-local `chat_mute`/`chat_ban` state and immutable revisions.
- `faction` and `faction_standing` tables are defined in the [Automation & Scripting Service](../automation-scripting-service/README.md) to track player reputation; integration with this service for NPC behavior is available

## Redis Role and Prefixes

- **Coordination Redis**
  - Social & Groups does not own or modify Coordination Redis prefixes
  - It does not touch `tick:*`, `timer:*`, `retry:*`, `session:*`, or automation coordination keys; gameplay coordination and Automation-owned scheduler/timer coordination remain the responsibility of the Game Session and Automation & Scripting services as described in [Redis Architecture](../../system-architecture-redis.md)
- **Cache/Rate-Limit Redis**
  - Uses Cache/Rate-Limit Redis for recent history projections, fanout buffers, delivery queues, and rate limits under prefixes such as:
    - `chat:say:<tenantId>:<senderAccountId>`
    - `chat:whisper:<tenantId>:<playableStateNamespaceId>:<characterId>`
    - `chat:tell:<tenantId>:<conversationId>`
    - `chat:guild:<tenantId>:<guildId>`
    - `chat:account:<tenantId>:<accountId>`
    - `chat:city:<tenantId>:<cityId>`
  - The target gameplay `WHISPER` projection is scoped to the exact recipient `{tenantId, playableStateNamespaceId, characterId}` and remains distinct from account-scoped `chat:account:<tenantId>:<accountId>`. The live Social request/DTO/entity/schema and key are still account-keyed as `chat:whisper:<tenantId>:<accountId>`; that is implementation drift and must not serve history/refill across same-account characters or playable-state namespaces. The required cross-service migration and original-view proof remain gaps; see the [canonical Redis contract](../../system-architecture-redis-cache-reference.md#canonical-social--groups-chatwhisper-class-b-contract).
  - These structures are bounded and rebuildable projections. They may accelerate reads or transient fanout but are never authoritative for promised player history, safety evidence, erasure, or retry receipts; loss cannot change the declared retention or disclosure contract. Redis loss or TTL expiry may produce only transient projection gaps; for a communication type that promises durable player history, reads use or fall back to authoritative Social/PostgreSQL history, so projection loss cannot create a durable-history entitlement gap.
  - The current `chat:say` key is sender-account scoped and must not be presented as a recipient/history scope. Any future gameplay-SAY projection must publish its exact recipient/view scope and original-view contract rather than infer it from a bare character ID.
  - `chat:city` is a current legacy TTL projection, not durable player history. Its presence does not implement or prove the target profile-scoped `SHOUT` contract. No `SHOUT` Redis/reset family exists until a selected profile publishes its type/storage/topology contract.
  - Cache metrics for these prefixes should follow the `chat:*` recommendations in `system-architecture-redis-cache.md` (for example `cache.chat_hits_total` / `cache.chat_misses_total` with chat-type labels) so hit/miss behavior and key counts are observable
  - Concrete TTL and max-message budgets for these prefixes are documented in [Configuration](./configuration.md) and must remain aligned with the type-specific retention contract and size/complexity envelopes described in `system-architecture-redis-cache.md`. The current `chat:whisper` settings remain `FIREMUD_CHAT_WHISPERS_TTL_SECONDS` (default `7200`) and `FIREMUD_CHAT_WHISPERS_MAX_MESSAGES` (default `50`) while the recipient-scope migration remains pending.
  - New chat/cache prefixes or changes to Redis usage should be validated against the [Redis Design Checklist](../../system-architecture-redis-design-checklist.md) so they remain aligned with the global key catalog and SLOs, and should be added to the [canonical cache/rate-limit prefix catalog](../../system-architecture-redis-cache-reference.md#cache-rate-limit-key-catalog).

If you change Redis usage for this service, you must read and apply:

- [Redis Architecture](../../system-architecture-redis.md)
- [Redis Cache & Rate Limiting](../../system-architecture-redis-cache.md)
- [Redis Operations & Migrations](../../system-architecture-redis-operations.md)

## Chat and Voice Delivery

- Social & Groups accepts direct Social-channel requests and bounded gameplay communication plans. Gameplay plans carry explicit type/version, target/scope, authorized recipient views, and stable identity from Game Logic; Social applies membership/audience, moderation, history, retention, and delivery-state rules without re-deriving topology or observer candidates.
- Game Session owns authenticated gameplay connections and final connected-player delivery. Social may publish delivery state or a cross-pod notification, but a history commit or fanout enqueue is not end-user delivery acknowledgement.
- Every communication type declares its storage class. Mail, account direct messages, and channels promising scrollback commit durable player history before durable acceptance. World `SAY`, nearby `WHISPER`, gameplay `TELL`, profile-defined `SHOUT`, and game-defined equivalents are live by default and create no permanent player history unless explicitly declared.
- A finite protected moderation/safety-evidence class is separate from player history and Logging & Admin case/audit. Content-free idempotency receipts may outlive content expiry but cannot reconstruct content or expand an audience. Retention, export, erasure, redaction, tombstones, and authorized readers are type-specific.
- Redis lists/buffers are rebuildable Cache/Rate-Limit Redis projections with bounded TTLs and message caps; they are not an alternate archive. Redis loss cannot change durable retention, erasure, idempotency, or original recipient disclosure. Stored history and later exports preserve each recipient's original authorized view.
- Voice chat is available as an optional feature built on top of a lightweight WebRTC gateway; the gateway establishes peer-to-peer connections or relays media when direct communication is not possible, and Social records only its declared finite moderation evidence.
- Active [ADR 0149](../../decisions/adr-0149-communication-type-specific-history-and-retention.md) records this storage/acknowledgement boundary, mapped from reviewed archive record `archive-ADR-0136`; active [ADR 0147](../../decisions/adr-0147-explicit-communication-classes-and-owner-delivery.md) records the owner handoff, mapped from reviewed archive record `archive-ADR-0134`.
