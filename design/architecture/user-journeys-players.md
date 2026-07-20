# FireMUD User Journeys: Players

This guide summarizes typical player-centric workflows in FireMUD. Each numbered step links to the microservice or design document that manages that portion of the flow. Use it alongside the [Architecture Overview](./README.md), the [System Architecture Overview](./system-architecture-overview.md), the [System Architecture Diagram](./system-architecture-diagram.md), and the [System Context Diagram](./system-context-diagram.md) to understand how players traverse the platform. For a breakdown of every service see the [Microservices Overview](./microservices/README.md) and the [Service Responsibility Matrix](./service-responsibility-matrix.md).

For creator and operator workflows, see:

- [Creator Journeys](./user-journeys-creators.md)
- [Operator Journeys](./user-journeys-operators.md)
- [User Journeys Hub](./user-journeys.md)

Accounts span multiple hosted games. The [Multi-Tenancy](./system-architecture-multi-tenancy.md) model explains how characters and worlds remain isolated under a single platform account.

## Table of Contents

- [Goals](#goals)
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
   - **First-party web client** – Obtains a short-lived player bootstrap token through the [Account Service](./microservices/account-service/README.md), uses bootstrap-backed discovery endpoints to choose a world/realm/character target, then requests a connect token and opens the gameplay WebSocket through the [Spring Cloud Gateway](./microservices/spring-cloud-gateway/README.md). Browser clients receive the connect token as the short-lived `Firemud-Connect-Token` HttpOnly cookie, so they do not depend on custom WebSocket headers.
   - **Telnet / MCP client** – Connects through the [TCP Proxy Service](./microservices/tcp-proxy-service/README.md) and authenticates in-band with `LOGIN`.
2. **Browse or Discover Joinable Worlds** – The player may use `WORLDS` before login to browse the platform publicly, then use the same command again after login to see the authenticated discovery set they can actually enter. Existing memberships always qualify. In v1, a live default production realm may also be publicly discoverable even before the player has joined that tenant, so brand-new accounts can still discover where they would enter through the public-production onboarding path. Responses use world slugs and friendly names rather than raw IDs, as defined in [Authentication & Authorization](./system-architecture-authentication.md) and [Multi-Tenancy](./system-architecture-multi-tenancy.md).
3. **Choose and Join a Realm** – If the selected world exposes more than one visible realm, the player uses `REALMS <world>` to choose between the default production realm and any explicitly authorized additional realms such as a playtest fork. Hidden or unauthorized realms are never disclosed. Public discovery applies only to the default production realm in v1; any additional realm requires an explicit access grant from the creator or operator. A first-time public player explicitly selects `Join & Play` or issues `JOIN <world>`; Account then creates the durable `player` membership that powers the player's game library and future return discovery.
4. **Resolve the Realm's Controllable Actor** – The player uses `CHARS <world> [realm]` to view the persisted controllable actors valid for the selected realm target. “Character” is the protocol's generic name for the primary player-controlled gameplay actor; a game may present it as a person, ship, nation, creature, party leader, or another authored concept. Each published realm declares one entry policy: player-created from a versioned creation descriptor, pre-seeded/copied actors only, or one idempotently auto-provisioned actor from a published template after explicit join/entry. If exactly one valid actor exists it may be selected automatically; multiple actors require an explicit choice. Missing creation metadata blocks only a realm whose policy requires player creation. Entity Management owns creation, actor-to-account association, and realm-valid listing; Account owns the global account, membership, and profile and may consume a projection for discovery. For an isolated fork, any copied actor receives a distinct fork-local ID and may retain `sourceCharacterId` only as provenance, never as a live identity link to production.
   - Minimum realm-policy consequence: when the selected realm is isolated-state, character discovery and any allowed creation target only that realm's gameplay state namespace. They must not silently read from or write to the tenant's normal live production roster.
   - `CHARS` must also expose the realm-local creation decision clearly enough that clients do not infer policy from roster shape alone. A realm that denies fresh creation must say so explicitly; a realm that allows fresh realm-local creation may do so alongside copied/seeded characters without introducing a separate client mode switch.
5. **Bind to Gameplay** – `PLAY <world> [realm] [character]` resolves to canonical `{tenantId, gameInstanceId, characterId}` values and binds the session to the selected realm. After this step, normal gameplay commands become available.

For a first-time join through a publicly discoverable production realm, the explicit join action creates the player's Account-owned membership transactionally before character creation, connect-token issuance, or gameplay binding. A successful join remains the intentional durable relationship even if later connection or `PLAY` fails; a failed join transaction creates nothing. Connect-token issuance and `PLAY` return `JOIN_REQUIRED` rather than silently creating membership.

This onboarding flow is the only supported way to discover and enter a realm. First-party WebSocket connect tokens and any future hidden Telnet smart-client metadata may narrow the target realm, but they never replace the authenticated lobby contract.

```plaintext
Player → WORLDS (optional public browse) → LOGIN → PLAY
                      ↘ optional REALMS / CHARS / Create Character when needed
```

Example text-client transcript:

```text
WORLDS
OK WORLDS
1) emberfall  Emberfall
LOGIN player@example.com swordfish
OK LOGIN Logged in as player@example.com
PLAY emberfall
OK PLAY Entered world: emberfall
```

Example first-party web flow:

```text
POST /auth/player-bootstrap
GET /auth/bootstrap/worlds
GET /auth/bootstrap/worlds/{world}/realms
GET /auth/bootstrap/worlds/{world}/realms/{realm}/characters
POST /auth/connect-token { connectScopeId=cs_demo_production_v17 }
GET /ws/game/** with the Firemud-Connect-Token cookie set by the previous response
LOGIN
PLAY <world> [realm] [character]
```

Example first-time public production join:

```text
POST /auth/player-bootstrap
GET /auth/bootstrap/worlds
GET /auth/bootstrap/worlds/emberfall/realms
GET /auth/bootstrap/worlds/emberfall/realms/production/characters
POST /characters { world=emberfall, realm=production, name=Mara, template=human-fighter }
POST /auth/connect-token { connectScopeId=cs_emberfall_production_v1 }
GET /ws/game/** with the Firemud-Connect-Token cookie set by the previous response
LOGIN
PLAY emberfall production Mara
OK PLAY Entered Emberfall / Live Realm as Mara
```

After this first successful join, the player's account now has normal `player` membership for Emberfall, so later discovery no longer depends on public-production visibility alone.
This example assumes a `PLAYER_CREATED` realm. Its `POST /characters` call is backed by Entity Management's `CreateCharacter` contract, is permitted only for the currently bootstrap-visible realm target, and must complete before the new character is admissible through `PLAY`. The remaining product contract is the exact versioned creation descriptor that tells first-party clients which game-authored fields and choices to render. `PRESEEDED_ONLY` and `AUTO_PROVISIONED` realms do not inherit this form.
Any non-production realm shown in fork/playtest examples is assumed to already be grant-visible to that caller; non-public realms are not publicly discoverable by default.

---

## 3. Character Creation & Selection

Every normal gameplay session binds to one persisted, realm-valid primary controllable actor. FireMUD calls that actor a “character” in shared protocol and storage contracts without assuming a humanoid or traditional RPG. A published realm chooses player-created, pre-seeded/copied-only, or idempotent single-template auto-provision entry. Selection is explicit only when more than one valid actor exists. The platform never manufactures character identity from an account ID, username, or hash.

Game-authored components and facts define race, class, attributes, ship configuration, national resources, or other game-specific state. They are not mandatory platform character fields. Truly characterless sessions and simultaneous control of multiple primary actors are deferred until a concrete game requires their different session, command, presence, and fencing semantics.

Behind the scenes:

- **Account & Character Link** – [Entity Management](./microservices/entity-management-service/README.md) owns the authoritative character-to-account association. Account may consume a bounded projection for discovery but does not become character owner.
- **Creation Descriptor and Templates** – The published realm entry policy references a versioned Game Design descriptor or template appropriate to that game.
- **Character Storage** – Entity Management persists character identity and authored component state; Game Session binds one selected identity to the gameplay session.

---

## 4. Player Login and Gameplay

Players connect using either a web client or a traditional Telnet client:

- **Web Client** – Connects via WebSocket and HTTP through the [Spring Cloud Gateway](./microservices/spring-cloud-gateway/README.md).
- **MUD/Telnet Client** – Connects over TCP to the [TCP Proxy Service](./microservices/tcp-proxy-service/README.md), which upgrades traffic to WebSocket for the Gateway. Both paths converge into a stateless WebSocket flow; see [Protocol Bridging](./system-architecture-protocol-bridging.md) for details. Normal Telnet clients simply issue `LOGIN` and `PLAY` just like WebSocket clients do. Any future smart-client attach hints should be hidden MCP metadata rather than typed player commands.

Gameplay sessions are managed by the [Game Session Service](./microservices/game-session-service/README.md), which coordinates ticks, sessions, and reconnect behavior. Redis stores gameplay session bindings and related runtime coordination state as described in [Redis Architecture](./system-architecture-redis.md), allowing the Game Session Service to rebind sessions when players reconnect. Account-auth issued-token registry and revocation/version state lives under the Account Service-owned `session:auth:*` contract rather than as generic Game Session auth state. Authentication is delegated to the [Account Service](./microservices/account-service/README.md) as described in [Authentication & Authorization](./system-architecture-authentication.md).

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

Players can participate in multiple games using the same platform account. Entity Management scopes its authoritative actor-to-account associations by tenant and playable-state namespace while resolving actual choices against the selected realm. Account selection and tenant setup are managed through the [Account Service](./microservices/account-service/README.md) and [Game Design Service](./microservices/game-design-service/README.md).

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
