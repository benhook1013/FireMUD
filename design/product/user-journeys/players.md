# FireMUD User Journeys: Players

This guide summarizes typical player-centric workflows in FireMUD. Each numbered step links to the microservice or design document that manages that portion of the flow. Use it alongside the [Architecture Overview](../../architecture/README.md), the [System Architecture Overview](../../architecture/system-architecture-overview.md), the [System Architecture Diagram](../../architecture/system-architecture-diagram.md), and the [System Context Diagram](../../architecture/system-context-diagram.md) to understand how players traverse the platform. For a breakdown of every service see the [Microservices Overview](../../architecture/microservices/README.md) and the [Service Responsibility Matrix](../../architecture/service-responsibility-matrix.md).

For creator and operator workflows, see:

- [Creator Journeys](./creators.md)
- [Operator Journeys](./operators.md)
- [User Journeys Hub](./overview.md)

Accounts span multiple hosted games. The [Multi-Tenancy](../../architecture/system-architecture-multi-tenancy.md) model explains how characters and worlds remain isolated under a single platform account.

These journeys define observable product behavior and user-facing outcomes; technical contracts remain in the linked architecture documents.

## Table of Contents

- [Goals](#goals)
- [Implementation Status](#implementation-status)
- [Quick Reference](#quick-reference)
- [1. Sign Up](#1-sign-up)
- [2. Join a Game for the First Time](#2-join-a-game-for-the-first-time)
- [3. Character Creation & Selection](#3-character-creation--selection)
- [4. Player Login and Gameplay](#4-player-login-and-gameplay)
- [5. Social Interaction & Safety](#5-social-interaction--safety)
- [6. Purchases and Subscriptions](#6-purchases-and-subscriptions)
- [7. Password Resets & Account Recovery](#7-password-resets--account-recovery)
- [8. Switch Games or Manage Multiple Games](#8-switch-games-or-manage-multiple-games)
- [9. Account Data Export](#9-account-data-export)
- [10. Account Deletion](#10-account-deletion)
- [Related Documentation](#related-documentation)

---

## Goals

- Provide a quick reference for how a player moves through the system.
- Map each step to the microservice that owns the logic or data from a player’s point of view.
- Link back to deeper design docs for anyone who needs additional context.

---

## Implementation Status

The target journeys below are primary. Current runtime status is concise: explicit `JOIN` / `Join & Play` and the complete realm-scoped character gate remain unimplemented; existing credential-bearing clients may still use direct `LOGIN` -> `PLAY` when they already have usable membership and a character. Connect-token issuance and text `PLAY` require existing membership and return `JOIN_REQUIRED` for eligible missing or `INACTIVE` public-production membership. Realm-aware discovery is implemented at the backend boundary, but the richer character-creation descriptor remains a gap. Account export is still Account/profile-only, and the complete cross-service deletion workflow is not yet proved. See the [Account Service export contract](../../architecture/microservices/account-service/api-contracts.md#current-vs-target-export-lifecycle), [ADR 0043](../../architecture/decisions/adr-0043-global-account-lifecycle-and-bounded-erasure-workflow.md#decision), and [ADR 0050](../../architecture/decisions/adr-0050-versioned-export-retention-and-erasure-policy.md#decision) for those implementation boundaries.

Current gameplay supports room views, movement, foundational inventory/container/equipment commands, and room-local `SAY`, `WHISPER`, and `TELL`. Guild chat and mail remain deferred target behavior and are not current player-runtime capabilities. The shared current communication path includes metadata-only whisper observers and recipient-side delivery for generic WebSocket and Telnet clients; broader audible scopes and first-party/MCP-aware presentation remain gaps. The target player-facing report ingress is not currently available; current support is limited to an internal service-to-service report-persistence seam, not player ingress.

Fixed-category restriction enforcement, safe notices, and bounded moderation appeals are target behavior and are not complete in the current runtime. Account, Game Session, and Social & Groups owner-local enforcement and the Logging & Admin case workflow remain partial/unavailable; this journey records player-visible consequences only.

The exact membership lifecycle, authorization, session, API, and retention semantics remain in their owning sources: [Multi-Tenancy](../../architecture/system-architecture-multi-tenancy.md#account-to-game-relationships), [Authentication & Authorization](../../architecture/system-architecture-authentication.md#login-and-session-flow), [Account Service API Contracts](../../architecture/microservices/account-service/api-contracts.md#subject-binding-rules-normative), [ADR 0022](../../architecture/decisions/adr-0022-account-authority-and-gameplay-session-ownership.md#decision), [ADR 0030](../../architecture/decisions/adr-0030-risk-based-active-session-revocation.md#decision), and [ADR 0035](../../architecture/decisions/adr-0035-single-record-issued-token-registry.md#decision).

---

## Quick Reference

- [Sign Up](#1-sign-up) – Create a platform account and enable auth options.
- [Join a Game for the First Time](#2-join-a-game-for-the-first-time) – Discover a world, choose a realm, and reach the lobby.
- [Character Creation & Selection](#3-character-creation--selection) – Create and choose characters for the selected game and realm target.
- [Player Login and Gameplay](#4-player-login-and-gameplay) – Connect to running realms and play.
- [Social Interaction & Safety](#5-social-interaction--safety) – Chat, groups, and moderation outcomes.
- [Purchases and Subscriptions](#6-purchases-and-subscriptions) – Manage hosting subscriptions.
- [Password Resets & Account Recovery](#7-password-resets--account-recovery) – Recover access when credentials are lost.
- [Switch Games or Manage Multiple Games](#8-switch-games-or-manage-multiple-games) – Move between games under one account.
- [Account Data Export](#9-account-data-export) – Request a durable asynchronous account export.
- [Account Deletion](#10-account-deletion) – Request separate account erasure after billing obligations are resolved.

Creator-focused design flows are described in the [Creator Journeys](./creators.md). Operational and moderation flows are described in the [Operator Journeys](./operators.md), including how outages and recoveries surface to players.

---

## 1. Sign Up

Players register for an account through the [Account Service](../../architecture/microservices/account-service/README.md). Email verification and the baseline password/verified-email-code login modes are outlined in [Authentication & Authorization](../../architecture/system-architecture-authentication.md). Under [ADR 0049](../../architecture/decisions/adr-0049-optional-provider-specific-external-identity-linking.md), **Google**, **Discord**, and **Steam** are planned optional HTTPS linking and sign-in integrations rather than baseline launch promises. Each provider is available only after its complete provider-specific security, recovery, collision, outage, and lifecycle contract is implemented and proven; provider-first account creation remains deferred.

```plaintext
Player → Account Service
```

---

## 2. Join a Game for the First Time

The first successful session for a new player converges on one membership and gameplay-admission contract, while client transports use distinct onboarding gates:

1. **Authenticate the Platform Account**
   - **First-party web client** – Obtains a short-lived player bootstrap token through the [Account Service](../../architecture/microservices/account-service/README.md), then uses bootstrap-backed discovery endpoints to choose a world/realm target. For first-time public-production entry with missing or `INACTIVE` membership, the player completes `Join & Play` before character selection/creation and before `POST /auth/connect-token`; returning members skip that action after fresh Account evidence confirms current membership, while grant-backed non-public players use their existing membership and grant. Eligible players then select or create a character, request a connect token, and open the gameplay WebSocket through the [Spring Cloud Gateway](../../architecture/microservices/spring-cloud-gateway/README.md). Browser clients receive the connect token as the short-lived `Firemud-Connect-Token` HttpOnly cookie, so they do not depend on custom WebSocket headers.
   - **Telnet / text client** – Connects through the [TCP Proxy Service](../../architecture/microservices/tcp-proxy-service/README.md) and authenticates in-band with `LOGIN`. Classic-client semantic extensions such as MCP are deferred and unsupported until separately selected and proven.
2. **Browse or Discover Joinable Worlds** – The player may use `WORLDS` before login to browse the platform publicly, then use the same command again after login to see the authenticated discovery set they can actually enter. Existing memberships qualify the world for authenticated discovery, but do not by themselves qualify every realm. In v1, a live default production realm may also be publicly discoverable even before the player has joined that tenant, so brand-new accounts can still discover where they would enter through the public-production onboarding path. Additional realms are not implied by world visibility: they require an explicit Account-owned realm-access grant for the caller, and grant visibility never substitutes for required membership. Responses use world slugs and friendly names rather than raw IDs, as defined in [Authentication & Authorization](../../architecture/system-architecture-authentication.md) and [Multi-Tenancy](../../architecture/system-architecture-multi-tenancy.md).

   `REALMS <world>` deterministically selects one policy branch after server-side resolution: only public production uses `public_production_onboarding`, while private/playtest discovery uses caller-bound membership/grant policy. Before membership exists, the public branch may expose only the catalog-designated public-production realm without a grant. Public production still requires current visibility and entitlement, while every non-public realm requires existing caller-bound membership with exact `membershipLifecycleState=ACTIVE` plus the current Account grant for `{accountId, tenantId, worldSlug, realmSlug, playtestLifecycleId}`; a grant never substitutes for membership. Both branches use the shared catalog/pointer pair; missing, malformed, ambiguous, stale, or unavailable pointer evidence is `ADMISSION_POINTER_UNAVAILABLE`, while a complete `CLOSED` realm is `REALM_UNAVAILABLE`. Hidden or unauthorized realms are omitted rather than disclosed as a hidden or generic authorization failure.

3. **Choose a Realm When Needed, Then Join** – If the selected world exposes more than one visible realm, the player uses `REALMS <world>` to understand the available targets. Public discovery and open enrollment apply only to the world's single configured default production realm in v1, so `JOIN <world>` always resolves that unambiguous public-production target and does not accept a realm argument. A first-time public player with missing or `INACTIVE` membership explicitly selects `Join & Play` or issues `JOIN <world>`; if the target denies public joining, the player sees `PUBLIC_PRODUCTION_ADMISSION_DENIED` and membership is unchanged. Returning public members and private/playtest players with current access continue through their existing admission flow without `JOIN`. Hidden or unauthorized realms are never disclosed.
4. **List Characters or Create New** – After `JOIN <world>` or `Join & Play` has established or returned the exact current `ACTIVE` membership for a first-time public-production target, the player uses `CHARS <world> [realm]` to view the Entity-backed character choices valid for the selected realm target. The selected realm and its server-derived `playableStateNamespaceId` scope the `CHARS` roster and any allowed creation choices; this pre-binding discovery step does not establish a gameplay controller. Returning members use their existing `ACTIVE` membership; grant-backed private or playtest players use existing `ACTIVE` membership plus the applicable realm grant without a public join. A realm grant never substitutes for tenant membership. `CHARS` and allowed character creation require the applicable membership, grant, and entitlement checks but do not require an existing character; a valid character becomes required only before connect-token issuance or `PLAY`. Character discovery is realm-aware: shared-state realms expose the normal live roster, while isolated realms may expose copied, seeded, or fresh realm-local characters according to explicit creation policy. The UI presents Entity Management's creation/provisioning decision and an explicit denial when no actor is available and creation is not allowed. If no visible character exists, the player completes the world's character-creation flow before `PLAY` unless that realm forbids creation. The authoritative character-creation and namespace-policy owner is the [Entity Management Service](../../architecture/microservices/entity-management-service/README.md). If a fork permits both copied and newly created realm-local characters, `CHARS` returns them in one list; if it forbids creation and no valid character exists, the player receives a hard character-selection denial rather than a generic `CHARACTER_REQUIRED` prompt.
   - Minimum realm-policy consequence: when the selected realm is isolated-state, character discovery and any allowed creation target only that realm's gameplay state namespace. They must not silently read from or write to the tenant's normal live production roster.
   - `CHARS` must also expose the realm-local creation decision clearly enough that clients do not infer policy from roster shape alone. A realm that denies fresh creation must say so explicitly; a realm that allows fresh realm-local creation may do so alongside copied/seeded characters without introducing a separate client mode switch.
5. **Bind to Gameplay** – In the target flow, `PLAY <world> [realm] [character]` is the first step that establishes the controller identity and attachment for the selected persisted actor, including `characterId`. It resolves the selected realm to its stable `playableStateNamespaceId`, server-derived `playableStateScope`, and currently active `gameInstanceId`, rejects stale or invalid actor evidence, then binds the session to controller key `{tenantId, playableStateNamespaceId, characterId}`; `playableStateScope` is server-derived binding/routing/policy/authorization evidence, not the controller key, and active `gameInstanceId` is runtime-fence evidence. An authorized takeover advances `bindingGeneration` atomically, fences new input from the old controller, and preserves already-admitted work under its existing identity. Replacing the runtime keeps durable player state in the same namespace while the admission pointer selects the new active instance; it does not move that state into a new realm or permit simultaneous old/new authority. After this step, normal gameplay commands become available. See [ADR 0132](../../architecture/decisions/adr-0132-namespace-scoped-single-character-controller.md) and [Session Behavior](../../architecture/system-architecture-session-behavior.md#namespace-scoped-controller-transfer-session-02). Runtime replacement retains the durable namespace; current binding/CAS proof remains partial.

For the target public-production flow, `Join & Play` or `JOIN <world>` is the explicit step before character creation, connect-token issuance, or gameplay binding. A successful join remains the player's durable relationship even if a later connection or `PLAY` attempt fails; an unsuccessful join changes nothing. If the target does not permit public joining, the player sees `PUBLIC_PRODUCTION_ADMISSION_DENIED`; if an eligible missing or `INACTIVE` member tries to continue without joining, the player sees `JOIN_REQUIRED`. Other reachable authoritative world or tenant denials remain `WORLD_ACCESS_DENIED`. Private/playtest targets require existing active membership plus the applicable realm grant. Text `PLAY` uses `WORLD_ACCESS_DENIED` for missing or non-admitting non-public membership, while `POST /auth/connect-token` uses `NON_PUBLIC_ENROLLMENT_REQUIRED` for missing membership and `CONNECT_TOKEN_REJECTED` for an existing non-admitting membership. Character creation, connect-token issuance, and `PLAY` never create or restore membership. The canonical [Authentication & Authorization](../../architecture/system-architecture-authentication.md#normative-target-contract) contract owns the technical lifecycle, target resolution, freshness, authorization, versioning, audit, and idempotency rules.

Current compatibility behavior and remaining onboarding gaps are summarized in [Implementation Status](#implementation-status). The target sequence remains `JOIN` -> `CHARS`/creation -> `PLAY`; exact text-to-Account operation binding, stale-selector rejection, and retry behavior are the local consequences documented by the [Game Session protocol contract](../../architecture/microservices/game-session-service/protocols.md#join-translation-and-status).

```plaintext
Telnet:
Player → TCP Proxy → WORLDS (optional public browse) → LOGIN → [REALMS if multiple]
       → [JOIN <world> when public membership is missing or INACTIVE] → CHARS / Create Character → PLAY

First-party web:
Player → Account bootstrap/discovery → [Join & Play when public membership is missing or INACTIVE] → CHARS / Create Character
       → connect-token issuance → Gateway WebSocket handshake → bare LOGIN → PLAY
```

Target-state example text-client transcript (explicit `JOIN` is not implemented in the current runtime):

```text
WORLDS
OK WORLDS
1) emberfall  Emberfall
LOGIN player@example.com swordfish
OK LOGIN Logged in as player@example.com
JOIN emberfall
OK JOIN Joined Emberfall
CHARS emberfall production
OK CHARS 1) Mara
PLAY emberfall production Mara
OK PLAY Entered Emberfall / Live Realm as Mara
```

Example first-party web flow for a returning member or grant-backed target:

```text
POST /auth/player-bootstrap { accountIdentifier=player@example.com, secret=<redacted> }
GET /auth/bootstrap/worlds
GET /auth/bootstrap/worlds/{world}/realms
GET /auth/bootstrap/worlds/{world}/realms/{realm}/characters?connectScopeId={scope}
POST /auth/connect-token { connectScopeId=cs_demo_production_v17 }
GET /ws/game/** with the Firemud-Connect-Token cookie set by the previous response
LOGIN
PLAY <world> [realm] [character]
```

Target-state example first-time public production join (explicit `JOIN`/`Join & Play`, including `POST /auth/bootstrap/join`, is not implemented in the current runtime):

```text
POST /auth/player-bootstrap { accountIdentifier=player@example.com, secret=<redacted> }
GET /auth/bootstrap/worlds
GET /auth/bootstrap/worlds/emberfall/realms
POST /auth/bootstrap/join { connectScopeId=cs_emberfall_production_v1, requestId=req-join-1 }
GET /auth/bootstrap/worlds/emberfall/realms/production/characters?connectScopeId=cs_emberfall_production_v1
POST /auth/bootstrap/worlds/emberfall/realms/production/characters { connectScopeId=cs_emberfall_production_v1, createCharacterRequestId=char-create-1, name=Mara, descriptorId=emberfall-human-fighter, descriptorVersion=17 }
POST /auth/connect-token { connectScopeId=cs_emberfall_production_v1, requestId=req-connect-1 }
GET /ws/game/** with the Firemud-Connect-Token cookie set by the previous response
LOGIN
PLAY emberfall production Mara
OK PLAY Entered Emberfall / Live Realm as Mara
```

The creation request uses the descriptor identity/version bound to the exact discovery response and a caller-stable `createCharacterRequestId`. Repeating that same ID with the same canonical input returns the original character result without allocating another actor; reusing it with changed input returns `IDEMPOTENCY_CONFLICT` before allocation. Account forwards the authenticated tenant and immutable server-bound discovery context; it does not derive namespace/scope or other gameplay authority from player input. Game Session resolves the playable-state namespace/scope and runtime target from the authoritative catalog/admission-pointer snapshot, while Entity validates that evidence and owns the roster, descriptor, creation-policy validation, and character allocation.

After this first successful join, the player's account has normal `player` membership for Emberfall, so later discovery no longer depends on public-production visibility alone. Character creation remains bound to the selected admissible realm and presents the exact versioned, game-authored descriptor for that realm; it may contain RPG choices or an entirely different actor model. Current descriptor availability is summarized in [Implementation Status](#implementation-status).
Any non-production realm shown in fork/playtest examples is assumed to already be grant-visible to that caller; non-public realms are not publicly discoverable by default.

---

## 3. Character Creation & Selection

Character selection is resolved against the specific realm the player is trying to enter. Every normal session binds one persisted, realm-valid primary actor under the [Entity actor-entry contract](../../architecture/microservices/entity-management-service/api-contracts.md). The journey presents Entity's valid roster and policy-specific next action, then Game Session performs selection and attachment. Shared versus isolated namespace behavior, non-RPG authored components, synthetic-ID rejection, and fork-local copy identity remain Entity-owned rules.

Behind the scenes:

- **Account authority** – The [Account Service](../../architecture/microservices/account-service/README.md) verifies identity, membership, and grants, then forwards the authenticated tenant and immutable server-bound discovery context; it does not derive namespace/scope or other gameplay authority or write character rows.
- **Player sequence** – `CHARS` presents the Entity-backed roster and policy result; creation/provisioning, when offered, completes before `PLAY`, and ambiguous rosters require explicit selection.
- **Session attachment** – [Game Session](../../architecture/microservices/game-session-service/README.md) resolves the playable-state namespace/scope and runtime target from the authoritative catalog/admission-pointer snapshot, then coordinates local discovery, selection, attachment, and controller fencing after Entity validates the actor.
- **Entity authority** – [Entity Management](../../architecture/microservices/entity-management-service/README.md) validates the supplied namespace/scope evidence and owns the roster, descriptor, creation-policy validation, and character allocation.

---

## 4. Player Login and Gameplay

Players connect using either a web client or a traditional Telnet client:

- **Web Client** – Connects via WebSocket and HTTP through the [Spring Cloud Gateway](../../architecture/microservices/spring-cloud-gateway/README.md).
- **MUD/Telnet Client** – Connects over TCP to the [TCP Proxy Service](../../architecture/microservices/tcp-proxy-service/README.md), which upgrades traffic to WebSocket for the Gateway. Both paths converge into a stateless WebSocket flow; see [Protocol Bridging](../../architecture/system-architecture-protocol-bridging.md) for details. The target Telnet journey authenticates with `LOGIN` or its `LOGON` alias, takes the conditional `JOIN` step for missing or `INACTIVE` public-production membership, selects or creates a character with `CHARS` or the character-creation flow, and then issues `PLAY`; returning members skip `JOIN` after fresh Account evidence confirms current membership. They may take abbreviated direct `PLAY` only when the unexpired discovery snapshot also proves a valid current character; otherwise they proceed through `CHARS` or creation, which does not require an existing character. Any future smart-client attach hint is conditional on a separately accepted classic-client adapter; if selected, Game Session owns it as advisory metadata and it cannot replace normal admission or carry authority.

Gameplay sessions are managed by the [Game Session Service](../../architecture/microservices/game-session-service/README.md), which coordinates ticks, sessions, and reconnect behavior. From a player's perspective, a disconnect can be recovered through the [Reconnection Strategy](../../architecture/system-architecture-reconnection.md), but new admission, a changed binding, or scope-expanding recovery is denied when required account, membership, realm-grant, billing, security, routing, or session authority is unavailable or invalid. Exact same-binding, non-expanding recovery may use safe positive last-known-good entitlement evidence during an entitlement-only outage for at most five minutes, and billing `grace` may preserve current or resumable bindings while blocking new commitments; hard denial, revocation, or contradictory authority still fails closed. A fresh edge reconnect does not replay client input, bytes, frames, or unsent output; it may show bounded semantic recent context, then a fresh authoritative `LOOK`. Target behavior emits exactly one reconnect prompt only when both effective `firemud.presentation.prompt.enabled` and `firemud.presentation.prompt.emit-after-reconnect-restore` are enabled, or zero reconnect prompts if either is disabled; the complete two-setting rule remains target behavior pending focused proof. Account owns authentication and issued-token authority; Game Session owns gameplay bindings. The exact login, token, revocation, and rebinding contracts are defined by [Authentication & Authorization](../../architecture/system-architecture-authentication.md#session-lifecycle-and-rebinding), [ADR 0022](../../architecture/decisions/adr-0022-account-authority-and-gameplay-session-ownership.md#decision), [ADR 0028](../../architecture/decisions/adr-0028-differentiated-entitlement-freshness.md#decision), [ADR 0030](../../architecture/decisions/adr-0030-risk-based-active-session-revocation.md#decision), and [ADR 0035](../../architecture/decisions/adr-0035-single-record-issued-token-registry.md#decision).

Game actions are resolved on a fixed tick loop as outlined in the [Tick System](../../architecture/system-architecture-ticks.md). Players recover from disconnects through the layered reconnect flow described in [Reconnection Strategy](../../architecture/system-architecture-reconnection.md).

If a tenant is temporarily unavailable because billing or entitlements block gameplay, the player sees a clear tenant-scoped error before `PLAY` succeeds. The canonical [PLAY Error Inventory](../../architecture/system-architecture-protocol-bridging.md#canonical-play-error-inventory) owns the shared routing, scope, entitlement, denial, and membership outcomes and their precedence. These outcomes preserve the authenticated lobby/session state and create no gameplay binding; this journey records only those local consequences. If a creator or operator cuts a realm over to a replacement instance, reconnect follows the same lobby and admission flow and lands on the currently routable realm target.

The player-facing gameplay loop includes room views, communication, movement, and item-management commands. After `PLAY`, a player can use `LOOK` to read the current room, `INV HERE` to inspect visible room-ground items, `GET <item>` / `DROP <item>` to move items between the room and their carried inventory, `INVENTORY` to inspect carried items, `CONTAINER <item>` / `PUT` / `TAKE` for named carried or nearby room-ground containers, and `EQUIPMENT` / `WEAR` / `REMOVE` for equipment state. Item views expose stable selectors when exact targeting is needed, so duplicate or stack-backed items can be manipulated without relying on prose descriptions alone. Equipment actions require the complete game-authored slot/body-layout vocabulary, complete applicable actor/item/equipment bindings, and the exact `equipmentLayoutDigest` matching the admitted published release. Missing, partial, unknown, or mismatched evidence produces an explicit unavailable or invalid outcome rather than a platform-default slot or silent no-op. Publication and authoring authority remains with [Item & Equipment Balancing Tools](../../architecture/microservices/game-design-service/item-equipment-balancing.md); [ADR 0127](../../architecture/decisions/adr-0127-game-authored-equipment-layouts-with-fail-closed-publication.md) provides the supporting fail-closed publication contract and rationale.

Example gameplay transcript:

```text
LOOK
The Ember Gate
You stand before a red stone arch.
Items here:
- Torch [torch3]
INV HERE
Room Inventory:
- Torch [torch3]
GET torch3
You pick up Torch.
INVENTORY
Inventory:
- Torch [torch3]
EQUIPMENT
You have nothing equipped.
WEAR torch3
You wear Torch.
EQUIPMENT
Equipment:
- HAND: Torch [torch3]
```

```plaintext
Player → TCP Proxy / Gateway → Game Session Service → Backend Services
```

---

## 5. Social Interaction & Safety

Players communicate and coordinate through the [Social & Groups Service](../../architecture/microservices/social-groups-service/README.md), with explicit gameplay/social owner handoffs:

1. **Chat Channels (Target/Planned)** – Account messaging, ordinary guild/group channels, browser social actions, and ordinary account/social mail will enter Social & Groups directly after authentication and their applicable authority checks. Tenant-scoped channels require membership; account-global messaging, social actions, and account/social mail instead apply their relevant relationship, privacy, and moderation rules. Social owns the envelope, applicable history, and delivery state; Game Session owns final delivery to connected gameplay transports. These player-facing flows remain deferred as summarized in [Implementation Status](#implementation-status); service-local APIs do not establish their availability.
2. **World Communication** – Gameplay `SAY`, nearby `WHISPER`, and gameplay `TELL` enter the [Game Logic Service](../../architecture/microservices/game-logic-service/README.md) when topology, perception, abilities, effects, or authored interception determine their meaning. Game Logic resolves a bounded authorized plan; Social applies social/moderation/history rules and Game Session delivers the authorized view. An in-game adapter does not turn private platform communication into gameplay or expose it to tenant-authored scripts.
3. **Friends, Groups, and Value** – Account-global friend pairs are tenant-free, while tenant-local relationships are distinct tenant-qualified records. Each group declares `ACCOUNT` or `{tenantId, playableStateNamespaceId, characterId}` membership subjects. Entity Management owns guild containers, items, inventory, currency, and mail attachments; Social retains only ACLs and stable owner references. See [Multi-Tenancy](../../architecture/system-architecture-multi-tenancy.md).
4. **History and Acknowledgement** – Mail, account direct messages, and channels promising scrollback commit durable history before durable acceptance. World speech is live by default; finite safety evidence and content-free retry receipts are separate classes. Redis is a rebuildable cache, and history/export never reveals more than the recipient's original authorized view.
5. **Observers and SHOUT** – Gameplay observer views use a closed type-declared vocabulary and candidate-specific authorization; gameplay `TELL` is non-observable by default. `SHOUT` remains deferred until a selected game profile publishes a named bounded topology and fanout cap; no platform-global, area, or region policy is promised.
6. **Moderation Hooks (Target Behavior)** – Social & Groups enforces its owner-local chat restrictions at communication boundaries; policy, cases, evidence, appeals, and audit use the [Logging & Admin Service](../../architecture/microservices/logging-admin-service/README.md). See [Monitoring and Moderation](./operators.md#1-monitoring-and-moderation) for operator flows.
7. **Chat Validation (Target Behavior)** – Social applies local checks (`chat_mute` at send; `chat_ban` at send, participation, and history) before persistence/publication. World and entity context for gameplay communication comes from the [World Management Service](../../architecture/microservices/world-management-service/README.md) and [Entity Management Service](../../architecture/microservices/entity-management-service/README.md).
8. **Profanity, Reports, and Friends** – Social performs profanity checks and may provide finite evidence/report input under the target contract. The target player-facing report ingress is not currently available; current persistence is internal service-to-service only. A report is evidence and does not automatically restrict another player. Account-level friends may appear in-game when the feature is enabled. Current availability is summarized in [Implementation Status](#implementation-status).
9. **Moderation Outcomes and Account Safety** – Punitive moderation actions surface as specific player-visible outcomes rather than a generic "ban" message. The protective `account_security_lock` is a separate Account security/recovery outcome, not an in-game reportable moderation action:
   - `account_security_lock` blocks ordinary account/bootstrap access until Account security recovery; `platform_access_ban` blocks ordinary platform access and survives credential recovery.
   - `gameplay_ban` blocks `PLAY` and new gameplay admission for the affected tenant/realm scope; already-admitted durable work follows the Game Session owner contract.
   - `chat_mute` blocks sending while ordinary receipt remains available; `chat_ban` blocks ordinary participation, sending, and history while essential notices remain deliverable.
   - Eligible severe or long-lived restrictions expose an opaque HTTPS appeal/status handoff. Filing does not suspend enforcement; only a later review outcome can issue a newer owner command.

**Implementation status:** The current player chat-validation flow covers only room-local `SAY`, `WHISPER`, and `TELL`: commands enter the [Game Logic Service](../../architecture/microservices/game-logic-service/README.md) first for gameplay/world semantics, then Social & Groups synchronously consumes Logging & Admin's `EvaluateModerationPolicy` read at `CHAT_SEND`, failing closed when required policy evidence is stale or unavailable. Target behavior uses Social-owned local `chat_mute`/`chat_ban` projections before persistence or publication, without making Logging & Admin a routine hot-path dependency; that owner-local enforcement remains partial. Guild chat and mail remain deferred target behavior and are not current runtime proof. Current runtime exposes only the internal service-to-service report persistence seam; public player report submission is not currently available. World and entity context comes from the [World Management Service](../../architecture/microservices/world-management-service/README.md) and [Entity Management Service](../../architecture/microservices/entity-management-service/README.md).

Canonical player-facing examples:

- `account_security_lock` – `ERROR ACCOUNT_LOCKED Contact support to recover this account.`
- `platform_access_ban` – `ERROR PLATFORM_ACCESS_BANNED Platform access is restricted. Follow the provided support or appeal guidance.`
- `gameplay_ban` – `ERROR GAMEPLAY_BANNED You cannot enter this realm.`
- `chat_mute` – `ERROR CHAT_MUTED You cannot send messages in this realm.` Ordinary receipt remains available.
- `chat_ban` – `ERROR CHAT_BANNED You cannot participate in chat in this realm.` Essential moderation and system notices remain deliverable.

The target communication model differentiates speech mode from audience scope and recipient view so game profiles can define bounded, explicit gameplay communication. Exact class, relationship/value, history, and observer boundaries are recorded in active ADRs 0147–0150, mapped from the reviewed archive decision keys `MS-GR-COMMUNICATION-ORCHESTRATION`, `MS-SOCIAL-RELATIONSHIP-AUTHORITY`, `MS-SOCIAL-HISTORY-DURABILITY`, and `MS-SOCIAL-OBSERVER-SHOUT-POLICY`; the former room-local `SOCIAL-01` staging assumption is superseded provenance. Current availability is summarized in [Implementation Status](#implementation-status).

```plaintext
Social channels/mail → Social & Groups Service → Game Session Service (connected gameplay delivery where applicable)
World/gameplay communication → Game Session Service (authenticated transport ingress) → Game Logic Service (semantic resolution) → Social & Groups Service → Game Session Service (connected transport projection and delivery)
Moderation policy/case/evidence/audit records → Logging & Admin Service (required)
Message evidence → Logging & Admin Service (optional)
```

---

## 6. Purchases and Subscriptions

1. **Hosting Billing** – The [Account Service](../../architecture/microservices/account-service/README.md) owns the sole supported v1 Stripe integration for FireMUD hosting-plan subscriptions, including provider lifecycle/reconciliation state and hosting-plan entitlement outcomes.
2. **Consumer Boundary** – Game Session, World Management, and lifecycle/admission consumers use Account billing outcomes; they do not call Stripe or copy provider logic. A payment attempt, redirect, or receipt is not entitlement authority.
3. **Deferred Commerce** – Player purchases, paid game subscriptions, creator donations/tips, platform fees on creator transactions, revenue sharing, payouts, and settlement are deferred and are not supported player journeys. Existing generic payment/refund code does not widen this scope.
4. **Tenant Availability & Limits** – Whether a game can start instances or accept new gameplay bindings depends on Account’s subscription state and plan entitlements as described in the [Subscription Management Design](../../architecture/microservices/account-service/subscription-management.md) and [Multi-Tenancy](../../architecture/system-architecture-multi-tenancy.md#tenant-configuration--scaling). When a tenant is suspended for billing, players see a clear tenant-scoped unavailability outcome.
5. **Billing Recovery** – Billing-safe management actions stay reachable even when gameplay is suspended so the authorized billing owner can resolve payment issues without gameplay authority or operator intervention. The current provider lifecycle and enforcement proof remains partial.

```plaintext
Player → Account Service → Stripe-hosted billing flow
```

---

## 7. Password Resets & Account Recovery

Players occasionally lose access to their accounts. Recovery is performed through the [Account Service](../../architecture/microservices/account-service/README.md), which issues password reset emails and temporary login tokens. Suspicious attempts are logged by the [Logging & Admin Service](../../architecture/microservices/logging-admin-service/README.md).

```plaintext
Player → Account Service → Logging & Admin Service (audit)
```

Operational recovery flows (for example, restoring services after outages) are described from the operator perspective in [Operator Recovery Journeys](./operators.md#2-operator-recovery-journeys), including how incidents surface to players.

---

## 8. Switch Games or Manage Multiple Games

Players can participate in multiple games using the same platform account. The [Multi-Tenancy](../../architecture/system-architecture-multi-tenancy.md) model keeps character ownership per `tenantId`, while [Entity Management](../../architecture/microservices/entity-management-service/api-contracts.md) keeps actor choices realm-scoped and owns the actor descriptors/components and creation policy. It resolves and validates the selected playable actor against the realm-scoped candidate binding identity `{tenantId, playableStateNamespaceId, characterId}` before Game Session attaches the controller; a successful `PLAY` creates and commits only the Game Session gameplay controller attachment. Server-derived `playableStateScope` and `gameInstanceId` are retained as binding/runtime-fence evidence. Account selection and tenant setup are managed through the [Account Service](../../architecture/microservices/account-service/README.md), while Game Design authors the game-level actor descriptors/components.

Switching now follows the same lobby contract used during onboarding:

1. `WORLDS` lists accessible worlds.
2. `REALMS <world>` lists visible realms for that world when more than one exists.
3. `CHARS <world> [realm]` shows Entity-validated characters scoped to the selected world/realm.
4. `PLAY <world> [realm] [character]` revalidates the selected actor through Entity Management, then rebinds the Game Session controller to the new target.

```plaintext
Account Service (select/resolve tenant) → Entity Management Service (select/validate actor) → Game Session Service (attach controller)
```

---

## 9. Account Data Export

Account owners request portable data through the [Account Service](../../architecture/microservices/account-service/README.md). The target player experience is an asynchronous export: initiation returns a stable export job, status exposes progress and a versioned manifest including owner failures or omissions, a matching retry resumes the same job, a changed request conflicts, and content becomes available only after required owner contributions complete. The export is limited to data the player is entitled to receive and does not include credentials, token material, provider secrets, internal security data, or other subjects' private data. Exact API shape, subject authorization, request idempotency, pending-deletion access, and content gating are defined in the [Account Service export contract](../../architecture/microservices/account-service/api-contracts.md#current-vs-target-export-lifecycle), [subject-binding rules](../../architecture/microservices/account-service/api-contracts.md#subject-binding-rules-normative), and [ADR 0050 full-subject export](../../architecture/decisions/adr-0050-versioned-export-retention-and-erasure-policy.md#full-subject-export).

Tenant-admin export is a separate tenant-controlled experience: an authorized tenant administrator may retrieve tenant-owned records, including only minimum stable subject references, but not global credentials, email, external identities, security state, or unrelated account data. Ordinary tenant export remains unavailable while billing-blocked; the billing-safe route remains available to an authorized tenant administrator. Its authorization and billing-safe exception are defined by the [Account Service tenant-admin contract](../../architecture/microservices/account-service/api-contracts.md#subject-binding-rules-normative) and [ADR 0050](../../architecture/decisions/adr-0050-versioned-export-retention-and-erasure-policy.md#tenant-administrator-export).

```plaintext
Player → Account Service (durable export initiation) → required owner fan-in → manifest retrieval
```

---

## 10. Account Deletion

Account deletion is a separate confirmed global operation, not leaving one game. After recent authentication and explicit confirmation, an eligible request tells the player that ordinary login, gameplay, and normal account access are revoked while asynchronous erasure proceeds. Deletion remains blocked until all authoritative billing ownership, transfer, cancellation, webhook, outbox, and provider-reconciliation work is terminally resolved; the player sees the applicable blocker rather than a false success. A scheduled reconciliation step is still pending, and `BILLING_RECONCILIATION_TERMINAL_FAILURE` remains a blocker until operator resolution proves a terminal outcome. While `deactivated_pending_delete`, only the dedicated status, cancellation, export, and necessary billing-settlement paths remain available. A retry keeps the same deletion workflow; failures leave the account visibly pending with diagnostics and never report terminal deletion early. Cancellation is available only before the published cutoff; terminal `deleted` has no recovery transition. Exact subject authorization, pending-deletion credentials, workflow/idempotency, audit, session revocation, and state transitions are defined by the [Account Service deletion contract](../../architecture/microservices/account-service/api-contracts.md#subject-binding-rules-normative), [ADR 0043 lifecycle and pending-deletion contract](../../architecture/decisions/adr-0043-global-account-lifecycle-and-bounded-erasure-workflow.md#pending-deletion-access), and [ADR 0050 deletion and shared-records policy](../../architecture/decisions/adr-0050-versioned-export-retention-and-erasure-policy.md#deletion-and-shared-records).

Terminal erasure deletes, anonymizes, tombstones, or retains records according to each owner's finite, minimized retention schedule. Shared, billing, tax, refund, fraud, moderation, security, audit, backup, and provider records may remain only for their approved purpose and duration; the player-facing contract does not promise instantaneous disappearance of every copy. The canonical retention registry and restore/erasure guarantees are owned by [ADR 0050](../../architecture/decisions/adr-0050-versioned-export-retention-and-erasure-policy.md#canonical-retention-registry).

```plaintext
Player → Account Service → Logging & Admin Service (audit)
```

---

## Related Documentation

- [Authentication & Authorization](../../architecture/system-architecture-authentication.md)
- [Game Creator Guide](../../user-guides/game-creator-guide.md)
- [Game Customization](../../architecture/system-architecture-game-customization.md)
- [Logging & Monitoring Overview](../../architecture/system-architecture-logging-monitoring.md)
- [Moderation Policies](../../architecture/microservices/logging-admin-service/moderation-policies.md)
- [Multi-Tenancy](../../architecture/system-architecture-multi-tenancy.md)
- [Mud Client Protocol (MCP) Support](../../architecture/system-architecture-mud-client-protocol.md)
- [System Architecture Overview](../../architecture/system-architecture-overview.md)
- [System Context Diagram](../../architecture/system-context-diagram.md)
