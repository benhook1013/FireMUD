# FireMUD System Architecture: Multi-Tenancy

This document explains how FireMUD hosts many independent games on shared infrastructure. It complements the [System Architecture Overview](./system-architecture-overview.md) and the multi-tenant requirements in the [Product requirements](../product/requirements.md).

## Normative Target Contract

Tenant identity, explicit public-production membership creation, realm-local gameplay scope, caller-bound authorization, and exact target routing are one contract. Global roles may authorize only explicitly classified control-plane routes; they never create tenant membership, bypass target-scope checks, or grant gameplay admission. All player-facing admission and tenant-scoped mutations fail closed when authoritative membership, entitlement, routing, or generation evidence is unavailable or ambiguous.

Scripting pins and rollout history are tenant/game-instance scoped: Game Session owns the exact `(scriptPatchVersion, scriptPinEpoch)` for each instance, and Automation's observed pin projection is keyed by `(tenantId, gameInstanceId)` and carries that exact observed version/epoch tuple. Automation-owned instance admission requires both a fresh authoritative Game Session result for that exact tuple and a fresh, matching Automation projection carrying the same tuple; the projection is mandatory evidence at this boundary but is never authority, and only the authoritative Game Session result can authorize the admission. Tenant-scoped readiness only says a patch is eligible for an explicit instance pin; it never authorizes instance work. For a promotion/rollback workflow carrying a canonical request identity, the owner-committed `controlPlaneRequestId` must also match in the applicable projection/request evidence; ordinary gameplay/runtime ingress does not carry that owner request field. Any stale, unverified, missing, unavailable, ambiguous, or mismatched tuple/result/projection evidence, and any applicable request-identity mismatch, fails closed for such work, including for the same tenant and game instance. An authoritative `UNPINNED` result is instead the valid no-script state for that tenant/instance and produces no script work; it must not be inferred from missing, stale, or unavailable authority. The exact identity, epoch, and `UNPINNED` semantics belong to [Scripting Contracts](./system-architecture-scripting-contracts.md); this document owns the tenancy and routing consequence.

Replacement keeps the logical playable-state identity separate from runtime routing. `playableStateNamespaceId` is stable for a shared tenant realm or an isolated realm lifecycle and changes only when an intentional new playable-state lifecycle is created, including a new isolated realm, playtest, fork, or fresh standalone lifecycle; replacing its runtime does not create a new namespace. `gameInstanceId` identifies the concrete runtime selected by the admission pointer. Durable state intended to survive replacement is authorized and keyed by the namespace plus the active-instance fence, while disposable runtime state remains instance-scoped. See [ADR 0122](./decisions/adr-0122-stable-playable-state-namespaces-for-runtime-replacement.md).

## Implementation Status

The realm-catalog, admission-pointer, realm-local character, explicit first-join, and quota-boundary contracts below are normative target behavior. Current runtime proof is partial: `IssueConnectToken` and text `PLAY` consume the current Account membership adapter evidence `membershipExists`, `gameplayAdmissionAllowed`, `membershipVersion`, and `evaluatedAt` (with the realm grant read separately where applicable) and admit only when the membership and admission flags are true; the adapter has no lifecycle-state field. An eligible public-production request with `membershipExists=false` may return `JOIN_REQUIRED`; a current `membershipExists=true` and `gameplayAdmissionAllowed=false` response is existing but non-admitting and cannot be called `INACTIVE`. Current text `PLAY` maps missing or non-admitting membership for a non-public target to `WORLD_ACCESS_DENIED`; the connect-token path retains its current Account rejection mapping. The obsolete implicit membership-writer surface has been removed; the membership-generation live reread at connect-token issuance is not implemented; pointer updates still use a read-then-write check with separate pointer/audit/prepared-execution writes; scoped-role population and tenant switching are not fully implemented/proved; authoritative entitlement freshness plus complete owning-service quota enforcement remain incomplete; and broader authority-generation claims, durable projections, and enforcement remain incomplete across issuer, account, tenant, and membership scopes. Explicit `JOIN`/`Join & Play` remains unimplemented, so the first-join flow remains target-state only. Current Game Session bootstrap/reconnect context remains on the implementation-local `sessionctx:*` family, including the unscoped bootstrap key `sessionctx:session:<sessionId>:context` and the tenant-scoped keys `sessionctx:<tenantId>:<sessionId>:context`, `sessionctx:<tenantId>:identity:<gameInstanceId>:<characterId>:context`, and `sessionctx:<tenantId>:identity:<gameInstanceId>:name:<characterName>:context`; these old per-instance identity keys and the current `{tenantId, gameInstanceId, characterId}` uniqueness are implementation drift. The target-only `session:game:{tenantGameplayTag}:...` family uses `{tenantGameplayTag}` as a server-derived, deterministic, injective projection of authoritative `tenantId`, allocated through an authoritative persisted tenant-to-tag mapping with an immutable allocation version. Its character uniqueness projection is keyed by `{tenantId, playableStateNamespaceId, playableStateScope, characterId}`, while its value/evidence retains `{sessionId, gameInstanceId, bindingGeneration}`. Existing mappings are always reused; a new allocation is committed atomically with uniqueness for both the tenant and tag, and a candidate tag already mapped to another tenant is rejected even when the candidate allocation version differs. Algorithm or encoding-version changes preserve existing mappings and may affect only future tenants. Its concrete encoding and version remain opaque to clients and other services; `tenantId` remains authoritative in APIs and persistence. Required focused proof covers distinct-tenant separation, same-tenant stability across process restart with the persisted assignment/version, concurrent first allocation returning one assignment, rejection of a tag collision across allocation versions, and rejection of an active-binding `bindingRef` whose tenant differs from the requested tenant. No end-to-end proof yet demonstrates these target invariants. The remaining gaps do not change the distinct `tenantSlug` and tenant-scoped authored-world `worldSlug` selectors, the exact-tenant quota binding, or the required `GetAdmissionPointer(tenantId, worldSlug, realmSlug)` contract.

---

## Identity & Tenant Model

FireMUD separates **global identity** from **per-game state** so that one person can participate in multiple games without data leakage between tenants.

- **Platform account (`accountId`)** – A global identity record managed by the Account Service. Each human player has a single platform account, which is the subject of authentication and JWT issuance.
- **Tenant (`tenantId`)** – A hosted game world or project. Each tenant represents one game created on the platform and may have one or more running game instances. The Game Design Service owns `tenantId` issuance.
- **Tenant slug (`tenantSlug`)** – A stable, human-friendly identifier owned by the Game Design Service. Slugs are used only as **player-facing selectors** in the post-login lobby flow (`WORLDS` / `REALMS` / `CHARS` / `PLAY`) and are resolved server-side to `tenantId`; services and persistence models continue to use `tenantId` as the authoritative tenant identifier. See [ADR 0005: Tenant Identifiers in Gameplay Protocol](./decisions/adr-0005-tenant-identifiers-in-gameplay-protocol.md) for the required slug stability rules.
- **World slug (`worldSlug`)** – A stable tenant-scoped selector for one authored world inside the tenant/game. It is resolved only together with `tenantId` and is paired with `realmSlug` by the realm catalog and admission-pointer contract. It is not an alias for `tenantSlug`, does not identify a tenant across the platform, and must not be inferred from display metadata, `realmSlug`, or `gameInstanceId`.
- **Game instance (`gameInstanceId`)** – A specific running instance of a tenant’s world, keyed as described in [Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md#version-activation--rollback). Runtime APIs and disposable instance-data keys include `gameInstanceId` explicitly; durable playable state that survives replacement additionally carries the stable `playableStateNamespaceId` and active-instance authorization.
  - A tenant may expose one or more **player-addressable realms**. Each realm is explicitly `OPEN` on exactly one admissible `gameInstanceId` or `CLOSED` with none through the canonical realm-catalog/admission-pointer contract owned by Multi-Tenancy and served at runtime by Game Session.
  - Exactly one visible, player-addressable realm must be flagged `publicProduction=true` in the separately revisioned Game Session catalog/policy record for each tenant. Zero or multiple public-production realms are invalid catalog state: public discovery, first join, connect-token issuance, and `PLAY` must fail closed rather than selecting a fallback. The admission pointer contains only `OPEN(gameInstanceId)` or `CLOSED` routing authority plus its `pointerVersion`; callers consume the catalog/policy flag rather than inferring public-production behavior from a slug.
  - Additional realms are explicitly authorized non-production realms such as playtest forks. They are never public-discovery realms in v1 and require explicit access grants owned by Account Service and evaluated through the same runtime grant authority across bootstrap discovery and gameplay admission.
  - Additional running instances may still exist for operational workflows, but only realms surfaced through the authenticated lobby contract are player-addressable.
- **Account–tenant membership and roles** – For each tenant a platform account participates in, the platform records membership and roles (for example, `player`, `designer`, `tenantAdmin`) that appear in JWT `scopedRoles[tenantId]` claims. Membership is many-to-many: one account can join many tenants, and each tenant can host many accounts.
- **Character (`characterId`)** – A gameplay identity controlled by a platform account within a specific tenant. Character identity is tenant-scoped; target gameplay session uniqueness is `{tenantId, playableStateNamespaceId, playableStateScope, characterId}`, while the binding value/evidence retains `sessionId`, `gameInstanceId`, and `bindingGeneration`. Durable character state, inventories, equipment, and progress intended to survive replacement use `playableStateNamespaceId` plus the active-instance fence.
- **Gameplay session** – A transient session binding between a connected client and a character in a tenant, managed by the Game Session Service. The target-only session record family is `session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>`; current Game Session context remains on `sessionctx:*`. Canonical session records are tenant- and instance-scoped and never change the global `accountId`. The account-wide active-binding index `session:game:index:account:<accountId>` is an explicit physical-key exception: one untagged key spans tenants, but every member carries the complete generation-safe tenant-qualified `bindingRef`; it is bounded lookup evidence, not authorization. Sessions bind sockets to character identities within a tenant (see [Authentication & Authorization](./system-architecture-authentication.md#login-and-session-flow) and [Session Behavior](./system-architecture-session-behavior.md#session-and-identity-management)).
  - Target uniqueness for takeover/resume is enforced as `{tenantId, playableStateNamespaceId, playableStateScope, characterId}` with `bindingGeneration`; the existing `{tenantId, gameInstanceId, characterId}` key is current implementation drift.
- **Target auth token session** – A short-lived issued-token registry record (`session:auth:token:<tokenHash>`) backing a revocable JWT for meta/control or admission APIs, as described in [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md). Current Account credential-login sessions use the legacy `session:auth:account:<accountId>:<tokenHash>` key and, for tenant-scoped sessions, the companion `session:auth:tenant:<tenantId>:<tokenHash>` key instead.
- **Control-plane browser session** – A front-end admin/creator UI session that holds short-lived JWTs in memory; target server-side revocation relies on auth token sessions, while current Account authentication retains the legacy session keys above. These are distinct from gameplay sessions and are described in the Frontend Architecture and Authentication designs.

### Authority Generations Across Tenant States

Account Service owns the issuer, account, tenant, and caller-bound membership authority generations and their projections. The exact global-role branches, route-class exceptions, target-tenant generation predicates, and assurance requirements are canonical in the [Authorization Route Matrix](./system-architecture-authz-route-matrix.md#classification-rules). Multi-tenancy's local consequence is that no role or route may use cached authorization, infer tenant access from a global role, or let a new route class inherit an exception implicitly.

If Account authority or its required projection is unavailable, the operation fails closed with retryable `AUTH_UNAVAILABLE`. UI clients retain in-memory state for retry, but no cached JWT role, membership, generation, or allowlist result is authorization.

Fresh authoritative evidence is required for admission, authority renewal, reconnect, and tenant-scoped control-plane mutations. Those paths fail closed when issuer, account, tenant, membership, entitlement, routing, or generation evidence is missing, unavailable, stale, or ambiguous. Already-bound ordinary gameplay is different: after a session is bound to a resolved `gameInstanceId`, ordinary gameplay uses the bound instance and runtime fences without rereading pointer authority for every action. Reconnect, renewal, and any new admission must establish fresh evidence again.

The only resume exception is the bounded last-known-good entitlement input allowed by [ADR 0028](./decisions/adr-0028-differentiated-entitlement-freshness.md). Its exact entitlement-continuity predicate is: the same existing still-resumable binding and resolved target, the same valid resume episode and deadline, a previously observed positive authoritative entitlement snapshot whose `evaluatedAt` age remains strictly below five minutes under ADR 0028's configured skew-adjusted freshness predicate, no observed hard denial, operation-specific denial, authority revocation, newer billing sequence, or sequence gap, and no capacity or quota expansion. This exception tolerates unavailability of entitlement evaluation only; it never substitutes for fresh, fail-closed issuer, account, tenant, membership, authority-generation, private-realm grant, token-identity-fence, revocation, routing, lease, or other identity/authority evidence. Missing, unavailable, stale, regressed, malformed, or ambiguous evidence in any of those predicates denies resume.

### Realm State Model

Tenant membership, tenant roles, and character ownership answer "which game does this account belong to?" They do not imply that every realm inside a tenant shares one undifferentiated set of gameplay state.

FireMUD distinguishes between:

- **Tenant-scoped identity and ownership** – platform accounts, tenant membership, role grants, and the fact that a character belongs to a tenant.
- **Realm- or instance-scoped playable state** – the gameplay state that applies when a player enters a specific realm resolved to a specific `gameInstanceId`, with its durable identity determined by the realm's stable `playableStateNamespaceId`.

Realm-scoped playable state may follow either of these patterns:

- **Shared-state realm** – the realm uses the tenant's stable shared playable-state namespace, so the same character identity, progression, and durable inventory are reused when the player enters that realm or its replacement runtime.
- **Isolated-state realm** – the realm uses its own stable realm/playtest `playableStateNamespaceId` rather than the tenant's normal live namespace. That isolated state may start from a copied source snapshot, seeded/sample data, or fresh standalone state. Playtest forks are the canonical copied-state example: copied characters remain associated with the same platform account and tenant, but the fork keeps its own namespace and fork-local progression, inventory, and other durable runtime state across replacement.

Minimum downstream consequences of realm policy:

- Shared-state realms reuse the tenant's normal live gameplay state namespace for the selected character.
- Isolated-state realms must treat at least the following as realm-scoped playable state keyed to the resolved `playableStateNamespaceId` and authorized against the active `gameInstanceId`:
  - visible character roster for `CHARS`;
  - character progression/resources;
  - durable inventory/equipment/containment state;
  - learned or pinned gameplay loadout/state that materially affects play in that realm.
- Tenant membership, account ownership, billing state, and cross-tenant control-plane identity remain tenant-scoped regardless of realm mode.
- Social/account-level relationships may remain tenant- or account-scoped unless a dedicated gameplay design says otherwise, but they must not be used to silently collapse isolated gameplay state back into the tenant's live production state.

Character-selection and creation policy must also respect realm mode:

- `CHARS` lists the character choices valid for the resolved `{tenantId, playableStateNamespaceId, gameInstanceId}` target, not a tenant-wide superset; the namespace identifies durable playable state and the instance provides the active authorization fence.
- Shared-state realms normally expose the tenant's normal live durable roster for that account.
- Isolated-state realms expose only that realm's valid roster, which may consist of copied fork-local characters, seeded/sample characters, newly created realm-local characters, or a policy-defined subset of those.
- `CHARS` must return one realm-local decision surface for the selected target: the visible roster plus a bounded `creationPolicy` / equivalent flag explaining whether fresh character creation is allowed, denied, or limited to a documented realm-local mode. Clients must not infer creation rules by comparing roster contents to tenant-wide state.
- If an isolated realm forbids fresh character creation, admission must fail with an explicit character-selection denial rather than implying the caller may create into the tenant's normal live roster.
- If an isolated realm allows both copied characters and fresh realm-local creation, both appear as one realm-local roster/creation experience for that target. The client must not require a separate "copied vs new" mode switch to enter the realm.

This distinction is normative for all realm-aware flows:

- `REALMS` describes which player-addressable realms exist for a tenant.
- `CHARS` lists the character choices valid for the resolved `{tenantId, playableStateNamespaceId, gameInstanceId}` target.
- `PLAY` binds the session to the target `{tenantId, playableStateNamespaceId, playableStateScope, characterId}` identity and retains the active runtime/value evidence `{gameInstanceId, sessionId, bindingGeneration}`.

Services must therefore avoid collapsing "character belongs to tenant" into "all character-associated data is keyed only by tenant" or treating a replaceable runtime as durable identity. Tenant ownership and playable-state namespace remain stable, while disposable runtime state may be recreated per `gameInstanceId` according to the resolved realm policy.

This model underpins both authentication and authorization:

- Authentication always resolves a single platform `accountId`.
- Tenant-scoped control-plane authorization combines the authenticated `accountId` with tenant-scoped roles from `scopedRoles[tenantId]`, plus any cross-tenant `globalRoles` such as `platformAdmin`, as described in [Authentication & Authorization](./system-architecture-authentication.md).
- Gameplay admission is stricter: player-facing `WORLDS` / `REALMS` / `CHARS` / `PLAY` selection uses caller-bound tenant membership, public-production admission policy, and entitlement checks, and global roles alone do not grant gameplay admission.
- Public-production `Join & Play` (text `JOIN`) is the sole exception to the existing-membership rule for tenant-owned writes and the only gameplay-adjacent operation that may change a durable `player` membership. Its exact lifecycle is missing -> `ACTIVE` and `INACTIVE` -> `ACTIVE`, each with a `membershipVersion` advance; `membershipAuthorityGeneration` advances independently only when `callerBoundAuthorityInvalidated=true`; `ACTIVE` returns the exact idempotent current snapshot and all other states reject. The Account-owned operation validates catalog visibility, public-production admission policy, tenant entitlement, admission routing, caller binding, and idempotency before committing membership. `JOIN_REQUIRED` covers missing or `INACTIVE` membership after policy permits joining. Character creation, connect-token issuance, and `PLAY` require the resulting caller-bound membership and never create or restore it implicitly.
- Private/playtest admission requires existing caller-bound `membershipLifecycleState=ACTIVE` and the current Account-owned access grant. The grant is checked independently, never substitutes for membership, and never auto-creates or restores membership.
- These membership prerequisites are limited to player-facing gameplay admission and gameplay-adjacent membership writes. They do not replace the explicit control-plane route-class authorization matrix: `platformAdmin`, `support`, and `billingAdmin` global-role branches remain available only on route classes that explicitly allow them and still require their declared scope, target-generation, privileged-control, and live Account checks. Membership is not a blanket prerequisite for an allowed global-role control-plane route, and a global role is not a substitute for gameplay membership.
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
- `stateScope` (`SHARED` or `ISOLATED`), the canonical playable-state policy for the exact realm snapshot
- `playableStateNamespaceId`, the opaque stable namespace allocated by the realm-catalog owner for this playable-state lifecycle and bound to this exact `catalogRevision`
- `characterCreationPolicy`
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

`catalogRevision` is the one canonical catalog-only monotonic field. It identifies the separately versioned catalog/policy snapshot for the stable `{tenantId, worldSlug, realmSlug}` identity; the snapshot contains visibility, access-policy, `publicProduction`, `stateScope`, and the policy-bound `playableStateNamespaceId` facts. The routing record stores that revision as its catalog/policy reference. There is no separate `policyRevision` or second policy counter. Display metadata and other catalog-only edits advance `catalogRevision`, not the runtime `pointerVersion`, and therefore do not invalidate a committed gameplay binding. Admission-policy reads must evaluate `publicProduction` and the other policy facts from the snapshot identified by the pointer's `catalogRevision`; callers must not infer public-production status from a slug, a current mutable catalog read, or an omitted revision. Admission-policy reads still revalidate current visibility, public-production, grant, and entitlement facts before creating or renewing authority. An uncommitted discovery, join, issuance, or lease result carrying a stale catalog revision or pointer version is rejected and requires rediscovery; it cannot be silently upgraded to the current pair. A public-production membership write is permitted only through explicit `Join & Play` (text `JOIN`) under the exact missing/`INACTIVE`/`ACTIVE` lifecycle above; private/playtest reads must prove existing `ACTIVE` membership and the current grant and never invoke a membership writer.

For `CHARS`, the exact catalog snapshot referenced by `catalogRevision` is also the source of the realm's playable-state policy. Game Session resolves `stateScope=SHARED` to `playableStateScope=PLAYABLE_STATE_SCOPE_SHARED` and `stateScope=ISOLATED` to `playableStateScope=PLAYABLE_STATE_SCOPE_ISOLATED`; the stable `playableStateNamespaceId` and `catalogRevision` must be resolved from and bound to that same exact snapshot. Game Session carries the resulting snapshot proof with the exact `{tenantId, worldSlug, realmSlug, playableStateNamespaceId, playableStateScope, gameInstanceId, catalogRevision, pointerVersion}` target through `CHARS`, character creation, and `PLAY`, and each boundary fails closed when the proof is missing, stale, ambiguous, or internally inconsistent. The namespace is a policy-bound storage identity, never a client-selected value or a value derived from Redis/PostgreSQL key names, `gameInstanceId`, or roster contents. Character-query input remains owned by the Entity Management API contract.

Connect-token admission branches by the resolved target mode. After required routing and authority-availability checks, public production evaluates the current public-production joining policy for the selected target. If that policy disallows joining, the result is `PUBLIC_PRODUCTION_ADMISSION_DENIED` regardless of whether membership is missing or `INACTIVE`; `JOIN_REQUIRED` is returned only when the policy permits joining and the fresh membership snapshot is missing or `INACTIVE`. Unavailable entitlement or pointer authority remains its own unavailable outcome rather than either code. Private/playtest admission never evaluates public-joining policy and requires existing `ACTIVE` membership plus the exact current realm-access grant, returning `NON_PUBLIC_ENROLLMENT_REQUIRED` rather than `JOIN_REQUIRED` for missing or `INACTIVE` membership. Both branches preserve the live entitlement, lifecycle, membership-generation, membership-version, and admission-pointer checks, and neither branch creates or restores membership.

Contract rules:

- `REALMS`, `CHARS`, `PLAY`, bootstrap discovery, connect-token issuance, and reconnect validation must all consume the same realm-catalog and admission-pointer truth.
- Clients never select raw `gameInstanceId` values directly. They select a world and optional realm, and the server resolves that choice to the current admissible runtime target.
- Each player-addressable realm has at most one admissible `gameInstanceId` at a time. `OPEN` names exactly one target; `CLOSED` names none.
- Public-production onboarding and first-join membership creation are controlled by the catalog/policy revision's `publicProduction` value plus visibility and entitlement checks, not by a duplicated routing flag or by comparing `realmSlug` with a reserved string.
- Catalog validation must find exactly one `publicProduction=true` realm among the visible, player-addressable realms for each tenant. Hidden or non-player-addressable records do not satisfy the count or authorize public discovery/admission. A zero or multiple qualifying realms is ambiguous authority and fails closed; callers must not infer one from ordering, an unvalidated record, or a reserved slug.
- An explicitly `CLOSED` realm may remain visible with an unavailable/maintenance presentation, but ordinary gameplay admission returns `REALM_UNAVAILABLE`; `CLOSED` is a deliberate authoritative state, not a missing pointer. Reachable missing, malformed, ambiguous, stale, or otherwise invalid routing evidence maps to `ADMISSION_POINTER_UNAVAILABLE`; an unreachable or timed-out pointer authority maps to `AUTH_UNAVAILABLE`. Callers must not confuse pointer-authority failure with deliberate closure or guess a replacement target.

Required read contract:

- `GetAdmissionPointer(tenantId, worldSlug, realmSlug)` is the authoritative gameplay-admission lookup.
- Multi-Tenancy owns the realm-catalog/admission-pointer identity and `OPEN`/`CLOSED` semantics. Game Session remains the runtime owner that persists, mutates, and serves the pointer read; Authentication owns admission and continuation authorization, while Gateway owns edge carrier, replay, and signed-context authorization. See [Authentication & Authorization](./system-architecture-authentication.md#admission-routing-convergence-rule) and [Gateway architecture](./system-architecture-gateway.md#tenant-aware-edge-connect-token-gameplay-handshake) for those consuming contracts.
- Callers must treat missing required pointer fields, ambiguous results, or stale pointer state as contract failures rather than inferring defaults. A complete `CLOSED` record is not an incomplete pointer.
- The result mapping is stable: complete `CLOSED` authority maps to `REALM_UNAVAILABLE`; reachable missing results, malformed required fields, ambiguous or stale evidence, and a `catalogRevision` that cannot resolve to the referenced catalog/policy snapshot map to `ADMISSION_POINTER_UNAVAILABLE`; an unreachable or timed-out authority maps to `AUTH_UNAVAILABLE` rather than pointer-invalid evidence.

Pointer freshness and cutover rules:

- `pointerVersion` is monotonic per `{tenantId, worldSlug, realmSlug}`.
- Any `OPEN`/`CLOSED` transition, target-instance change, or execution-namespace change that materially changes the admitted runtime must advance `pointerVersion`. Catalog-only changes advance `catalogRevision` instead.
- The current admissible pointer is persisted in Game Session-owned control-plane state together with append-only pointer audit events; gameplay clients and bootstrap flows consume the read surface derived from that state rather than local config snapshots.
- Connect-token issuance and other admission-critical flows must fail closed if the selected realm target no longer resolves to the same admissible `pointerVersion` and `catalogRevision` they were issued against. Bootstrap bundles and connect tokens carry both references; a catalog/policy revision change cannot be hidden behind an unchanged runtime pointer version.
- Target bootstrap discovery snapshots must return `connectScopeId`, `tenantId`, `worldSlug`, `realmSlug`, `playableStateNamespaceId`, `playableStateScope`, `gameInstanceId`, the exact `catalogRevision` and `pointerVersion` pair, `evaluatedAt`, and `connectScopeExpiresAt`. The snapshot is short-lived proof of the evaluated target, not a reservation. `JoinPublicProductionMembership` must re-resolve the target and require that exact pair at its membership commit gate. Generated proto and caller support for carrying and proving this complete snapshot through the join commit gate remains implementation drift.
- Realm cutover must therefore look like a control-plane pointer move, not a client-side reinterpretation of slugs or instance names.
- **Target transaction boundary:** the persistence key and uniqueness constraint are `{tenantId, worldSlug, realmSlug}`. Existing-route mutations require an expected positive version and use one atomic database conditional write; checking a version in memory before an unconditional update is not compare-and-set.
- In that target, the pointer, append-only audit event, and idempotent request outcome commit atomically when held in the Game Session database. A replacement cutover that changes `gameInstanceId` additionally commits its prepared-cutover execution state in that transaction; ordinary `OPEN` or `CLOSED` updates do not require a prepared upgrade.
- A replacement cutover additionally binds World Management's one-shot `cutoverHoldId`/`cutoverHoldFence` and exact source/target `ACTIVE` lifecycle proofs in that same Game Session transaction. World remains the hold and lifecycle owner; Game Session finalizes no hold from local state and requests termination only after authoritative post-swap readback proves the hold-bound pointer, audit, prepared-execution, source-cleanup, and drain-fence commit. See [World's cutover hold contract](microservices/world-management-service/api-contracts.md#replacement-cutover-hold-contract).
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
- Character ownership is scoped per `tenantId`, so a player may have different characters in different games. Realm-resolved durable playable state may either reuse the tenant-shared namespace or use an isolated stable namespace selected by the realm policy; `gameInstanceId` remains the active runtime fence and disposable-state scope.
- Friend lists and guilds are maintained by the Social & Groups Service. Per-game friendships store `tenantId` plus player IDs, while account-to-account friendships reference global account IDs.
- Tenant roles, profiles, characters, subscriptions, and hosting/game entitlements are tenant-scoped relationships or records. Explicit account-scoped purchases, grants, and donations remain global account records without a fabricated `tenantId`; using one for a tenant-scoped feature requires an explicit tenant binding or consumption record. Gameplay state is tenant-owned but may be shared-state or realm-scoped according to realm policy, with durable isolated state keyed by `playableStateNamespaceId` and disposable state keyed by `gameInstanceId`. Leaving one game changes only that relationship under its retention rules and does not delete the account or unrelated relationships.
- Tenant operators may see only the minimum account reference and tenant-owned data authorized for their tenant. Global credentials, recovery state, linked external identities, security history, and the existence or contents of unrelated tenant relationships are platform authority and must not be exposed through tenant roles.
- Global username and email uniqueness, internal platform correlation, and the cross-game effect of account compromise, security lock, recovery, and deletion are accepted consequences of this identity model. The account row must not carry a default or owning `tenantId`.

## Data Separation per Service

- All microservices connect to a single PostgreSQL instance and store data in service-specific schemas.
  Migrations create tables directly inside dedicated service schemas rather than the `public` schema.
- Databases are **shared across tenants**. Tenant-owned records carry and enforce `tenantId`; genuinely platform-global records such as the core account identity do not acquire a placeholder tenant merely to satisfy this convention. Relationships between a global record and a game live in explicit tenant-scoped tables. Domain services also scope their versioned data by `version_id` so multiple published or draft configurations can coexist per tenant.
- Services enforce the `tenantId` filter on queries for tenant-owned data to prevent cross-game access. They do not apply tenant filters to platform-global account, credential, recovery, or security records; links between those records and a game are represented by explicit tenant-scoped relationships and authorized separately.
- Every Redis key family must use its canonical key builder and representative formats defined in the [Redis Architecture](./system-architecture-redis.md#coordination-key-examples), rather than hand-rolled strings. The target-only authenticated gameplay session family is `session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>`, where `tenantGameplayTag` is loaded or allocated through the authoritative persisted tenant-to-tag mapping defined above; `tenantId` remains the authoritative identity and validation field. Current Game Session context remains on `sessionctx:*`. For tick-related keys, this prefix is combined with a region identifier into a single normalized region hash tag token (for example `tick:{tenantRegionTag}:lock:<entityId>`). The hash tag only co-locates related keys in one Redis Cluster slot for shard-local atomic operations; it is not a tenant-isolation boundary. Tenant isolation comes from canonical key builders and owner-side tenant validation. The untagged global account active-binding index and other explicitly global families are exceptions to the physical key-prefix rule; tenant isolation for the account index is enforced by its tenant-qualified `bindingRef` members and owner-controlled validation, never by treating the global key as tenant-local authority.
- Focused proof for this boundary must demonstrate that distinct `tenantId` values produce distinct tags, a tenant's tag and allocation version remain stable across process restart, concurrent first allocation returns one persisted assignment, a candidate tag collision is rejected when another tenant owns it under a different allocation version, and the owning active-binding validator rejects a `bindingRef` from another tenant. Redis slot co-location alone is not proof of isolation.
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
- Per-game resource quotas ensure one tenant cannot exhaust cluster capacity. Account Service is the owner of the tenant-scoped hosting/game entitlement and plan-limit contract, but the `GetTenantEntitlementsForRuntime` contract, scoped by `tenantId` and `requestId`, returns values bound to exactly that `tenantId`, with its authority/version context. Explicit account-scoped purchases, grants, and donations are not runtime tenant entitlements and never supply a fabricated target; any tenant use must be represented by an explicit binding or consumption record. A membership in another tenant, a global role, or a cross-tenant read cannot inherit or reuse tenant entitlement values; missing or ambiguous target binding fails closed. Metrics expose current usage so operators can track `active_sessions`, quota denials, and the impact of billing state on availability (for example, `suspended` or `canceled` tenants cannot start new instances or admit new player sessions even if raw capacity is available).

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
