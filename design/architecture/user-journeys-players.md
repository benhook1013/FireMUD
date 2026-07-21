# FireMUD User Journeys: Players

This guide summarizes typical player-centric workflows in FireMUD. Each numbered step links to the microservice or design document that manages that portion of the flow. Use it alongside the [Architecture Overview](./README.md), the [System Architecture Overview](./system-architecture-overview.md), the [System Architecture Diagram](./system-architecture-diagram.md), and the [System Context Diagram](./system-context-diagram.md) to understand how players traverse the platform. For a breakdown of every service see the [Microservices Overview](./microservices/README.md) and the [Service Responsibility Matrix](./service-responsibility-matrix.md).

For creator and operator workflows, see:

- [Creator Journeys](./user-journeys-creators.md)
- [Operator Journeys](./user-journeys-operators.md)
- [User Journeys Hub](./user-journeys.md)

Accounts span multiple hosted games. The [Multi-Tenancy](./system-architecture-multi-tenancy.md) model explains how characters and worlds remain isolated under a single platform account.

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
- [9. Account Data Export & Deletion](#9-account-data-export--deletion)
- [Related Documentation](#related-documentation)

---

## Goals

- Provide a quick reference for how a player moves through the system.
- Map each step to the microservice that owns the logic or data from a player’s point of view.
- Link back to deeper design docs for anyone who needs additional context.

---

## Implementation Status

The target journey below requires an explicit `JOIN` / `Join & Play`, then realm-scoped `CHARS`, before character creation, connect-token issuance, or `PLAY` for first-time public-production entry; `PLAY` never creates membership. Returning members and grant-backed non-public players preserve their existing membership/grant-backed discovery flow and skip only the public-production join action. Current implementation still allows the connect-token issuance and text `PLAY` paths to invoke `EnsurePublicProductionPlayerMembership` implicitly, and current text clients can enter through direct `LOGIN` plus `PLAY` without the target discovery/`CHARS` sequence. The explicit join boundary, realm-scoped character gate, and `JOIN_REQUIRED` behavior are not yet implemented across all clients. That is tracked implementation drift, not target behavior.

Realm-aware character discovery and the current creation-policy decision are implemented at the backend boundary, but the richer character-creation descriptor remains a gap. The current flow does not yet provide first-party clients with the published-version-specific template, race, class, and option descriptor needed to render the complete creation choices.

---

## Quick Reference

- [Sign Up](#1-sign-up) – Create a platform account and enable auth options.
- [Join a Game for the First Time](#2-join-a-game-for-the-first-time) – Discover a world, choose a realm, and reach the lobby.
- [Character Creation & Selection](#3-character-creation--selection) – Create and choose characters for the selected game and realm target.
- [Player Login and Gameplay](#4-player-login-and-gameplay) – Connect to running realms and play.
- [Social Interaction & Safety](#5-social-interaction--safety) – Chat, groups, reports, and moderation outcomes.
- [Purchases and Subscriptions](#6-purchases-and-subscriptions) – Manage subscriptions and in-game purchases.
- [Password Resets & Account Recovery](#7-password-resets--account-recovery) – Recover access when credentials are lost.
- [Switch Games or Manage Multiple Games](#8-switch-games-or-manage-multiple-games) – Move between games under one account.
- [Account Data Export & Deletion](#9-account-data-export--deletion) – Request exports or complete account deletion.

Creator-focused design flows are described in the [Creator Journeys](./user-journeys-creators.md). Operational and moderation flows are described in the [Operator Journeys](./user-journeys-operators.md), including how outages and recoveries surface to players.

---

## 1. Sign Up

Players register for an account through the [Account Service](./microservices/account-service/README.md). Email verification and the baseline password/verified-email-code login modes are outlined in [Authentication & Authorization](./system-architecture-authentication.md). Under [ADR 0049](./decisions/adr-0049-optional-provider-specific-external-identity-linking.md), **Google**, **Discord**, and **Steam** are planned optional HTTPS linking and sign-in integrations rather than baseline launch promises. Each provider is available only after its complete provider-specific security, recovery, collision, outage, and lifecycle contract is implemented and proven; provider-first account creation remains deferred.

```plaintext
Player → Account Service
```

---

## 2. Join a Game for the First Time

The first successful session for a new player follows a single canonical onboarding flow regardless of client type:

1. **Authenticate the Platform Account**
   - **First-party web client** – Obtains a short-lived player bootstrap token through the [Account Service](./microservices/account-service/README.md), then uses bootstrap-backed discovery endpoints to choose a world/realm target. For first-time public-production entry, the player completes `Join & Play` before character selection/creation and before `POST /auth/connect-token`; returning members skip that action, while grant-backed non-public players use their existing membership plus grant and also skip it. Eligible players then select or create a character, request a connect token, and open the gameplay WebSocket through the [Spring Cloud Gateway](./microservices/spring-cloud-gateway/README.md). Browser clients receive the connect token as the short-lived `Firemud-Connect-Token` HttpOnly cookie, so they do not depend on custom WebSocket headers.
   - **Telnet / MCP client** – Connects through the [TCP Proxy Service](./microservices/tcp-proxy-service/README.md) and authenticates in-band with `LOGIN`.
2. **Browse or Discover Joinable Worlds** – The player may use `WORLDS` before login to browse the platform publicly, then use the same command again after login to see the authenticated discovery set they can actually enter. Existing memberships qualify the world for authenticated discovery, but do not by themselves qualify every realm. In v1, a live default production realm may also be publicly discoverable even before the player has joined that tenant, so brand-new accounts can still discover where they would enter through the public-production onboarding path. Additional realms are not implied by world visibility: they require an explicit Account-owned realm-access grant for the caller, and grant visibility never substitutes for required membership. Responses use world slugs and friendly names rather than raw IDs, as defined in [Authentication & Authorization](./system-architecture-authentication.md) and [Multi-Tenancy](./system-architecture-multi-tenancy.md).

   Discovery has two deliberately different admission classes. The configured default production realm may be listed through public world discovery and may offer the explicit `JOIN`/`Join & Play` path; Account creates the caller's durable `player` membership before character discovery, connect-token issuance, or gameplay. Private and playtest realms are never exposed by public visibility alone: they appear only when the caller has an active Account-owned realm-access grant and any separately required tenant membership, skip `JOIN`, and do not create membership. A grant permits visibility and admission to that realm but never substitutes for membership; without the grant, the realm is omitted rather than disclosed as a hidden or generic authorization failure.

3. **Choose a Realm When Needed, Then Join** – If the selected world exposes more than one visible realm, the player uses `REALMS <world>` to understand the available targets. Public discovery and open enrollment apply only to the world's single configured default production realm in v1, so `JOIN <world>` always resolves that unambiguous public-production target and does not accept a realm argument. A first-time public player explicitly selects `Join & Play` or issues `JOIN <world>`; Account then creates the durable tenant `player` membership that powers the player's game library and future return discovery. Grant-backed private or playtest realms validate the current grant and any separately required existing membership, skip `JOIN`, and never create membership through this flow. Hidden or unauthorized realms are never disclosed.
4. **List Characters or Create New** – After `JOIN <world>` or `Join & Play` has created or confirmed membership for a first-time public-production target, the player uses `CHARS <world> [realm]` to view the character choices valid for the selected realm target. Returning members use their existing membership; grant-backed private or playtest players use existing membership plus the applicable realm grant without a public join. A realm grant never substitutes for tenant membership. In shared-state realms this typically means the tenant's normal live durable character roster. In isolated realms it may instead mean copied fork-local state, seeded/sample-state characters, or fresh standalone realm-local state for the same account. The current backend discovery contract is realm-aware: character listing carries the resolved `gameInstanceId` and playable-state scope, and bootstrap/text discovery expose the selected realm's state scope and character-creation policy instead of reading a tenant-wide roster behind a realm label. If no visible character exists, the client must complete the world's character-creation flow before `PLAY` succeeds unless the resolved realm's policy forbids creation. The authoritative character-creation owner for this step is the [Entity Management Service](./microservices/entity-management-service/README.md), whose `CreateCharacter` contract defines how new player characters are created for the selected realm scope. For playtest forks, a player may arrive with copied fork-local character state from the source snapshot. If no visible fork-local character exists for that account, fork policy determines whether the player may create a fresh fork-local character or whether the fork is restricted to copied characters only; whichever policy a fork uses must be surfaced consistently in the lobby/client UX. If a fork permits both copied and newly created fork-local characters, `CHARS` returns them in one fork-local list and the client does not need a separate mode switch. If no visible character exists and fork policy forbids creation, the canonical player-facing failure is a hard character-selection denial rather than a generic `CHARACTER_REQUIRED` prompt.
   - Minimum realm-policy consequence: when the selected realm is isolated-state, character discovery and any allowed creation target only that realm's gameplay state namespace. They must not silently read from or write to the tenant's normal live production roster.
   - `CHARS` must also expose the realm-local creation decision clearly enough that clients do not infer policy from roster shape alone. A realm that denies fresh creation must say so explicitly; a realm that allows fresh realm-local creation may do so alongside copied/seeded characters without introducing a separate client mode switch.
5. **Bind to Gameplay** – `PLAY <world> [realm] [character]` resolves to canonical `{tenantId, gameInstanceId, characterId}` values and binds the session to the selected realm. After this step, normal gameplay commands become available.

For the target public-production flow, the explicit join action creates the player's Account-owned membership transactionally before character creation, connect-token issuance, or gameplay binding. A successful join remains the intentional durable relationship even if later connection or `PLAY` fails; a failed join transaction creates nothing. Connect-token issuance and `PLAY` return `JOIN_REQUIRED` rather than silently creating membership. Grant-backed private and playtest flows validate their grant plus any separately required membership, skip `JOIN`, and do not create membership.

This onboarding flow is the only supported way to discover and enter a realm. First-party WebSocket connect tokens and any future hidden Telnet smart-client metadata may narrow the target realm, but they never replace the authenticated lobby contract.

```plaintext
Telnet:
Player → TCP Proxy → WORLDS (optional public browse) → LOGIN → [REALMS if multiple]
       → [JOIN <world> for first public-production entry] → CHARS / Create Character → PLAY

First-party web:
Player → Account bootstrap/discovery → [Join & Play for first public-production entry] → CHARS / Create Character
       → connect-token issuance → Gateway WebSocket handshake → bare LOGIN → PLAY
```

Example text-client transcript:

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

Example first-time public production join:

```text
POST /auth/player-bootstrap { accountIdentifier=player@example.com, secret=<redacted> }
GET /auth/bootstrap/worlds
GET /auth/bootstrap/worlds/emberfall/realms
POST /auth/bootstrap/join { connectScopeId=cs_emberfall_production_v1, requestId=req-join-1 }
GET /auth/bootstrap/worlds/emberfall/realms/production/characters?connectScopeId=cs_emberfall_production_v1
POST /auth/bootstrap/worlds/emberfall/realms/production/characters { connectScopeId=cs_emberfall_production_v1, name=Mara, template=human-fighter }
POST /auth/connect-token { connectScopeId=cs_emberfall_production_v1, requestId=req-connect-1 }
GET /ws/game/** with the Firemud-Connect-Token cookie set by the previous response
LOGIN
PLAY emberfall production Mara
OK PLAY Entered Emberfall / Live Realm as Mara
```

After this first successful join, the player's account now has normal `player` membership for Emberfall, so later discovery no longer depends on public-production visibility alone.
The player-facing character-creation call in this sequence is the Account-owned `POST /auth/bootstrap/worlds/{worldSlug}/realms/{realmSlug}/characters` facade backed by Entity Management's internal `CreateCharacter` contract. It requires the opaque server-issued discovery `connectScopeId`, is permitted only for that still-admissible realm target, and must complete before the new character is admissible through `PLAY`. The currently resolved backend substrate covers realm scope and creation policy; the remaining product contract is a richer character-creation descriptor that tells first-party clients which template/race/class/options to render for a given published game version.
Any non-production realm shown in fork/playtest examples is assumed to already be grant-visible to that caller; non-public realms are not publicly discoverable by default.

---

## 3. Character Creation & Selection

Character ownership is scoped per game (tenant), while character selection is always resolved against the specific realm the player is trying to enter. Depending on realm policy, the selected realm may expose the tenant's normal live durable character state or isolated realm-local state created from a copy, seeded/sample data, or fresh standalone records for the same account. These flows use the [Entity Management Service](./microservices/entity-management-service/README.md) and [Game Session Service](./microservices/game-session-service/README.md). Creation flows are coordinated with the [Game Design Service](./microservices/game-design-service/README.md) to ensure race, class, and ability choices match the published game configuration. Explicit character creation and selection are part of the v1 admission contract; the platform does not fall back to an implicit account-derived default character. See [World and Entity Design](./user-journeys-creators.md#2-world-and-entity-design) for creator-side details on how these options are defined.

Behind the scenes:

- **Account & Character Link** – The [Account Service](./microservices/account-service/README.md) tracks ownership of characters per account.
- **Character Templates** – Starting attributes come from templates in the [Game Design Service](./microservices/game-design-service/README.md).
- **Character Storage** – The [Entity Management Service](./microservices/entity-management-service/README.md) persists characters with deferred writes coordinated by the Game Session Service.

---

## 4. Player Login and Gameplay

Players connect using either a web client or a traditional Telnet client:

- **Web Client** – Connects via WebSocket and HTTP through the [Spring Cloud Gateway](./microservices/spring-cloud-gateway/README.md).
- **MUD/Telnet Client** – Connects over TCP to the [TCP Proxy Service](./microservices/tcp-proxy-service/README.md), which upgrades traffic to WebSocket for the Gateway. Both paths converge into a stateless WebSocket flow; see [Protocol Bridging](./system-architecture-protocol-bridging.md) for details. Normal Telnet clients authenticate with `LOGIN`, take the conditional `JOIN` step for first public-production membership, select or create a character with `CHARS` or the character-creation flow, and then issue `PLAY`; returning members skip `JOIN` but not the character-selection requirement. Any future smart-client attach hints should be hidden MCP metadata rather than typed player commands.

Gameplay sessions are managed by the [Game Session Service](./microservices/game-session-service/README.md), which coordinates ticks, sessions, and reconnect behavior. Redis stores gameplay session bindings and related runtime coordination state as described in [Redis Architecture](./system-architecture-redis.md), allowing the Game Session Service to rebind sessions when players reconnect. The Account-auth issued-token registry and its revocation/version state live under the Account Service-owned `session:auth:*` contract rather than as generic Game Session auth state. Authentication is delegated to the [Account Service](./microservices/account-service/README.md) as described in [Authentication & Authorization](./system-architecture-authentication.md).

Game actions are resolved on a fixed tick loop as outlined in the [Tick System](./system-architecture-ticks.md). Players recover from disconnects through the layered reconnect flow described in [Reconnection Strategy](./system-architecture-reconnection.md).

If a tenant is temporarily unavailable because billing or entitlements block gameplay, the player sees a clear tenant-scoped error before `PLAY` succeeds. If a creator or operator cuts a realm over to a replacement instance, reconnect follows the same lobby and admission flow and lands on the currently routable realm target.

The current player-facing gameplay loop includes room views, communication, movement, and the first item-management commands. After `PLAY`, a player can use `LOOK` to read the current room, `INV HERE` to inspect visible room-ground items, `GET <item>` / `DROP <item>` to move items between the room and their carried inventory, `INVENTORY` to inspect carried items, `CONTAINER <item>` / `PUT` / `TAKE` for named carried or nearby room-ground containers, and `EQUIPMENT` / `WEAR` / `REMOVE` for equipment state. Item views expose stable selectors when exact targeting is needed, so duplicate or stack-backed items can be manipulated without relying on prose descriptions alone. Equipment actions are validated against the game's authored slot/body-layout model when that schema exists, so non-humanoid characters or game-specific attachment points produce explicit errors rather than silent no-ops.

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

Players communicate and coordinate through the [Social & Groups Service](./microservices/social-groups-service/README.md):

1. **Chat Channels** – Global, zone, and group chat messages are routed through the Social & Groups Service.
2. **Friends and Guilds** – Friend lists and guild memberships are scoped per game (`tenantId`), as outlined in [Multi-Tenancy](./system-architecture-multi-tenancy.md).
3. **Moderation Hooks** – Messages and social actions may be subject to moderation and logging via the [Logging & Admin Service](./microservices/logging-admin-service/README.md). See [Monitoring and Moderation](./user-journeys-operators.md#monitoring-and-moderation) for operator flows.

4. **Chat Validation** – In-game chat commands (say, tell, guild chat, mail) are first validated by the [Game Logic Service](./microservices/game-logic-service/README.md) against the [World Management Service](./microservices/world-management-service/README.md) and [Entity Management Service](./microservices/entity-management-service/README.md) to ensure they respect world and entity state.
5. **Profanity & Friends** – The Social & Groups Service performs profanity checks, logs communication, and delivers messages. Account-level friends automatically appear in-game when the feature is enabled.
6. **Player Reporting** – Players can submit an in-game abuse report through the shared moderation/reporting surface. Reports are recorded by the [Logging & Admin Service](./microservices/logging-admin-service/README.md) with the relevant tenant, realm, reported subject, and transcript metadata for operator review.
7. **Moderation Outcomes** – Moderation actions surface as specific player-visible outcomes rather than a generic "ban" message:
   - `account_security_ban` blocks account authentication and recovery to the normal account-security path.
   - `gameplay_ban` blocks `PLAY` for the affected tenant/realm scope with a canonical gameplay denial.
   - `chat_mute` / `chat_ban` allow gameplay to continue but reject affected messaging commands with canonical chat errors.

Canonical player-facing examples:

- `account_security_ban` – `ERROR ACCOUNT_LOCKED Contact support to recover this account.`
- `gameplay_ban` – `ERROR GAMEPLAY_BANNED You cannot enter this realm.`
- `chat_mute` / `chat_ban` – `ERROR CHAT_RESTRICTED You cannot send messages in this realm.`

Implementation note:

- The current playable chat slice is room-local speech. Future slices are expected to differentiate speech mode from audience scope so game rules can support behavior such as target-limited whispers, directed tells, and topology-aware shouts or announcements that propagate across an area, region, map, or continent rather than assuming all communication is equivalent to `SAY`.

```plaintext
Player → Game Session Service → Social & Groups Service → Logging & Admin Service
```

Current gameplay implementation note: the foundational shared communication path is now live for `SAY`, `WHISPER`, and `TELL`. Structured metadata-only observer handling for `WHISPER` and recipient-side live delivery for generic WebSocket and Telnet now exist in the shared communication model. Broader audible scopes (`area`, `map`, `region`, `continent`, channel-level) and first-party/MCP-aware recipient presentation remain later communication slices.

---

## 6. Purchases and Subscriptions

1. **Payment Processing** – The [Account Service](./microservices/account-service/README.md) handles subscriptions, one-time purchases, and optional donations via Stripe.
2. **Platform Fee & Restrictions** – A small platform fee applies to each transaction and external payment methods are not allowed, per the [Core Requirements](../project-management/core-requirements.md#2.8-moderation-administration--monetization).
3. **One-Time Purchase Entitlements** – One-time purchases that grant ongoing value create Account Service-owned purchase entitlements after Stripe success; refunds revoke those entitlements unless the product was explicitly consumed under a non-revocable product contract.
4. **Audit and Compliance** – Transactions are logged through the [Logging & Admin Service](./microservices/logging-admin-service/README.md) for reporting and refunds.
5. **Tenant Availability & Limits** – Whether a game can start new instances or accept new logins depends on the tenant’s subscription state and plan entitlements as described in the [Subscription Management Design](./microservices/account-service/subscription-management.md) and [Multi-Tenancy](./system-architecture-multi-tenancy.md#tenant-configuration--scaling). When a tenant is suspended for billing, login attempts fail with clear errors until billing is resolved.
6. **Billing Recovery** – Billing-safe management actions stay reachable even when gameplay is suspended so creators can resolve payment issues without operator intervention. Players see tenant-scoped unavailability errors until the creator restores service.

```plaintext
Player → Account Service → Logging & Admin Service
```

---

## 7. Password Resets & Account Recovery

Players occasionally lose access to their accounts. Recovery is performed through the [Account Service](./microservices/account-service/README.md), which issues password reset emails and temporary login tokens. Suspicious attempts are logged by the [Logging & Admin Service](./microservices/logging-admin-service/README.md).

```plaintext
Player → Account Service → Logging & Admin Service (audit)
```

Operational recovery flows (for example, restoring services after outages) are described from the operator perspective in [Operator Recovery Journeys](./user-journeys-operators.md#operator-recovery-journeys), including how incidents surface to players.

---

## 8. Switch Games or Manage Multiple Games

Players can participate in multiple games using the same platform account. The [Multi-Tenancy](./system-architecture-multi-tenancy.md) model keeps character ownership per `tenantId` while resolving actual playable character choices against the selected realm's `gameInstanceId`. Account selection and tenant setup are managed through the [Account Service](./microservices/account-service/README.md) and [Game Design Service](./microservices/game-design-service/README.md).

Switching now follows the same lobby contract used during onboarding:

1. `WORLDS` lists accessible worlds.
2. `REALMS <world>` lists visible realms for that world when more than one exists.
3. `CHARS <world> [realm]` shows characters scoped to the selected world/realm.
4. `PLAY <world> [realm] [character]` rebinds gameplay to the new target.

```plaintext
Account Service → Game Design Service (select tenant) → Game Session Service
```

---

## 9. Account Data Export & Deletion

Players may request a full account data export or permanently delete an account through the [Account Service](./microservices/account-service/README.md). Under [ADR 0050](./decisions/adr-0050-versioned-export-retention-and-erasure-policy.md), full export is an asynchronous versioned JSON manifest of portable data contributed by every required owning service; partial or omitted categories are reported rather than presented as complete. Tenant admins have a separate tenant-scoped billing-safe export for one suspended or canceled tenant. It contains tenant-owned recovery data and minimum subject references, never global credentials, email, external identities, security state, or unrelated account data from other games.

Account deletion requires confirmation, revokes active sessions, and is recorded by the [Logging & Admin Service](./microservices/logging-admin-service/README.md) for audit purposes. Deletion is blocked while the account owns any nonterminal tenant subscription; the creator must first transfer billing ownership or cancel the subscription terminally so payment instruments, invoices, refunds, and tenant hosting responsibility are not orphaned. Direct identity is erased after the cancellation cutoff, while shared or legally/policy-required evidence may remain only in the minimized form and finite duration declared by the canonical retention registry.

```plaintext
Player → Account Service → Logging & Admin Service (audit)
```

---

## Related Documentation

- [Authentication & Authorization](./system-architecture-authentication.md)
- [Game Creator Guide](../user-guides/game-creator-guide.md)
- [Game Customization](./system-architecture-game-customization.md)
- [Logging & Monitoring Overview](./system-architecture-logging-monitoring.md)
- [Moderation Policies](./microservices/logging-admin-service/moderation-policies.md)
- [Multi-Tenancy](./system-architecture-multi-tenancy.md)
- [Mud Client Protocol (MCP) Support](./system-architecture-mud-client-protocol.md)
- [System Architecture Overview](./system-architecture-overview.md)
- [System Context Diagram](./system-context-diagram.md)
