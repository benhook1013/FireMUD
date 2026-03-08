# Microservices Responsibility Matrix

Checkmarks in this table indicate **participation** in a workflow. Rows prefixed with `Authoritative owner:` identify the single service that owns invariant enforcement or policy-of-record for that function.

| Function | Game Design Service | World Management Service | Account Service | Game Session Service | Entity Management Service | Game Logic Service | Automation & Scripting Service | Social & Groups Service | Logging & Admin Service | TCP Proxy Service | Spring Cloud Gateway |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Game configuration authoring | ✔ | | | | | | | | | | |
| Custom in-game scripting authoring | ✔ | | | | | | | | | | |
| Game version publishing | ✔ | | | | | | | | | | |
| Design-time feature flag definitions | ✔ | | | | | | | | | | |
| Room and zone editing | ✔ | | | | | | | | | | |
| World map region layout | | ✔ | | | | | | | | | |
| Room topology and static metadata (descriptions, flags, ambient properties) | | ✔ | | | | | | | | | |
| Room dynamic world state (persistent environment flags, doors, hazards) | | ✔ | | | | | | | | | |
| Room occupancy (entity locations in rooms) | | ✔ | | | | | | | | | |
| Navmesh and pathfinding metadata (storage/publishing) | | ✔ | | | | | | | | | |
| Pathfinding and movement route computation (algorithms) | | | | | | ✔ | | | | | |
| Account authentication, credential verification, and JWT issuance (JWKS) | | | ✔ | | | | | | | | |
| Account-related email (verification, password reset, security alerts, subscription/billing notifications) | | | ✔ | | | | | | | | |
| Operational and moderation notifications (alerts, moderation actions, admin digests) | | | | | | | | | ✔ | | |
| Payment and subscriptions | | | ✔ | | | | | | | | |
| Account-security bans (`account_security_ban`) policy + revocation authority | | | ✔ | | | | | | | | |
| Gameplay-ban policy definition (`gameplay_ban`) | | | | | | | | | ✔ | | |
| Chat mute/chat-ban policy definition (`chat_mute`, `chat_ban`) | | | | | | | | | ✔ | | |
| Account security policy (password rules, lockout, MFA requirements) | | | ✔ | | | | | | | | |
| Gameplay login command handling and session binding (Redis) | | | | ✔ | | | | | | | |
| Login throttling, lockout, password reset, and email verification | | | ✔ | | | | | | | | |
| WebSocket transport connection lifecycle (upgrade, routing, DMZ edges) | | | | | | | | | | ✔ | ✔ |
| Gameplay session lifecycle (login, resume, takeover) | | | | ✔ | | | | | | | |
| Reconnection handling (resume gameplay) | | | | ✔ | | | | | | | |
| Command queuing and dispatch | | | | ✔ | | | | | | | |
| Session state storage (volatile, Redis gameplay bindings) | | | | ✔ | | | | | | | |
| Authoritative owner: Coordination Redis gameplay sessions (`session:game:*`) | | | | ✔ | | | | | | | |
| Authoritative owner: Coordination Redis gameplay coordination keys (`tick:*`, `timer:*`, `retry:*`, `tick-executor-lease:*`) | | | | ✔ | | | | | | | |
| Authoritative owner: Coordination Redis auth sessions (`session:auth:*`) | | | ✔ | | | | | | | | |
| Authoritative owner: Coordination Redis automation tick keyspace (`automation:tick:*`) | | | | | | | ✔ | | | | |
| Tick-region lease ownership and executor coordination (`<tenantId, regionId>`) | | | | ✔ | | | | | | | |
| Gameplay WebSocket route definition and routing (`/ws/game/**` canonical route) | | | | | | | | | | | ✔ |
| Game version activation at runtime | | | | ✔ | | | | | | | |
| Replacement-instance compatibility preflight (`ValidateInstanceCutoverCompatibility`) | ✔ | ✔ | | ✔ | ✔ | | ✔ | | ✔ | | |
| Authoritative owner: `versionStateEpoch` CAS enforcement | ✔ | | | | | | | | | | |
| Version-state CAS APIs ownership/invocation for activation/rollback (`versionStateEpoch`) | ✔ | | | ✔ | | | | | ✔ | | |
| Runtime feature flag overrides | | | | ✔ | | | | | | | |
| Tick & coordination health metrics (per region) | | | | ✔ | | | | | | | |
| Entity definition and persistence | | | | | ✔ | | | | | | |
| NPC state, inventory, and stats | | | | | ✔ | | | | | | |
| Player inventory and stats | | | | | ✔ | | | | | | |
| Item definitions and crafting data | | | | | ✔ | | | | | | |
| Game mechanics and combat resolution | | | | | | ✔ | | | | | |
| Command parsing and alias resolution | | | | | | ✔ | | | | | |
| Action execution (movement, attack, etc.) | | | | | | ✔ | | | | | |
| Progression logic (XP, levels, effects) | | | | | | ✔ | | | | | |
| Weather and ambient state persistence (weather, time-of-day, ambient modifiers) | | ✔ | | | | | | | | | |
| Environmental effects computation (weather, hazards, modifiers) | | | | | | ✔ | | | | | |
| Economy logic (trading, shops, pricing) | | | | | | ✔ | | | | | |
| AI-driven actions and behaviors | | | | | | | ✔ | | | | |
| Triggered script execution | | | | | | | ✔ | | | | |
| Redis-backed automation tick coordination (`automation:tick:*` keys) | | | | | | | ✔ | | | | |
| Coordination Redis participation via shared helpers (locks, automation tick prefixes) | | | | ✔ | ✔ | | ✔ | | | | |
| Cache/Rate-Limit Redis usage (caches, quotas, rate limiting) | | ✔ | | | ✔ | | ✔ | ✔ | | ✔ | ✔ |
| Chat and private messaging | | | | | | | | ✔ | | | |
| Guilds and group discovery | | | | | | | | ✔ | | | |
| Social network graph (friends/blocks/etc.) | | | | | | | | ✔ | | | |
| Centralized observability dashboards and moderation analytics (logs/metrics/traces) | | | | | | | | | ✔ | | |
| Admin panel and feature flag toggling | | | | | | | | | ✔ | | |
| Game moderation tools | | | | | | | | | ✔ | | |
| Game moderation policy definition | | | | | | | | | ✔ | | |
| Moderation policy propagation contract (versioning, invalidation, and audit context) | | | | ✔ | | | | ✔ | ✔ | | |
| Subscription entitlements and plan-driven quota values (`GetTenantEntitlements`) | | | ✔ | | | | | | | | |
| Operator quota overrides, auditing, and dashboards (overlay on entitlements) | | | | | | | | | ✔ | | |
| Enforcement of gameplay bans at login/command level | | | | ✔ | | | | | | | |
| Enforcement of chat mutes/bans at message send time | | | | | | | | ✔ | | | |
| Authoritative owner: gameplay-ban enforcement | | | | ✔ | | | | | | | |
| Authoritative owner: chat mute/chat-ban enforcement | | | | | | | | ✔ | | | |
| Movement/location write contract orchestration (effect identity, order, and replay safety) | | ✔ | | ✔ | ✔ | ✔ | | | | | |
| Instance termination orchestration (`PREPARING/ACTIVE/TERMINATING/TERMINATED`) and cross-service cleanup | | ✔ | | ✔ | ✔ | | | | ✔ | | |
| Automated tick/coordination remediation (pause/resume/reset) | | | | | | | | | ✔ | | |
| Game asset publishing & object storage | ✔ | | | | | | | | | | |
| Asset deletion eligibility oracle (`CanDeleteVersionAssets`) | ✔ | | | | | | | | ✔ | | |
| Asset purge control-plane workflow (`BeginPurgeVersionAssets` / `FinalizePurgeVersionAssets`) | ✔ | | | | | | | | ✔ | | |
| TCP/Telnet socket handling | | | | | | | | | | ✔ | |
| Telnet → WebSocket bridging | | | | | | | | | | ✔ | |
| WebSocket upgrade, routing, and admin auth gating | | | | | | | | | | | ✔ |
| Authoritative owner: gateway dynamic route override policy | | | | | | | | | | | ✔ |
| Dynamic route management and gateway configuration | | | | | | | | | | | ✔ |
| Authoritative owner: edge admin/creator API allowlist policy | | | | | | | | | | | ✔ |
| Admin/creator API participation (edge-routable domain APIs) | ✔ | | ✔ | ✔ | | | | ✔ | ✔ | | ✔ |
| API gateway rate limiting and abuse filters | | | | | | | | | | | ✔ |

## Notes on Redis Ownership and Participation

- **Authoritative owner: Coordination Redis gameplay sessions (`session:game:*`)** – Game Session Service owns gameplay session bindings, lifecycle, and reset scope expectations for these keys. Other services participate only through documented shared helper libraries and key contracts; they do not introduce new gameplay session prefixes or modify TTLs/payload semantics without Game Session ownership and Redis design review.
- **Authoritative owner: Coordination Redis gameplay coordination keys (`tick:*`, `timer:*`, `retry:*`, `tick-executor-lease:*`)** – Game Session Service owns gameplay coordination schema and lifecycle for these prefixes. Other services participate only through documented shared helper libraries and key contracts; they do not introduce new gameplay coordination prefixes or modify TTLs/payload semantics without Game Session ownership and Redis design review.
- **Authoritative owner: Coordination Redis auth keyspace (`session:auth:*`)** – Account Service owns JWT allowlist and revocation watermark semantics, including lifecycle, revocation, and scope contracts consumed by downstream services.
- **Redis-backed automation ownership split** – Automation & Scripting Service owns:
  - Coordination Redis `automation:tick:*` keyspace and Lua scripts that drive automation ticks.
  - Cache/Rate-Limit Redis `automation:queue:*` and `automation:quota:*` best-effort queues/counters.
  Game Session and other services interact with automation via gRPC APIs, not by writing `automation:*` keys directly.
- **Cache/Rate-Limit Redis usage (caches, quotas, rate limiting)** – World Management Service, TCP Proxy Service, Spring Cloud Gateway, Entity Management Service, Automation & Scripting Service, and Social & Groups Service all use shared cache and rate‑limit helpers backed by Redis (for example, `cache:*` and `ratelimit:*` prefixes). World Management is authoritative for invalidation semantics of `room:*` and `world-dynamic:*` world caches; the schema, TTL policies, and correctness guarantees for these prefixes are defined in the shared cache/rate-limit library and in the Redis Cache & Rate Limiting design.

These ownership boundaries are normative per `design/architecture/decisions/adr-0009-coordination-redis-ownership-boundary.md`.

## Notes on Movement and Moderation Contracts

- **Movement/location write contract orchestration** – Game Session orchestrates movement under tick/effect identity, Game Logic computes deterministic movement outcomes, World Management commits authoritative room occupancy/location, and Entity Management applies entity-side consequences without owning occupancy indexes.
- **Replacement-instance compatibility preflight** – Game Session owns `ValidateInstanceCutoverCompatibility` orchestration and result semantics; Game Design, World, Entity, Automation, and Logging/Admin participate as dependency and policy providers for checks.
- **Moderation policy propagation** – Logging & Admin owns gameplay/chat moderation policy definition and audit trail; Game Session and Social & Groups enforce policy using versioned policy snapshots/events with bounded cache staleness and explicit invalidation semantics.
- **Ban taxonomy** – Account owns account-security bans and revocation watermark writes; Logging & Admin owns gameplay/chat moderation ban policy definitions; Game Session and Social & Groups are enforcement owners for gameplay and chat scopes respectively.
- **Admin/creator API allowlist policy** – Gateway owns the edge-route allowlist policy; domain services own only the API contracts behind allowlisted routes.
- **Edge exposure default** – Unless a service is explicitly marked as participating in edge-routable domain APIs, its APIs are internal-only and reached through service-to-service contracts, not directly from external tools via Gateway.

## Related Documentation

- [Microservices Overview](./microservices/README.md)
- [System Architecture Overview](./system-architecture-overview.md)
