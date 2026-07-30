# Microservices Responsibility Matrix

Checkmarks in this table indicate **participation** in a workflow. Rows prefixed with `Authoritative owner:` identify the single service that owns invariant enforcement or policy-of-record for that function.

## Implementation Status

- Live external operator availability includes Logging & Admin reads, investigation, moderation policy-input/audit persistence, admission-pointer reads/audit, and prepared-upgrade reads. The target mutation rows below are normative contracts, not claims that every listed mutation is currently routable.
- `/moderation/actions` and `ApplyModerationAction` are live for policy-input/audit persistence only; they do not perform owner-side enforcement. The target owner-side enforcement mutation remains gated on its action-family schema, shared cross-language `mutationDigest/v1` golden vectors, and Account-issued authorization-reference issuance/redemption flow. Synchronous `EvaluateModerationPolicy` consumption at `GAMEPLAY_ADMISSION` and `CHAT_SEND` remains separate owner enforcement.
- Feature-flag overrides, version-upgrade preparation, scoped per-instance tick pause/resume, admission-pointer CAS/cutover, and target session-lifecycle forwarding have implementation or owner contract seams but remain gated or target-only pending the same action/digest/reference contract and, where applicable, the target-only `privileged_control` issuance flow.
- Game Session `/sessions*` routes are current owner-local hooks; direct owner edge exposure is denied. The target external lifecycle ingress is Logging & Admin forwarding to Game Session owner RPCs with Account authorization-reference redemption, and no complete current external `/sessions*` family is claimed.
- Quota overrides, versioned moderation propagation, broader moderation enforcement, and broader tick remediation remain target coverage without current executable owner routes.

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
| Authoritative owner: moderation policy input persistence and audit (current internal/control-plane path) | | | | | | | | | ✔ | | |
| External `POST /moderation/actions` policy-input/audit persistence ingress (current; not owner enforcement) | | | | | | | | | ✔ | | |
| Account security policy (password rules, lockout, MFA requirements) | | | ✔ | | | | | | | | |
| Gameplay login command handling and session binding (Redis) | | | | ✔ | | | | | | | |
| Login throttling, lockout, password reset, and email verification | | | ✔ | | | | | | | | |
| WebSocket transport connection lifecycle (upgrade, routing, DMZ edges) | | | | | | | | | | ✔ | ✔ |
| Gameplay session lifecycle (login, resume, takeover) | | | | ✔ | | | | | | | |
| Owner-local Game Session session-lifecycle routes (`/sessions*`, current internal surface) | | | | ✔ | | | | | | | |
| External session-lifecycle operator forwarding through Logging & Admin (target; not current route) | | | | | | | | | `target only` | | |
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
| Version-state CAS API invocation for activation/rollback (`versionStateEpoch`) | ✔ | | | ✔ | | | | | ✔ | | |
| Authoritative owner: admission-pointer reads, prepared-upgrade reads, gated version-upgrade preparation mutation, target-only open/close/retarget `pointerVersion` CAS, and target-only cutover | | | | ✔ | | | | | | | |
| Admission-pointer reads, prepared-upgrade reads, gated version-upgrade preparation mutation, target-only open/close/retarget CAS, and target-only cutover invocation | | | | ✔ | | | | | ✔ | | |
| Runtime feature-flag truth and override participation (Game Session owns runtime truth) | | | | ✔ | | | | | ✔ | | |
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
| Game moderation report review and player tooling | | | | | | | | | ✔ | | |
| Current moderation policy-input persistence and audit (`POST /moderation/actions`; synchronous evaluation is a separate read) | | | | | | | | | ✔ | | |
| Gated moderation enforcement mutation (`GAMEPLAY_ADMISSION` / `CHAT_SEND`) | | | | ✔ | | | | ✔ | ✔ | | |
| Authoritative owner: subscription entitlements, plan-driven quota values, and effective quota override overlay (`GetTenantEntitlementsForRuntime`) | | | ✔ | | | | | | | | |
| Operator quota override ingress, UX, and audit (Logging & Admin forwards to the Account-owned entitlement overlay) | | | ✔ | | | | | | ✔ | | |
| Enforcement of gameplay bans at `GAMEPLAY_ADMISSION` (Game Session owns enforcement) | | | | ✔ | | | | | | | |
| Enforcement of chat mutes/bans at `CHAT_SEND` (Social & Groups owns enforcement) | | | | | | | | ✔ | | | |
| Movement/location write contract orchestration (effect identity, order, and replay safety) | | ✔ | | ✔ | ✔ | ✔ | | | | | |
| Instance termination orchestration (`PREPARING/ACTIVE/TERMINATING/TERMINATED`) and cross-service cleanup | | ✔ | | ✔ | ✔ | | | | ✔ | | |
| Automated tick/coordination remediation (per-instance pause/resume, guarded enumeration, regional mutation, and reset/remediate orchestration) | | | | ✔ | | | | | ✔ | | |
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
| External operator read/preparation ingress | | | | | | | | | ✔ | | ✔ |
| External operator write ingress for owner-side moderation enforcement, quota overrides, runtime feature flags, admission control/pointer writes, tick remediation, and session lifecycle (target boundary; only selected families live) | | | | | | | | | ✔ | | ✔ |
| API gateway rate limiting and abuse filters | | | | | | | | | | | ✔ |

The `<tenantId, gameInstanceId, regionId>` tuple in the tick and coordination health metrics row is the diagnostic scope Game Session must support through control-plane status, structured logs, and audit records. It is not a Prometheus label tuple: metric series must use bounded `scope`, `scope_bucket`, `region_class`, or equivalent operational buckets under the cardinality policy, while exact identities remain in diagnostic records.

For the edge-routable services in this matrix, participation does not imply that every mutation may be called directly by external tools. Per the overview’s canonical operator write ingress policy, external mutating operator workflows for moderation, quota overrides, runtime feature-flag overrides, admission control, and tick remediation must enter through Logging & Admin. Direct external writes on other edge-routable services require an explicit bypass-safe designation in the owning service contract. Game Design tenant-scoped asset and template creator writes are the current architecture-level bypass-safe write class delegated to an owning service contract.

Service docs may not create new external bypass-safe write classes on their own. If a workflow is not explicitly allowlisted by the overview or this matrix, treat it as non-bypass-safe until the architecture docs are updated.

Route-review procedure:

1. Classify the proposed route using the [canonical route-review examples and traffic split](./system-architecture-overview.md#external-admin-traffic-split-canonical).
2. Confirm the matching participation and ingress rows below; participation alone does not authorize a direct external mutation.

- The exact current matrix rows are **External operator read/preparation ingress** and **External operator mutating ingress**; their current availability and drift are recorded in **Implementation Status**, while the rows remain the canonical target participation contract.
- Proposed route: `POST /api/session/game-sessions/{id}/feature-flags/{flagKey}:toggle`. Matrix check: `Game Session` participates in `Admin/creator API participation`, but the **External operator mutating ingress** row routes this workflow through `Logging & Admin`, so the direct external Game Session route is not allowed without a design update; current availability is governed by **Implementation Status**.
- Proposed route: `POST /api/design/templates`. Matrix check: `Game Design` participates in `Admin/creator API participation`, and `Bypass-safe Game Design creator writes for tenant-scoped assets and templates` delegates this domain-local creator write to the Game Design service contract, so the route may be edge-routable when Game Design documents tenant access, validation, and audit behavior.
- Proposed route: `GET /api/account/accounts/{id}`. Matrix check: `Account Service` participates in `Admin/creator API participation`, and the request is an external admin read rather than an operator mutation covered by the **External operator mutating ingress** row, so the route may be edge-routable when the owning service documents it as a bypass-safe read contract.

The current moderation policy-input row assigns `POST /moderation/actions` persistence and audit to Logging & Admin; its separate `EvaluateModerationPolicy` read supplies runtime decisions. The route does not itself perform owner-side enforcement. Target owner-side enforcement, quota, runtime, admission-control/pointer-write, tick, and lifecycle mutations use the canonical Logging & Admin ingress and remain individually gated by their owning action, digest, and authorization contracts.

## Notes on Redis Ownership and Participation

- **Independent version and admission CAS domains** – Game Design Service owns the version lifecycle state and `versionStateEpoch` CAS contract used for activation and rollback. Game Session and Logging & Admin may invoke that typed lifecycle API through their documented control-plane paths, but neither owns the version epoch. The live admission-pointer responsibility is read/preparation; target state assigns Game Session the `pointerVersion` CAS and cutover contract for `{tenantId, worldSlug, realmSlug}`, with Logging & Admin invoking the target operation through its operator ingress. The two CAS tokens are independent and must not be substituted for one another.

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
- **Operator-action coverage split** – Admission-pointer `GET` reads, audit, and prepared-upgrade proof reads are the live external operator read ingress. Version-upgrade preparation is a mutation and remains externally gated pending its action-family schema, shared `mutationDigest/v1` vector conformance, and Account authorization-reference issuance/redemption. Runtime feature-flag overrides, admission-control/pointer writes, and scoped per-instance tick pause/resume use the same three-part external gate; the current `/moderation/actions` and `ApplyModerationAction` paths are live only for policy-input/audit persistence, while target owner-side enforcement remains gated. Game Session's `/sessions*` lifecycle routes are current owner-local hooks, not a complete external ingress family; target lifecycle forwarding enters through Logging & Admin. Admission-pointer open/close/retarget CAS and cutover are target-only. Current gameplay/chat enforcement synchronously consumes the live `EvaluateModerationPolicy` read; versioned propagation, quota override, and broader reset/remediate actions remain target families.
- **Tick remediation split** – Logging & Admin owns the implementation-participating per-instance pause/resume ingress, target-state remediation APIs, automation policy, and audit trail; external pause/resume mutation enablement remains gated pending the action-family schema, shared `mutationDigest/v1` vectors, and Account authorization-reference issuance/redemption. Game Session owns all tick/coordination state mutation. Broader reset/remediate actions remain hypothetical until the owner APIs exist.
- **Replacement-instance compatibility preflight** – Game Session owns `ValidateInstanceCutoverCompatibility` orchestration and result semantics; Game Design, World, Entity, Automation, and Logging/Admin participate as dependency and policy providers for checks.
- **Moderation policy propagation** – Logging & Admin owns current gameplay/chat moderation policy-input persistence, evaluation, definition, and audit; `POST /moderation/actions` and `ApplyModerationAction` are live for persistence only and do not perform owner-side enforcement. Target owner-side enforcement remains gated on its action-family schema, shared `mutationDigest/v1` vectors, and Account authorization-reference issuance/redemption flow. Game Session consumes `GAMEPLAY_ADMISSION` and Social & Groups consumes `CHAT_SEND` at their owner boundaries. Versioned policy snapshot/event propagation, pull-on-miss refresh, and broader enforcement remain missing; the applicable high-risk decisions fail closed when fresh policy evidence is unavailable. See the canonical moderation propagation contract in `design/architecture/system-architecture-overview.md`.
- **Ban taxonomy** – Account owns account-security bans and auth authority-generation advances; Logging & Admin owns current gameplay/chat moderation policy input, definitions, evaluation, and audit; Game Session and Social & Groups enforce gameplay and chat decisions respectively. The gated moderation enforcement mutation endpoint is unavailable and does not directly perform runtime enforcement.
- **Admin/creator API allowlist policy** – Gateway owns the edge-route allowlist policy; domain services own only the API contracts behind allowlisted routes.
- **External operator read/preparation ingress** – Logging & Admin participates in the live external ingress for admission-pointer reads/audit/prepared-upgrade proof reads, while version-upgrade preparation is a mutation that remains externally gated pending its action-family schema, shared `mutationDigest/v1` vector conformance, and Account authorization-reference issuance/redemption. Game Session remains the authority for the underlying read/preparation contract. Game Session's current `/sessions*` lifecycle routes remain owner-local rather than current external ingress.
- **External operator mutating ingress** – Logging & Admin is the mandatory external ingress for implementation-participating runtime feature flags, version-upgrade preparation, admission-control/pointer writes, and scoped per-instance tick pause/resume, but those mutations remain externally gated pending their action-family schemas, shared `mutationDigest/v1` vectors, and Account authorization-reference issuance/redemption. Target owner-side moderation enforcement, lifecycle forwarding, admission-pointer open/close/retarget CAS and cutover, versioned moderation propagation, hypothetical quota overrides, and broader tick remediation also enter through Logging & Admin; Gateway participates only as the edge routing and coarse protection layer for those writes.
- **Edge admin/creator protocol** – External admin/creator APIs are HTTP(S) only at the Gateway edge unless a dedicated design update explicitly adds an edge gRPC contract. Internal service-to-service gRPC remains direct. External mutating operator workflows defined in the overview’s canonical operator action table must enter through Logging & Admin rather than directly through another edge-routable service.
- **Edge exposure default** – Unless a service is explicitly marked as participating in edge-routable domain APIs, its APIs are internal-only and reached through service-to-service contracts, not directly from external tools via Gateway.
- **Gameplay hot path policy** – Service APIs used in steady-state gameplay must follow the overview’s canonical bounded fan-out rule. New hot-path designs needing synchronous calls to more than two downstream domain services require an architecture-level justification of latency budget, fallback behavior, and why pre-aggregation or a read model is insufficient.

## Related Documentation

- [Microservices Overview](./microservices/README.md)
- [System Architecture Overview](./system-architecture-overview.md)
