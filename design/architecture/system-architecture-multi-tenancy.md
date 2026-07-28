# FireMUD System Architecture: Multi-Tenancy

This document explains how FireMUD hosts many independent games on shared infrastructure. It complements the [System Architecture Overview](./system-architecture-overview.md) and the multi-tenant requirements in the [Core Requirements](../project-management/core-requirements.md).

## Normative Target Contract

Tenant identity, explicit public-production membership creation, realm-local gameplay scope, caller-bound authorization, and exact target routing are one contract. Global roles may authorize only explicitly classified control-plane routes; they never create tenant membership, bypass target-scope checks, or grant gameplay admission. All player-facing admission and tenant-scoped mutations fail closed when authoritative membership, entitlement, routing, or generation evidence is unavailable or ambiguous.

## Implementation Status

The realm-catalog, admission-pointer, realm-local character, explicit first-join, and quota-boundary contracts below are normative target behavior. Current runtime proof is partial: `IssueConnectToken` and text `PLAY` can still invoke implicit public-production membership creation; the membership-generation live reread at connect-token issuance is not implemented; pointer updates still use a read-then-write check with separate pointer/audit/prepared-execution writes; scoped-role population and tenant switching are not fully implemented/proved; and authoritative entitlement freshness plus complete owning-service quota enforcement remain incomplete. The implicit membership creation in `IssueConnectToken` and `PLAY` is a live security-contract violation and known drift, not a benign non-weakening implementation gap: only the explicit public-production `JOIN` flow may create membership, and affected admission paths must fail closed until that rule is enforced. The remaining gaps do not change the distinct `tenantSlug` and tenant-scoped authored-world `worldSlug` selectors, the exact-tenant quota binding, or the required `GetAdmissionPointer(tenantId, worldSlug, realmSlug)` contract.

---

## Identity & Tenant Model

FireMUD separates **global identity** from **per-game state** so that one person can participate in multiple games without data leakage between tenants.

- **Platform account (`accountId`)** – A global identity record managed by the Account Service. Each human player has a single platform account, which is the subject of authentication and JWT issuance.
- **Tenant (`tenantId`)** – A hosted game world or project. Each tenant represents one game created on the platform and may have one or more running game instances. The Game Design Service owns `tenantId` issuance.
- **Tenant slug (`tenantSlug`)** – A stable, human-friendly identifier owned by the Game Design Service. Slugs are used only as **player-facing selectors** in the post-login lobby flow (`WORLDS` / `REALMS` / `CHARS` / `PLAY`) and are resolved server-side to `tenantId`; services and persistence models continue to use `tenantId` as the authoritative tenant identifier. See [ADR 0005: Tenant Identifiers in Gameplay Protocol](./decisions/adr-0005-tenant-identifiers-in-gameplay-protocol.md) for the required slug stability rules.
- **World slug (`worldSlug`)** – A stable tenant-scoped selector for one authored world inside the tenant/game. It is resolved only together with `tenantId` and is paired with `realmSlug` by the realm catalog and admission-pointer contract. It is not an alias for `tenantSlug`, does not identify a tenant across the platform, and must not be inferred from display metadata, `realmSlug`, or `gameInstanceId`.
- **Game instance (`gameInstanceId`)** – A specific running instance of a tenant’s world, keyed as described in [Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md#version-activation--rollback). Persistence models, APIs, and key formats must include `gameInstanceId` explicitly rather than overloading `tenantId`.
  - A tenant may expose one or more **player-addressable realms**. Each realm is explicitly `OPEN` on exactly one admissible `gameInstanceId` or `CLOSED` with none through the authoritative realm-routing contract owned by Game Session.
  - Exactly one visible, player-addressable realm must be flagged `publicProduction=true` in the separately revisioned Game Session catalog/policy record for each tenant. Zero or multiple public-production realms are invalid catalog state: public discovery, first join, connect-token issuance, and `PLAY` must fail closed rather than selecting a fallback. The admission pointer contains only `OPEN(gameInstanceId)` or `CLOSED` routing authority plus its `pointerVersion`; callers consume the catalog/policy flag rather than inferring public-production behavior from a slug.
  - Additional realms are explicitly authorized non-production realms such as playtest forks. They are never public-discovery realms in v1 and require explicit access grants owned by Account Service and evaluated through the same runtime grant authority across bootstrap discovery and gameplay admission.
  - Additional running instances may still exist for operational workflows, but only realms surfaced through the authenticated lobby contract are player-addressable.
- **Account–tenant membership and roles** – For each tenant a platform account participates in, the platform records membership and roles (for example, `player`, `designer`, `tenantAdmin`) that appear in JWT `scopedRoles[tenantId]` claims. Membership is many-to-many: one account can join many tenants, and each tenant can host many accounts.
- **Character (`characterId`)** – A gameplay identity controlled by a platform account within a specific tenant. Character identity is tenant-scoped, but gameplay session binding and any realm- or instance-local character state, inventories, or progress must also include `gameInstanceId`.
- **Gameplay session** – A transient session binding between a connected client and a character in a tenant, managed by the Game Session Service and stored in Redis using keys such as `session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>`. These gameplay session keys are always tenant- and instance-scoped and never change the global `accountId`. Sessions bind sockets to character identities within a tenant (see [Authentication & Authorization](./system-architecture-authentication.md#login-and-session-flow) and [Session and Identity Management](./system-architecture-authentication.md#session-and-identity-management)).
  - Uniqueness for takeover/resume is enforced as `{tenantId, gameInstanceId, characterId}`.
- **Auth token session** – A short-lived issued-token registry record (`session:auth:token:<tokenHash>`) backing a revocable JWT for meta/control or admission APIs, as described in [Authentication & Authorization](./system-architecture-authentication.md#session-and-identity-management).
- **Control-plane browser session** – A front-end admin/creator UI session that holds short-lived JWTs in memory and relies on auth token sessions on the server; these are distinct from gameplay sessions and are described in the Frontend Architecture and Authentication designs.

### Authority Generations Across Tenant States

Account Service owns the issuer, account, tenant, and caller-bound membership authority generations and their projections. Tenant generation applies by default. `platformAdmin` requests in `tenant_regular` and `cross_tenant_data_bearing` bind the exact current target-tenant generation as a freshness fence without acquiring membership. Only the explicit route-class allowlist is exempt from the target tenant generation: `billing_safe_tenant` still requires issuer/account and caller-bound membership generations plus a live `tenantAdmin` check; `cross_tenant_support_safe` still requires issuer/account generations, a live global `support` or explicitly allowed `platformAdmin` role, and global token scope; and `cross_tenant_billing_safe` still requires issuer/account generations, a live global `billingAdmin` or explicitly allowed `platformAdmin` role, global token scope, and `privileged_control`. Support does not require that elevation; the `platformAdmin` path does. No route may use cached authorization, and a new route class does not inherit this allowlist.

If Account authority or its required projection is unavailable, the operation fails closed with retryable `AUTH_UNAVAILABLE`. UI clients retain in-memory state for retry, but no cached JWT role, membership, generation, or allowlist result is authorization.

### Realm State Model

Tenant membership, tenant roles, and character ownership answer "which game does this account belong to?" They do not imply that every realm inside a tenant shares one undifferentiated set of gameplay state.

FireMUD distinguishes between:

- **Tenant-scoped identity and ownership** – platform accounts, tenant membership, role grants, and the fact that a character belongs to a tenant.
- **Realm- or instance-scoped playable state** – the gameplay state that applies when a player enters a specific realm resolved to a specific `gameInstanceId`.

Realm-scoped playable state may follow either of these patterns:

- **Shared-state realm** – the realm uses the tenant's normal live character state, so the same character identity, progression, and durable inventory are reused when the player enters that realm.
- **Isolated-state realm** – the realm uses instance-local gameplay state for that `gameInstanceId` rather than the tenant's normal live character state. That isolated state may start from a copied source snapshot, seeded/sample data, or fresh standalone state. Playtest forks are the canonical copied-state example: copied characters remain associated with the same platform account and tenant, but the fork keeps its own fork-local progression, inventory, and other runtime state.

Minimum downstream consequences of realm policy:

- Shared-state realms reuse the tenant's normal live gameplay state namespace for the selected character.
- Isolated-state realms must treat at least the following as realm-/instance-scoped gameplay state keyed to the resolved `gameInstanceId`:
  - visible character roster for `CHARS`;
  - character progression/resources;
  - durable inventory/equipment/containment state;
  - learned or pinned gameplay loadout/state that materially affects play in that realm.
- Tenant membership, account ownership, billing state, and cross-tenant control-plane identity remain tenant-scoped regardless of realm mode.
- Social/account-level relationships may remain tenant- or account-scoped unless a dedicated gameplay design says otherwise, but they must not be used to silently collapse isolated gameplay state back into the tenant's live production state.

Character-selection and creation policy must also respect realm mode:

- `CHARS` lists the character choices valid for the resolved `{tenantId, gameInstanceId}` target, not a tenant-wide superset.
- Shared-state realms normally expose the tenant's normal live durable roster for that account.
- Isolated-state realms expose only that realm's valid roster, which may consist of copied fork-local characters, seeded/sample characters, newly created realm-local characters, or a policy-defined subset of those.
- `CHARS` must return one realm-local decision surface for the selected target: the visible roster plus a bounded `creationPolicy` / equivalent flag explaining whether fresh character creation is allowed, denied, or limited to a documented realm-local mode. Clients must not infer creation rules by comparing roster contents to tenant-wide state.
- If an isolated realm forbids fresh character creation, admission must fail with an explicit character-selection denial rather than implying the caller may create into the tenant's normal live roster.
- If an isolated realm allows both copied characters and fresh realm-local creation, both appear as one realm-local roster/creation experience for that target. The client must not require a separate "copied vs new" mode switch to enter the realm.

This distinction is normative for all realm-aware flows:

- `REALMS` describes which player-addressable realms exist for a tenant.
- `CHARS` lists the character choices valid for the resolved `{tenantId, gameInstanceId}` target.
- `PLAY` binds the session to that same `{tenantId, gameInstanceId, characterId}` target.

Services must therefore avoid collapsing "character belongs to tenant" into "all character-associated data is keyed only by tenant." Tenant ownership remains stable, while playable state may be shared across realms or isolated per realm according to the resolved realm policy.

This model underpins both authentication and authorization:

- Authentication always resolves a single platform `accountId`.
- Tenant-scoped control-plane authorization combines the authenticated `accountId` with tenant-scoped roles from `scopedRoles[tenantId]`, plus any cross-tenant `globalRoles` such as `platformAdmin`, as described in [Authentication & Authorization](./system-architecture-authentication.md).
- Gameplay admission is stricter: player-facing `WORLDS` / `REALMS` / `CHARS` / `PLAY` selection uses caller-bound tenant membership, public-production admission policy, and entitlement checks, and global roles alone do not grant gameplay admission.
- Public-production `JOIN` is the only pre-membership tenant action. It is an explicit Account-owned membership write; character creation, connect-token issuance, and `PLAY` require the resulting caller-bound membership and never create it implicitly.
- Player-facing world visibility in v1 has two sources:
  - existing caller-bound tenant membership for any visible realm the caller is allowed to enter, and
  - public-production discovery for tenants whose explicit public-production realm is live and gameplay-admissible.
  Worlds that fail both visibility sources, or fail entitlement checks, must not appear in discovery responses.

### Realm Catalog and Admission-Pointer Contract

Realm routing is a control-plane/runtime contract, not merely a lobby rendering concern.

The platform distinguishes between:

- a **realm catalog** describing which realms are player-addressable for one tenant; and
- an **admission pointer** describing whether one `{tenantId, worldSlug, realmSlug}` target is `OPEN` on one concrete `gameInstanceId` or `CLOSED` with no admission target.

Minimum realm-catalog facts for one visible realm are:

- `tenantId`
- `tenantSlug`
- `worldSlug`
- `realmSlug`
- bounded player-facing display metadata
- whether the realm is visible
- `publicProduction` policy value distinguishing the explicit public-production realm from explicit-grant-only realms
- whether the realm uses shared-state or isolated-state gameplay policy
- `catalogRevision`, the monotonic identifier of the versioned catalog/policy snapshot for this realm

Minimum admission-pointer facts for one resolved realm are:

- `tenantId`
- `worldSlug`
- `realmSlug`
- `admissionState` (`OPEN` or `CLOSED`)
- `admissibleGameInstanceId` when and only when `OPEN`
- `pointerVersion`
- `catalogRevision`, referencing the same versioned catalog/policy snapshot
- `updatedAt`

`catalogRevision` is the one canonical catalog-only monotonic field. It identifies the separately versioned catalog/policy snapshot for the stable `{tenantId, worldSlug, realmSlug}` identity; the snapshot contains visibility, access-policy, and `publicProduction` facts. The routing record stores that revision as its catalog/policy reference. There is no separate `policyRevision` or second policy counter. Display metadata and other catalog-only edits advance `catalogRevision`, not the runtime `pointerVersion`, and therefore do not invalidate an existing gameplay binding. Admission-policy reads must evaluate `publicProduction` and the other policy facts from the snapshot identified by the pointer's `catalogRevision`; callers must not infer public-production status from a slug, a current mutable catalog read, or an omitted revision. Admission-policy reads still revalidate current visibility, public-production, grant, and entitlement facts before creating or renewing authority.

Contract rules:

- `REALMS`, `CHARS`, `PLAY`, bootstrap discovery, connect-token issuance, and reconnect validation must all consume the same realm-catalog and admission-pointer truth.
- Clients never select raw `gameInstanceId` values directly. They select a world and optional realm, and the server resolves that choice to the current admissible runtime target.
- Each player-addressable realm has at most one admissible `gameInstanceId` at a time. `OPEN` names exactly one target; `CLOSED` names none.
- Public-production onboarding and first-join membership creation are controlled by the catalog/policy revision's `publicProduction` value plus visibility and entitlement checks, not by a duplicated routing flag or by comparing `realmSlug` with a reserved string.
- Catalog validation must find exactly one `publicProduction=true` realm per tenant. A zero or multiple result is ambiguous authority and fails closed for public-production discovery and admission; callers must not infer one from ordering, visibility, or a reserved slug.
- An explicitly `CLOSED` realm may remain visible with an unavailable/maintenance presentation, but ordinary gameplay admission returns `REALM_UNAVAILABLE`; `CLOSED` is a deliberate authoritative state, not a missing pointer. Missing, malformed, unavailable, or ambiguous routing state remains `ADMISSION_POINTER_UNAVAILABLE`; callers must not confuse pointer-authority failure with deliberate closure or guess a replacement target.

Required read contract:

- `GetAdmissionPointer(tenantId, worldSlug, realmSlug)` is the authoritative gameplay-admission lookup.
- The authoritative owner of this pointer contract is the Game Session control plane.
- Callers must treat missing required pointer fields, ambiguous results, or stale pointer state as contract failures rather than inferring defaults. A complete `CLOSED` record is not an incomplete pointer.
- The result mapping is stable: complete `CLOSED` authority maps to `REALM_UNAVAILABLE`; no result, malformed required fields, unavailable authority, or multiple ambiguous pointer results map to `ADMISSION_POINTER_UNAVAILABLE`. A pointer whose `catalogRevision` cannot resolve to the referenced catalog/policy snapshot is also unavailable rather than eligible for policy inference.

Pointer freshness and cutover rules:

- `pointerVersion` is monotonic per `{tenantId, worldSlug, realmSlug}`.
- Any `OPEN`/`CLOSED` transition, target-instance change, or execution-namespace change that materially changes the admitted runtime must advance `pointerVersion`. Catalog-only changes advance `catalogRevision` instead.
- The current admissible pointer is persisted in Game Session-owned control-plane state together with append-only pointer audit events; gameplay clients and bootstrap flows consume the read surface derived from that state rather than local config snapshots.
- Connect-token issuance and other admission-critical flows must fail closed if the selected realm target no longer resolves to the same admissible `pointerVersion` and `catalogRevision` they were issued against. Bootstrap bundles and connect tokens carry both references; a catalog/policy revision change cannot be hidden behind an unchanged runtime pointer version.
- Realm cutover must therefore look like a control-plane pointer move, not a client-side reinterpretation of slugs or instance names.
- **Target transaction boundary:** the persistence key and uniqueness constraint are `{tenantId, worldSlug, realmSlug}`. Existing-route mutations require an expected positive version and use one atomic database conditional write; checking a version in memory before an unconditional update is not compare-and-set.
- In that target, the pointer, append-only audit event, and idempotent request outcome commit atomically when held in the Game Session database. A replacement cutover that changes `gameInstanceId` additionally commits its prepared-cutover execution state in that transaction; ordinary `OPEN` or `CLOSED` updates do not require a prepared upgrade.
- The pointer governs new or renewed bindings. An already connected player remains authorized by the bound game instance and its runtime fences until the explicit bounded source drain ends; ordinary actions do not re-read pointer authority or eject the player merely because the pointer advanced.

## Account-to-Game Relationships

- Players have a **single platform account** managed by the **Account Service**.
- Registration creates only that global account and its security identity. It does not select a tenant or implicitly create membership, roles, a tenant profile, a character, an entitlement, or gameplay authority; the explicit public-game `JOIN` contract creates membership.
- Authentication is global at the `accountId` level. Tenant-owned reads and writes check the requested `tenantId` against the account’s allowed tenants; platform-global account and security records remain account-scoped and must not require a synthetic or unrelated `tenantId`.
- The same account can join multiple games. Each game is identified by a `tenantId`.
- `tenantId` is the authoritative tenant identifier owned by the Game Design Service. Identifier naming and format conventions are defined in [Identifier Glossary](./system-architecture-identifier-glossary.md).
- Persistence models must treat `tenantId` as an opaque identifier, not as a user-facing value.
- Gameplay clients may select worlds using `tenantSlug` values returned by `WORLDS` in the lobby flow, but `tenantSlug` must never be used as a substitute for `tenantId` in APIs or persistence outside of lobby selection.
- Gameplay clients select a world and optional realm in the lobby flow, and the server resolves that selection to canonical `{tenantId, gameInstanceId}` values through the realm-routing contract.
- Character ownership is scoped per `tenantId`, so a player may have different characters in different games. Realm-resolved playable state may either reuse tenant-shared character state or use isolated state scoped to the selected `gameInstanceId`.
- Friend lists and guilds are maintained by the Social & Groups Service. Per-game friendships store `tenantId` plus player IDs, while account-to-account friendships reference global account IDs.
- Tenant roles, profiles, characters, purchases, subscriptions, entitlements, and grants are tenant-scoped relationships or records. Gameplay state is tenant-owned but may be shared-state or realm-/instance-scoped according to realm policy, with isolated state keyed by `gameInstanceId`. Leaving one game changes only that relationship under its retention rules and does not delete the account or unrelated relationships.
- Tenant operators may see only the minimum account reference and tenant-owned data authorized for their tenant. Global credentials, recovery state, linked external identities, security history, and the existence or contents of unrelated tenant relationships are platform authority and must not be exposed through tenant roles.
- Global username and email uniqueness, internal platform correlation, and the cross-game effect of account compromise, security lock, recovery, and deletion are accepted consequences of this identity model. The account row must not carry a default or owning `tenantId`.

## Data Separation per Service

- All microservices connect to a single PostgreSQL instance and store data in service-specific schemas.
  Migrations create tables directly inside dedicated service schemas rather than the `public` schema.
- Databases are **shared across tenants**. Tenant-owned records carry and enforce `tenantId`; genuinely platform-global records such as the core account identity do not acquire a placeholder tenant merely to satisfy this convention. Relationships between a global record and a game live in explicit tenant-scoped tables. Domain services also scope their versioned data by `version_id` so multiple published or draft configurations can coexist per tenant.
- Services enforce the `tenantId` filter on queries for tenant-owned data to prevent cross-game access. They do not apply tenant filters to platform-global account, credential, recovery, or security records; links between those records and a game are represented by explicit tenant-scoped relationships and authorized separately.
- Redis keys prefix the `tenantId` as described in the
  [Redis Architecture](./system-architecture-redis.md#key-format-examples) so
  cached session state and runtime data remain isolated. For tick-related keys,
  this prefix is combined with a region identifier into a single normalized
  region hash tag token (for example `tick:{tenantRegionTag}:lock:<entityId>`),
  ensuring both **tenant isolation** and **shard-local atomic operations**
  within a region.
- The React frontend loads per-tenant, version-scoped assets from a published
  `manifest.json` in object storage; the Game Design Service is not queried at
  runtime.
- See [Game Customization](./system-architecture-game-customization.md) and the
  [Frontend Architecture](./system-architecture-frontend.md) for details.

## Tenant Configuration & Scaling

- Game-specific settings—such as world size and tick intervals—are stored in
  configuration tables keyed by `tenantId`.
  Runtime flag behavior is described in
  [Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md).
- Creating a new game world triggers a Saga across services.
  The steps are outlined in
  [World Creation Workflow](./microservices/world-management-service/world-creation-workflow.md).
- All microservices run as shared deployments; there is **no tenant-specific infrastructure**, selectively dedicated data plane, or dedicated tenant cluster in the supported multi-tenant topology.
- Game Session Service instances scale horizontally based on overall load.
- Operations may run more than one instance per tenant. The player-facing contract exposes whichever realms the caller is authorized to see, and each open realm resolves to exactly one admissible `gameInstanceId` at a time. One shared world scales through Game Session pods, region partitioning, and fenced lease rebalancing inside that instance; intentionally separate worlds or shards use separately addressable realms.
- Per-game resource quotas ensure one tenant cannot exhaust cluster capacity. Account Service is the owner of the entitlement and plan-limit contract, but `GetTenantEntitlementsForRuntime(tenantId)` returns values bound to exactly that `tenantId`, with its authority/version context. A membership in another tenant, a global role, or a cross-tenant read cannot inherit or reuse those values; missing or ambiguous target binding fails closed. Metrics expose current usage so operators can track `active_sessions`, quota denials, and the impact of billing state on availability (for example, `suspended` or `canceled` tenants cannot start new instances or admit new player sessions even if raw capacity is available).

This topology deliberately accepts an environment-wide infrastructure, backup, restore, and major-incident blast radius. FireMUD does not promise tenant-local upgrades, maintenance windows, data residency, cryptographic isolation, or disaster recovery inside one environment. If a demonstrated contractual or regulatory requirement later needs hard infrastructure isolation, the bounded escape path is a separately operated complete FireMUD environment after explicit review, not tenant-aware routing among selectively dedicated databases, Redis deployments, or services inside the shared environment.

### Quota Enforcement Responsibilities

Quotas are enforced at multiple layers, each with a clear scope:

- **Network and API rate quotas (Gateway and TCP Proxy Service):**
  - Spring Cloud Gateway enforces per-IP and per-connection handshake/request limits for HTTP and WebSocket traffic using Cache/Rate-Limit Redis. For gameplay WebSockets, the gateway does not apply tenant-aware rate limits based on post-login traffic; tenant-aware gameplay quotas are enforced in Game Session after `LOGIN` binds the session.
  - TCP Proxy Service enforces per-IP and per-socket safety caps for legacy Telnet clients (connection limits, line-rate budgets, buffer ceilings, idle timeouts). Like the gateway, it does not assume a tenant identity before gameplay login binds a session.
  - Both components expose edge-throttling metrics so operators can distinguish edge-safety enforcement from tenant-aware quotas enforced in downstream services.

- **Gameplay command and tick quotas (Game Session Service):**
  - Game Session Service enforces per‑tenant budgets for active sessions, queued commands, and tick workload (for example, maximum commands per tick per tenant, maximum concurrent sessions per tenant).
  - When quotas are exceeded, Game Session may reject or defer new sessions and commands for that tenant, shed low‑priority work, or apply backpressure while preserving core gameplay invariants.
  - Quota decisions are driven by tenant entitlements and regional capacity; metrics and logs record when quotas are enforced so that support and operators can investigate.

- **Storage and long‑lived state quotas (PostgreSQL and object storage):**
  - Storage usage is tracked per tenant via `tenantId` in PostgreSQL schemas and per‑tenant prefixes in object storage.
  - Alerts and dashboards in Logging & Admin Service surface when tenants approach storage thresholds so operators can work with creators to clean up data or upgrade plans.

Account Service remains the **source of truth** for entitlements and plan details, while enforcement is carried out by Game Session Service and other domain services (tenant-aware), plus the Gateway/TCP Proxy (edge-safety). When new quota types are introduced, their enforcement point and metrics must be documented alongside the entitlement fields that drive them.

---

> 🔗 For service roles and interactions, see the
> [System Architecture Overview](./system-architecture-overview.md) and the
> [Service Responsibility Matrix](./service-responsibility-matrix.md).
