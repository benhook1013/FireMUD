# Product Requirements Document (PRD): MUD Game Platform

## Implementation Status

Typed Game Design creator/versioning APIs have partial current implementation. Starter-profile materialization and conservative upgrades, model-assisted authoring, and official-hosted creator-party terms acceptance/gating remain target-state and are not implemented; changed-terms decline/continuity is likewise target-only under [ADR 0181](../architecture/decisions/adr-0181-changed-hosted-terms-decline-and-existing-content-continuity.md). See the [Game Authoring, Publishing, and Activation tracker](../project-management/implementation-tracking/game-authoring-publishing-and-activation.md), [creator journey implementation status](./user-journeys/creators.md#implementation-status), and [ADR 0180](../architecture/decisions/adr-0180-account-owned-hosted-terms-acceptance-gate.md) for the current boundary and proof.

## 1. Introduction

This document is the canonical product requirements overview for the FireMUD platform. It records product scope and intended outcomes; detailed technical contracts remain in the linked architecture documents.

### 1.1 Purpose

The MUD Game Platform is a **multi-tenant system** that enables users to **create, host, and run multiple independent MUD games**. The platform provides a **scalable, modular, and extensible architecture**, supporting **game world management, player interactions, scripting, automation, and real-time networking**.

### 1.2 Scope

This document outlines the **core functional and non-functional requirements** for the MUD Game Platform, focusing on:

- Multi-tenancy support for **multiple hosted games**.
- A **microservices architecture** for modularity and scalability.
- A **customizable game framework** allowing different rulesets.
- **Real-time networking** and multiplayer interactions.
- **Administration, moderation, and logging tools** for game operators.
- **Scalability, persistence, and deployment considerations**.

### 1.3 Users & Stakeholders

- **Game Designers (Creators)**: Users who design and manage games using the platform's tools.
- **Players**: End users who join and play games on the platform.
- **Administrators & Moderators**: Users who oversee platform security, logging, and compliance.
- **Platform Developers**: Those extending or modifying the FireMUD platform itself.

---

## 2. Key Features & Functional Requirements

### 2.1 Multi-Tenancy & Game Hosting

- The platform supports **multiple hosted games**, each **isolated at the game level**. See [Multi-Tenancy Architecture](../architecture/system-architecture-multi-tenancy.md).
- Each hosted game has **separate world data, player characters, and configurations**.
- Players have a **single platform-wide account** that allows them to join multiple games, with **separate characters per game**.
- Game creators can **host multiple games** with independent settings.
- Hosted games may expose multiple player-addressable realms, including a default production realm and explicit non-production playtest forks used to validate changes against copied gameplay state without mutating production.

### 2.2 Game Design & Customization

- Provides **game editing tools** for modifying world layouts, NPCs, items, and abilities. See [Game Design Service](../architecture/microservices/game-design-service/README.md).
- Allows **game creators to configure rulesets and mechanics** without requiring code changes.
- Supports **game balancing, including experience curves, combat formulas, and economy adjustments**.
- Enables **scripted event design for quests, encounters, and world events**.
- **Procedural generation** supports **algorithm-driven world creation** (e.g., procedural room layouts) while allowing **manual overrides**.
- Starter profiles create editable content in the creator's Draft. Later profile changes do not silently rewrite that Draft, and a runtime realm never uses a profile as an implicit fallback. See [ADR 0124](../architecture/decisions/adr-0124-materialized-starter-profiles-with-conservative-draft-upgrades.md).
- Creators define each game's equipment vocabulary and body layouts. Game Design publication rejects an incomplete equipment description before that version is published. Separately, runtime rejects missing or invalid schema, occupancy, or equipment mappings against the admitted published digest, and a live cutover blocks when required remapping lacks owner-validated/applied evidence for the exact source and target versions; players receive an explicit unavailable/invalid outcome rather than a platform-default slot. See [ADR 0127](../architecture/decisions/adr-0127-game-authored-equipment-layouts-with-fail-closed-publication.md).

### 2.3 User & Account Management

- The platform must provide **secure authentication and user management**.
- Gameplay clients must provide **secure, consistent login and session admission**, with credentials and delegated access limited to the authority required for each operation. See [Authentication & Authorization](../architecture/system-architecture-authentication.md).
- Role-based access control (RBAC) for **admins, moderators, and players**.
- Users should be able to **create and manage multiple characters per game**.
- Sessions should support **persistent logins and reconnection handling**.
- Account must support a protective `account_security_lock` for a compromise assessment supported by verified or high-confidence evidence, an explicit Account security policy, or an audited operator action. Attacker-controlled failed-attempt signals may produce only temporary throttles or rejections and cannot authorize the durable lock. The Account-owned lock revokes ordinary account/bootstrap authority and is cleared only through Account security recovery; it is not a punitive moderation category.
- First-party browser clients must support world, realm, and character selection before gameplay begins. Native mobile requires a later product and compatibility decision.
- **Expanded Account Features**:
  - Players should be able to **link external accounts** (Google, Discord, Steam) for login.
  - Profiles should include **game history, achievements, and social features**.
  - Persistent session tracking to **ensure seamless reconnection across devices**.
See [Account Service](../architecture/microservices/account-service/README.md) for implementation details.

### 2.4 Game World & Entity Management

- Support for **multi-room game worlds** with region-based navigation.
- **Instance-based game spaces** allow separate world states (e.g., public production realms, creator-managed playtest forks, private dungeons, event-based scenarios, or personalized player housing).
- Game creators can configure **instance rules, expiration, and persistence settings**.
- A replacement realm is not exposed to players until it is ready. If its state cannot be safely carried forward or its pre-activation state-transfer cleanup cannot complete, the creator sees a blocked/failed cutover and the currently usable realm remains the player-facing target; after the replacement is ready and that cleanup succeeds, the route swaps before the old realm is drained or terminated.
- **World Persistence & Scheduled Events**:
  - The platform must support **persistent world states**, ensuring that world changes **persist beyond player sessions**.
  - **Scheduled events** (e.g., daily resets, seasonal world changes, NPC schedules) should be configurable.
  - NPC actions and environmental changes should **continue in a believable way even if no players are online**.
- Persistent world, character, NPC, item, inventory, and environmental state must remain **consistent, durable, and independently evolvable** across its owning domains. See the [Entity Management Service](../architecture/microservices/entity-management-service/README.md) and [World Management Service](../architecture/microservices/world-management-service/README.md) for the exact ownership boundary.

### 2.5 Game Logic & Automation

- Players interact with the game via **text-based command parsing** (e.g., `"move north"`, `"attack goblin"`).
- The platform must support **custom game logic per hosted game**.
- **Action processing** for combat, trading, crafting, and roleplay actions.
- **NPC AI Behaviors**:
  - NPCs react dynamically to the world using **event-driven** (trigger-based) and **state-driven** (persistent memory) behaviors.
  - NPCs maintain **awareness of past interactions**, allowing dynamic responses.
  - The system supports **world simulation**, enabling **autonomous NPC actions** even when no players are online.
- Time-based gameplay must process actions **deterministically and fairly** across independently scalable regions while supporting game-configured pacing. See the [Tick System](../architecture/system-architecture-ticks.md).
See [Game Logic Service](../architecture/microservices/game-logic-service/README.md) and [Automation & Scripting Service](../architecture/microservices/automation-scripting-service/README.md) for design details.

### 2.6 Real-Time Multiplayer & Communication

- **WebSockets/TCP-based real-time networking** for player interactions.
- In-game **chat system, mail messaging, and guild/group communications**.
- **PvP & cooperative multiplayer support**.
- **Target: one active gameplay controller per `{tenantId, playableStateNamespaceId, characterId}`**; an authorized `PLAY` transfers control through a multi-step fenced protocol whose final monotonic `bindingGeneration` CAS is the atomic linearization point for target admission, not an implied single cross-service atomic operation. The protocol fences new input from the old connection and preserves the identity of work already admitted before the transfer. `gameInstanceId` is replaceable runtime evidence, not the character-control uniqueness boundary. Current namespace-scoped admission and its proof remain incomplete and must not be inferred from the live binding.
See [Social & Groups Service](../architecture/microservices/social-groups-service/README.md) for chat and guild features.

### 2.7 Extensibility & Game Customization

- Games should support **custom game rules, abilities, and world data**.
- **Scripting API & Advanced AI Customization**:
  - The platform offers **AI & scripting tools** for creating deep, dynamic game interactions.
  - Games can define **unique AI behaviors, quest logic, and in-game events** without requiring custom code deployments.
  - AI behaviors should be flexible enough to allow **autonomous world simulation**, making the game feel persistent and alive.
  - Creators can author validated automation through **textual and visual tools** appropriate to their experience level.
  - Untrusted automation must not compromise platform security, stability, tenant isolation, or gameplay fairness; execution resources remain bounded. See [Scripting & Automation](../architecture/system-architecture-scripting.md).
- Optional model-assisted authoring may propose Draft changes through the same scoped creator tools used by a human. Suggestions remain reviewable and require creator acceptance; a model cannot publish content or mutate a live game directly. See [ADR 0126](../architecture/decisions/adr-0126-untrusted-models-and-scoped-authoring-tools.md).
- Whole-game import/export, filesystem or Git interchange, and portable snapshots are outside the current product boundary; creators use the supported typed Game Design APIs instead. See [ADR 0125](../architecture/decisions/adr-0125-defer-whole-game-portability-and-external-authoring-formats.md).
- **Item & equipment balancing tools** to allow game creators to tweak in-game balance.

See [Game Design Service](../architecture/microservices/game-design-service/README.md) for authoring tools.

### 2.8 Moderation, Administration & Monetization

- **Admin dashboard** for monitoring and moderating hosted games.
- **In-game reporting & fixed-category safety system** for handling violations. Reportable punitive categories are `platform_access_ban`, `gameplay_ban`, `chat_mute`, and `chat_ban`, with independent lifecycles and owner-local enforcement. The protective `account_security_lock` is handled only by Account security recovery, not by in-game reports.
- **Bounded moderation appeals** for eligible severe or long-lived restrictions. Logging & Admin owns the case, evidence references, jurisdiction, review, and audit; Account authenticates the player and provides the browser handoff; Account, Game Session, and Social & Groups remain enforcement owners. Filing does not suspend enforcement.
- **Moderation policy definitions** including profanity filters.
- **Central analytics dashboards and logging** for tracking player activity and game performance.
- Operators and authorized game administrators can manage **tenant-scoped runtime feature flags** through audited controls. See [Versioning & Runtime Configuration](../architecture/system-architecture-versioning-runtime.md) for the ownership and activation contract.
- **V1 Hosting Billing**: Account integrates Stripe as the sole supported provider for FireMUD hosting-plan subscriptions. Account owns provider lifecycle/reconciliation state and hosting-plan/resource entitlement/availability outcomes; lifecycle and admission consumers use those outcomes without copying provider logic. Marketplace/player-purchase entitlements require a later accepted product/settlement decision. Billing-safe management remains reachable when gameplay is denied for billing reasons.
- **Official hosted creator content**: Official hosting requires target Account-owned affirmative acceptance of the current hosted-terms version for the identified operator before the first persisted Draft and before later content-bearing authoring, revision, asset, template, plugin, or publication writes. Game Design is the target fail-closed mutation gate; tenant roles, billing ownership, payment, or UI checks do not substitute for party acceptance. Creators retain content ownership, and hosting alone grants no marketplace or model-training rights. After a material terms change, decline or missing reacceptance blocks new covered writes and publication after the effective date, while supported reads/downloads and narrowly reducing lifecycle actions remain available under limited prior-terms continuity. The [Hosted Content Terms](../../HOSTED_CONTENT_TERMS.md) file is a pre-launch baseline, not an operative contract; this gate and its proof remain unimplemented pending the required legal-review boundary.
- **Deferred creator/player monetization**: player purchases, paid game subscriptions, creator donations/tips, platform fees on creator transactions, revenue sharing, payouts, and settlement are not v1 product commitments. A separate marketplace/settlement decision is required before enabling them; off-platform receipts do not create FireMUD entitlements.
- **Future managed-commerce direction**: The accepted direction in [ADR 0179](../architecture/decisions/adr-0179-firemud-managed-creator-commerce-boundary.md) does not enable a marketplace. Any later creator commerce requires separate opt-in and launch decisions; hosting acceptance is not marketplace enrollment, and only FireMUD-managed checkout could create FireMUD entitlements.
- **Community self-hosted lane**: Ordinary community operators remain in the project's recognized and supported strict noncommercial, no-money lane described by [LICENSING.md](../../LICENSING.md); [LICENSE.md](../../LICENSE.md) alone determines legal rights, and this product guidance does not narrow it. Descriptive brand references remain conditional on [TRADEMARKS.md](../../TRADEMARKS.md) and do not imply official status. Separate owner development sponsorship does not authorize community-instance revenue.
- **External payment methods are not allowed** within FireMUD-managed v1 hosting billing.
- **High-resource features** (e.g., AI, scripting) may be premium hosting plan options, subject to Account entitlements.
See [Logging & Admin Service](../architecture/microservices/logging-admin-service/README.md) for moderation features and [Account Service](../architecture/microservices/account-service/README.md) for payment processing.

---

### 2.9 Versioning & Runtime Configuration

- Creators can publish **immutable, identifiable game versions** and select a published version when launching or updating a realm.
- A running realm uses one **internally consistent published design** rather than mixing independently changing authoring state.
- Concurrent Draft edits surface a base/version conflict when their affected aggregate/scope tuples overlap; disjoint-scope edits may proceed independently. The product does not silently merge competing changes or claim a successful commit until every required owner accepts it. See [ADR 0129](../architecture/decisions/adr-0129-durable-fenced-multi-owner-draft-commits.md).
- Authorized administrators can activate versions and runtime flags through controlled launch, cutover, and rollback experiences. See [Versioning & Runtime Configuration](../architecture/system-architecture-versioning-runtime.md).
- Published versions include **patch notes** so creators, administrators, and players can understand relevant changes over time.
See [Game Design Service](../architecture/microservices/game-design-service/README.md) for publishing workflows.

---

## 3. Infrastructure & Scalability Considerations

### 3.1 Networking & API Gateway

- The platform must provide **low-latency, real-time gameplay networking** for supported clients over WebSocket/TCP.
- First-party WebSocket clients and legacy **Telnet/TCP clients** must have supported paths into the same gameplay experience, with consistent admission and failure behavior. See [Protocol Bridging](../architecture/system-architecture-protocol-bridging.md) and [Gateway Architecture](../architecture/system-architecture-gateway.md).
- External client traffic must use **stable, supported entry points** with the routing, filtering, and edge-failure behavior required for gameplay and platform integrations. The exact edge topology is defined by [Gateway Architecture](../architecture/system-architecture-gateway.md).
- Players must be able to **authenticate and enter gameplay securely** through the Game Session and Account ownership boundaries, while administrative and REST clients must have credentials and permissions enforced by the consuming service. See [Authentication & Authorization](../architecture/system-architecture-authentication.md) and [JWT and Token Contracts](../architecture/system-architecture-jwt-and-token-contracts.md).
- Internal service communication must be **authenticated, confidential, and contract-governed**, with environment-specific deployment exceptions documented rather than silently weakening the security boundary. See [gRPC API Style & Versioning](../architecture/system-architecture-grpc.md), [Security Architecture](../architecture/system-architecture-security.md), and [Infrastructure Documentation](../architecture/infrastructure/README.md) for the exact transport and certificate contracts.
- Multi-server support must enable **hosted games to scale independently**.

### 3.2 Persistence & Caching

- The platform must **durably persist authoritative game-world, entity, and account data** beyond individual player sessions.
- Transient session state and caching may support responsive gameplay and recovery, but must not replace or override **authoritative domain data**. See [Redis Architecture](../architecture/system-architecture-redis.md) for the ownership and durability boundary.
- Each service must support **safe, independently managed persistence evolution** without compromising data integrity. See [Database Migrations](../architecture/system-architecture-database-migrations.md) for the canonical schema and migration contract.

### 3.3 Deployment Model

- The platform must support **repeatable cloud deployment and automated service delivery**, with independently scalable services. See [Deployment Environments](../architecture/infrastructure/deployment-environments.md) and the [CI/CD Pipeline](../architecture/system-architecture-cicd.md) for the deployment contract.
- Infrastructure must support **horizontal scaling** for high-concurrency use cases and **multi-region deployment** to improve latency for global users.
- Operators must have **centralized logging and metrics** sufficient to monitor player activity, service health, and game performance. See [Logging & Monitoring](../architecture/system-architecture-logging-monitoring.md).
- Deployment configuration and authoritative data must have **scheduled backup and disaster-recovery coverage** sufficient to restore the platform after infrastructure or data loss. See [Backup & Disaster Recovery](../architecture/system-architecture-backup-recovery.md) for the exact backup responsibilities.

### 3.4 Gameplay Session Architecture

- Active gameplay sessions must provide **consistent tick execution and runtime configuration**, with the Game Session ownership and versioning boundaries defined by [Tick System](../architecture/system-architecture-ticks.md) and [Versioning & Runtime Configuration](../architecture/system-architecture-versioning-runtime.md).
- Players must be able to **recover gameplay sessions after service or connection disruptions** using current authoritative state and the documented resume-or-reload behavior, without replaying client input, transport bytes, or frames and without treating transient coordination state as authoritative. Reconnect context is bounded semantic recent context, followed by a fresh `LOOK` and exactly one prompt only when both effective `firemud.presentation.prompt.enabled` and `firemud.presentation.prompt.emit-after-reconnect-restore` are enabled; if either is disabled, it emits zero reconnect prompts. The four effective-flag combinations still require focused proof. It is not delivery acknowledgement or a complete transcript archive. See [Reconnection Strategy](../architecture/system-architecture-reconnection.md), [Input, Output, and Presentation](../architecture/system-architecture-input-output-and-presentation.md#prompt-behavior), and [Redis Architecture](../architecture/system-architecture-redis.md).
- Game execution must support **independently scalable regions** while preserving deterministic action processing and coordination correctness. The exact tick, coordination, and failover contracts are defined by [Tick System](../architecture/system-architecture-ticks.md).
- Failover and recovery must preserve **gameplay correctness and bounded availability**, including safe reconstruction of volatile coordination from durable state where required. See [Tick Failures & Operations](../architecture/system-architecture-tick-failures-and-operations.md) and [Redis Recovery](../architecture/system-architecture-redis-reset-and-recovery.md).
- All clients must have a **clear, reliable reconnect path**. Third-party clients must be able to follow the documented flow independently; first-party clients may automate that same flow to reduce user-visible friction. See [Reconnection Strategy](../architecture/system-architecture-reconnection.md).
- Player-visible outcomes use compact versioned structured output with a mandatory plain-text projection, while Game Session owns late presentation/rendering. Structured boundaries used by structured clients must carry `schemaVersion`; supported versions are accepted, while unknown versions produce the explicit non-mutating `unsupported_schema_version` outcome. Telnet consumes the deterministic mandatory plain-text projection and does not negotiate structured schema versions. WebSocket proof for supported and unsupported structured versions remains a gap; Telnet projection/parity proof remains a separate gap. Localization is future-compatible stored source-locale/explicit-variant data with deterministic fallback; live provider translation is not a gameplay hot-path dependency. See [Input, Output, and Presentation](../architecture/system-architecture-input-output-and-presentation.md).

---

## 4. Non-Functional Requirements

| **Category** | **Requirement** |
| --- | --- |
| **Performance** | Must support **hundreds to thousands of concurrent players** per game instance. |
| **Scalability** | Must support **horizontal scaling of services independently**. |
| **Reliability** | **Automated failover and redundancy** for high availability. |
| **Security** | Enforce **OAuth2/JWT authentication, RBAC, and request validation**. |
| **Extensibility** | Provide **modular game design tools** for content creators. |
| **Compliance** | Ensure **GDPR-compliant data handling** for user accounts. |

---
