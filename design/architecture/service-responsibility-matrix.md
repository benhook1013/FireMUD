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
| Account-security ban policy (`account_security_ban`) and account authority-generation revocation | | | ✔ | | | | | | | | |
| Gameplay-ban policy definition and audit (`gameplay_ban`) | | | | | | | | | ✔ | | |
| Chat mute/chat-ban policy definition and audit (`chat_mute`, `chat_ban`) | | | | | | | | | ✔ | | |
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
| Authoritative owner: Coordination Redis connect-token replay (`gateway:connect-token:jti:*` and replay-readiness fence) | | | | | | | | | | | ✔ |
| Authoritative owner: Coordination Redis automation tick keyspace (`automation:tick:*`) | | | | | | | ✔ | | | | |
| Tick-region lease ownership and executor coordination (`<tenantId, gameInstanceId, regionId>`) | | | | ✔ | | | | | | | |
| Gameplay WebSocket route definition and routing (`/ws/game/**` canonical route) | | | | | | | | | | | ✔ |
| Game version activation at runtime | | | | ✔ | | | | | | | |
| Replacement-instance compatibility preflight (`ValidateInstanceCutoverCompatibility`) | ✔ | ✔ | | ✔ | ✔ | | ✔ | | ✔ | | |
| Authoritative owner: `versionStateEpoch` CAS enforcement | ✔ | | | | | | | | | | |
| Version-state CAS APIs ownership/invocation for activation/rollback (`versionStateEpoch`) | ✔ | | | ✔ | | | | | ✔ | | |
| Runtime feature flag overrides | | | | ✔ | | | | | | | |
| Tick & coordination health metrics (diagnostic scope: `<tenantId, gameInstanceId, regionId>`) | | | | ✔ | | | | | | | |
| Canonical room-state read fence production and same-fence room-view composition | | ✔ | | | ✔ | ✔ | | | | | |
| Entity definition and persistence | | | | | ✔ | | | | | | |
| NPC state, inventory, and stats | | | | | ✔ | | | | | | |
| Player inventory and stats | | | | | ✔ | ✔ | | | | | |
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
| Redis-backed automation queue projection and timer coordination (`automation:queue:*`, `automation:timer:*`, `script-scheduler:*`) | | | | | | | ✔ | | | | |
| Coordination Redis participation via shared helpers (locks and documented automation/tick prefix rules) | | | | ✔ | ✔ | | ✔ | | | | |
| Cache/Rate-Limit Redis usage (caches, quotas, rate limiting) | | ✔ | | | ✔ | | ✔ | ✔ | | ✔ | ✔ |
| Chat and private messaging | | | | | | | | ✔ | | | |
| Guilds and group discovery | | | | | | | | ✔ | | | |
| Social network graph (friends/blocks/etc.) | | | | | | | | ✔ | | | |
| Centralized observability dashboards and moderation analytics (logs/metrics/traces) | | | | | | | | | ✔ | | |
| Admin panel UX and runtime feature-flag override workflow | | | | ✔ | | | | | ✔ | | |
| Game moderation tools | | | | | | | | | ✔ | | |
| Game moderation policy definition | | | | | | | | | ✔ | | |
| Moderation policy propagation contract (versioning, invalidation, bounded staleness, and audit context) | | | | ✔ | | | | ✔ | ✔ | | |
| Subscription entitlements and plan-driven quota values (`GetTenantEntitlements`) | | | ✔ | | | | | | | | |
| Operator quota overrides, auditing, and dashboards (overlay on entitlements) | | | | | | | | | ✔ | | |
| Enforcement of gameplay bans at login/command level | | | | ✔ | | | | | | | |
| Enforcement of chat mutes/bans at message send time | | | | | | | | ✔ | | | |
| Authoritative owner: gameplay-ban enforcement (policy remains Logging & Admin-owned) | | | | ✔ | | | | | | | |
| Authoritative owner: chat mute/chat-ban enforcement (policy remains Logging & Admin-owned) | | | | | | | | ✔ | | | |
| Movement/location write contract orchestration (effect identity, order, and replay safety) | | ✔ | | ✔ | ✔ | ✔ | | | | | |
| Instance termination orchestration (`PREPARING/ACTIVE/TERMINATING/TERMINATED`) and cross-service cleanup | | ✔ | | ✔ | ✔ | | | | ✔ | | |
| Automated tick/coordination remediation (pause/resume/reset) | | | | ✔ | | | | | ✔ | | |
| Game asset publishing & object storage | ✔ | | | | | | | | | | |
| Asset deletion eligibility oracle (`CanDeleteVersionAssets`) | ✔ | | | | | | | | ✔ | | |
| Asset purge control-plane workflow (`BeginPurgeVersionAssets` / `FinalizePurgeVersionAssets`) | ✔ | | | | | | | | ✔ | | |
| Bypass-safe Game Design creator writes for tenant-scoped assets and templates | ✔ | | | | | | | | | | ✔ |
| TCP/Telnet socket handling | | | | | | | | | | ✔ | |
| Telnet → WebSocket bridging | | | | | | | | | | ✔ | |
| WebSocket upgrade, routing, and admin auth gating | | | | | | | | | | | ✔ |
| Authoritative owner: gateway dynamic route override policy | | | | | | | | | | | ✔ |
| Dynamic route management and gateway configuration | | | | | | | | | | | ✔ |
| Authoritative owner: edge admin/creator API allowlist policy | | | | | | | | | | | ✔ |
| Admin/creator API participation (edge-routable domain APIs) | ✔ | | ✔ | ✔ | | | | ✔ | ✔ | | ✔ |
| External operator write ingress for moderation, quota overrides, runtime feature flags, admission control, and tick remediation | | | | | | | | | ✔ | | ✔ |
| API gateway rate limiting and abuse filters | | | | | | | | | | | ✔ |

The `<tenantId, gameInstanceId, regionId>` tuple in the tick and coordination health metrics row is the diagnostic scope Game Session must support through control-plane status, structured logs, and audit records. It is not a Prometheus label tuple: metric series must use bounded `scope`, `scope_bucket`, `region_class`, or equivalent operational buckets under the cardinality policy, while exact identities remain in diagnostic records.

The `<tenantId, gameInstanceId, regionId>` tuple in the tick and coordination health metrics row is the diagnostic scope Game Session must support through control-plane status, structured logs, and audit records. It is not a Prometheus label tuple: metric series must use bounded `scope`, `scope_bucket`, `region_class`, or equivalent operational buckets under the cardinality policy, while exact identities remain in diagnostic records.

For the edge-routable services in this matrix, participation does not imply that every mutation may be called directly by external tools. Per the overview’s canonical operator write ingress policy, external mutating operator workflows for moderation, quota overrides, runtime feature-flag overrides, admission control, and tick remediation must enter through Logging & Admin. Direct external writes on other edge-routable services require an explicit bypass-safe designation in the owning service contract. Game Design tenant-scoped asset and template creator writes are the current architecture-level bypass-safe write class delegated to an owning service contract.

Service docs may not create new external bypass-safe write classes on their own. If a workflow is not explicitly allowlisted by the overview or this matrix, treat it as non-bypass-safe until the architecture docs are updated.

Route-review example:

- Proposed route: `POST /api/session/game-sessions/{id}/feature-flags/{flagKey}:toggle`. Matrix check: `Game Session` participates in `Admin/creator API participation`, but `External operator write ingress for moderation, quota overrides, runtime feature flags, and tick remediation` routes this workflow through `Logging & Admin`, so the direct external Game Session route is not allowed without a design update.
- Proposed route: `POST /api/design/templates`. Matrix check: `Game Design` participates in `Admin/creator API participation`, and `Bypass-safe Game Design creator writes for tenant-scoped assets and templates` delegates this domain-local creator write to the Game Design service contract, so the route may be edge-routable when Game Design documents tenant access, validation, and audit behavior.
- Proposed route: `GET /api/account/accounts/{id}`. Matrix check: `Account Service` participates in `Admin/creator API participation`, and the request is an external admin read rather than an operator write covered by `External operator write ingress for moderation, quota overrides, runtime feature flags, and tick remediation`, so the route may be edge-routable when the owning service documents it as a bypass-safe read contract.

## Notes on Redis Ownership and Participation

- **Authoritative owner: Coordination Redis gameplay sessions (`session:game:*`)** – Game Session Service owns gameplay session bindings, lifecycle, and reset scope expectations for these keys. Other services participate only through documented shared helper libraries and key contracts; they do not introduce new gameplay session prefixes or modify TTLs/payload semantics without Game Session ownership and Redis design review.
- **Authoritative owner: Coordination Redis gameplay coordination keys (`tick:*`, `timer:*`, `retry:*`, `tick-executor-lease:*`)** – Game Session Service owns gameplay coordination schema and lifecycle for these prefixes. Other services participate only through documented shared helper libraries and key contracts; they do not introduce new gameplay coordination prefixes or modify TTLs/payload semantics without Game Session ownership and Redis design review.
- **Authoritative owner: Coordination Redis auth sessions (`session:auth:*`)** – Account Service owns the issued-token registry and revocation/version semantics, including lifecycle, revocation, and scope contracts consumed by downstream services.
- **Authoritative owner: Coordination Redis connect-token replay (`gateway:connect-token:jti:*` and replay-readiness fence)** – Spring Cloud Gateway owns only this narrow edge replay-consumption keyspace and its readiness fence. It does not own general gameplay sessions, Account auth state, or broader coordination policy.
- **Redis-backed automation ownership split** – Automation & Scripting Service owns:
  - Coordination Redis scheduler/timer keys such as `automation:timer:*` and `script-scheduler:*`.
  - Cache/Rate-Limit Redis `automation:queue:*`, `automation:quota:*`, `automation:tenant-budget:*`, and `automation:test:capacity:*` best-effort queues/counters.
  Game Session and other services interact with automation via gRPC APIs, not by writing `automation:*` keys directly.
- **Cache/Rate-Limit Redis usage (caches, quotas, rate limiting)** – World Management Service, TCP Proxy Service, Spring Cloud Gateway, Entity Management Service, Automation & Scripting Service, and Social & Groups Service all use shared cache and rate‑limit helpers backed by Redis (for example, `cache:*` and `ratelimit:*` prefixes). World Management is authoritative for invalidation semantics of `room:*` and `world-dynamic:*` world caches; the schema, TTL policies, and correctness guarantees for these prefixes are defined in the shared cache/rate-limit library and in the Redis Cache & Rate Limiting design.

These ownership boundaries are normative per `design/architecture/decisions/adr-0009-coordination-redis-ownership-boundary.md`.

## Notes on Movement and Moderation Contracts

- **Movement/location write contract orchestration** – Game Session orchestrates movement under tick/effect identity and owns per-session sequencing plus the current execution-region pointer; Game Logic computes deterministic movement outcomes and orchestrates same-fence room-read composition, World Management commits authoritative room occupancy/location and emits the canonical room-read fence, and Entity Management applies entity-side consequences without owning occupancy indexes.
- **Movement hot-path exception** – The overview’s two-downstream-service ceiling has one explicit initial-slice exception for movement and region-transition orchestration: Game Session may synchronously coordinate Game Logic, World Management, and Entity Management under one fenced tick/effect contract. This exception is valid only with the overview’s documented budget/fallback contract and must not expand to additional participants without a new architecture decision.
- **Canonical room-state read fence** – World Management emits the canonical room-read fence on `GetRoomSnapshot`; Game Logic orchestrates same-fence room-view composition by comparing the World fence with the Entity Management room-entity fence and composing the `LookResult` only when both reads align. Game Session owns request initiation, ordering, and transcript rendering/cache behavior, but it is not the downstream read orchestrator for `GetRoomSnapshot` plus `ListRoomEntities`. See the canonical room runtime contract in `design/architecture/system-architecture-overview.md`.
- **Item command runtime split** – Game Session owns text-session ingress and transcript rendering for player item commands; Game Logic owns the gameplay-facing item command RPC seam; Entity Management remains authoritative for item/container/equipment persistence, holder mutation, validation, and transfer audit writes.
- **Tick remediation split** – Logging & Admin owns operator-facing remediation APIs, automation policy, and audit trail; Game Session owns all tick/coordination state mutation and executes pause/resume/remediation control actions through its control-plane APIs.
- **Replacement-instance compatibility preflight** – Game Session owns `ValidateInstanceCutoverCompatibility` orchestration and result semantics; Game Design, World, Entity, Automation, and Logging/Admin participate as dependency and policy providers for checks.
- **Moderation policy propagation** – Logging & Admin owns gameplay/chat moderation policy definition and audit trail; Game Session and Social & Groups enforce policy using versioned policy snapshots/events with monotonic invalidation per `{tenantId, policyScope}`, bounded cache staleness, pull-on-miss refresh, and fail-closed behavior for `gameplay_ban` and `chat_ban` when no fresh snapshot is available within the allowed window. See the canonical moderation propagation contract in `design/architecture/system-architecture-overview.md`.
- **Ban taxonomy** – Account owns account-security bans and auth authority-generation advances; Logging & Admin owns gameplay/chat moderation ban policy definitions; Game Session and Social & Groups are enforcement owners for gameplay and chat scopes respectively.
- **Admin/creator API allowlist policy** – Gateway owns the edge-route allowlist policy; domain services own only the API contracts behind allowlisted routes.
- **External operator write ingress** – Logging & Admin is the mandatory external ingress for operator writes covering moderation, quota overrides, runtime feature flags, admission control, and tick remediation; Gateway participates only as the edge routing and coarse protection layer for those writes.
- **Edge admin/creator protocol** – External admin/creator APIs are HTTP(S) only at the Gateway edge unless a dedicated design update explicitly adds an edge gRPC contract. Internal service-to-service gRPC remains direct. External mutating operator workflows defined in the overview’s canonical operator action table must enter through Logging & Admin rather than directly through another edge-routable service.
- **Edge exposure default** – Unless a service is explicitly marked as participating in edge-routable domain APIs, its APIs are internal-only and reached through service-to-service contracts, not directly from external tools via Gateway.
- **Gameplay hot path policy** – Service APIs used in steady-state gameplay must follow the overview’s canonical bounded fan-out rule. New hot-path designs needing synchronous calls to more than two downstream domain services require an architecture-level justification of latency budget, fallback behavior, and why pre-aggregation or a read model is insufficient.

## Related Documentation

- [Microservices Overview](./microservices/README.md)
- [System Architecture Overview](./system-architecture-overview.md)
