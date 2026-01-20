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
- [2. Character Creation & Selection](#2-character-creation--selection)
- [3. Player Login and Gameplay](#3-player-login-and-gameplay)
- [4. Social Interaction](#4-social-interaction)
- [5. Purchases and Subscriptions](#5-purchases-and-subscriptions)
- [6. Password Resets & Account Recovery](#6-password-resets--account-recovery)
- [7. Switch Games or Manage Multiple Games](#7-switch-games-or-manage-multiple-games)
- [8. Account Data Export & Deletion](#8-account-data-export--deletion)
- [Related Documentation](#related-documentation)

---

## Goals

- Provide a quick reference for how a player moves through the system.
- Map each step to the microservice that owns the logic or data from a player’s point of view.
- Link back to deeper design docs for anyone who needs additional context.

---

## Quick Reference

- [Sign Up](#1-sign-up) – Create a platform account and enable auth options.
- [Character Creation & Selection](#2-character-creation--selection) – Create and choose characters per game.
- [Player Login and Gameplay](#3-player-login-and-gameplay) – Connect to running game instances and play.
- [Social Interaction](#4-social-interaction) – Chat, groups, and social systems.
- [Purchases and Subscriptions](#5-purchases-and-subscriptions) – Manage subscriptions and in-game purchases.
- [Password Resets & Account Recovery](#6-password-resets--account-recovery) – Recover access when credentials are lost.
- [Switch Games or Manage Multiple Games](#7-switch-games-or-manage-multiple-games) – Move between games under one account.
- [Account Data Export & Deletion](#8-account-data-export--deletion) – Request exports or complete account deletion.

Creator-focused design flows are described in the [Creator Journeys](./user-journeys-creators.md). Operational and moderation flows are described in the [Operator Journeys](./user-journeys-operators.md), including how outages and recoveries surface to players.

---

## 1. Sign Up

Players register for an account through the [Account Service](./microservices/account-service/README.md). Email verification and login flows are outlined in [Authentication & Authorization](./system-architecture-authentication.md). Admins and moderators can enable **two-factor authentication** (TOTP) as described in the [Security Architecture](./system-architecture-security.md). Players may also link external accounts such as **Google**, **Discord**, or **Steam** for simplified logins, as detailed in the Account Service documentation.

```plaintext
Player → Account Service
```

---

## 2. Character Creation & Selection

Character definitions and selection are scoped per game (tenant) using the [Entity Management Service](./microservices/entity-management-service/README.md) and [Game Session Service](./microservices/game-session-service/README.md). Creation flows are coordinated with the [Game Design Service](./microservices/game-design-service/README.md) to ensure race, class, and ability choices match the published game configuration. See [World and Entity Design](./user-journeys-creators.md#world-and-entity-design) for creator-side details on how these options are defined.

---

## 3. Player Login and Gameplay

Players connect using either a web client or a traditional Telnet client:

- **Web Client** – Connects via WebSocket and HTTP through the [Spring Cloud Gateway](./microservices/spring-cloud-gateway/README.md).
- **MUD/Telnet Client** – Connects over TCP to the [TCP Proxy Service](./microservices/tcp-proxy-service/README.md), which upgrades traffic to WebSocket for the Gateway. See [Mud Client Protocol (MCP) Support](./system-architecture-mud-client-protocol.md).

Gameplay sessions are managed by the [Game Session Service](./microservices/game-session-service/README.md), which coordinates ticks, sessions, and reconnect behavior. Authentication is delegated to the [Account Service](./microservices/account-service/README.md) as described in [Authentication & Authorization](./system-architecture-authentication.md).

```plaintext
Player → TCP Proxy / Gateway → Game Session Service → Backend Services
```

---

## 4. Social Interaction

Players communicate and coordinate through the [Social & Groups Service](./microservices/social-groups-service/README.md):

1. **Chat Channels** – Global, zone, and group chat messages are routed through the Social & Groups Service.
2. **Friends and Guilds** – Friend lists and guild memberships are scoped per game (`tenantId`), as outlined in [Multi-Tenancy](./system-architecture-multi-tenancy.md).
3. **Moderation Hooks** – Messages and social actions may be subject to moderation and logging via the [Logging & Admin Service](./microservices/logging-admin-service/README.md). See [Monitoring and Moderation](./user-journeys-operators.md#monitoring-and-moderation) for operator flows.

```plaintext
Player → Game Session Service → Social & Groups Service → Logging & Admin Service
```

---

## 5. Purchases and Subscriptions

1. **Payment Processing** – The [Account Service](./microservices/account-service/README.md) handles subscriptions, one-time purchases, and optional donations via Stripe.
2. **Platform Fee & Restrictions** – A small platform fee applies to each transaction and external payment methods are not allowed, per the [Core Requirements](../project-management/core-requirements.md#2.8-moderation-administration--monetization).
3. **Audit and Compliance** – Transactions are logged through the [Logging & Admin Service](./microservices/logging-admin-service/README.md) for reporting and refunds.

```plaintext
Player → Account Service → Logging & Admin Service
```

---

## 6. Password Resets & Account Recovery

Players occasionally lose access to their accounts. Recovery is performed through the [Account Service](./microservices/account-service/README.md), which issues password reset emails and temporary login tokens. Suspicious attempts are logged by the [Logging & Admin Service](./microservices/logging-admin-service/README.md). If two-factor authentication was enabled, the service validates the TOTP code before issuing a new password.

```plaintext
Player → Account Service → Logging & Admin Service (audit)
```

Operational recovery flows (for example, restoring services after outages) are described from the operator perspective in [Operator Recovery Journeys](./user-journeys-operators.md#operator-recovery-journeys), including how incidents surface to players.

---

## 7. Switch Games or Manage Multiple Games

Players can participate in multiple games using the same platform account. The [Multi-Tenancy](./system-architecture-multi-tenancy.md) model stores character progress per `tenantId`. Account selection and tenant setup are managed through the [Account Service](./microservices/account-service/README.md) and [Game Design Service](./microservices/game-design-service/README.md).

```plaintext
Account Service → Game Design Service (select tenant) → Game Session Service
```

---

## 8. Account Data Export & Deletion

Players may request a full data export or permanently delete an account through the [Account Service](./microservices/account-service/README.md). Exported data is provided in JSON format for portability. Deletions require confirmation and are recorded by the [Logging & Admin Service](./microservices/logging-admin-service/README.md) for audit purposes.

```plaintext
Player → Account Service → Logging & Admin Service (audit)
```

---

## Related Documentation

- [Authentication & Authorization](./system-architecture-authentication.md)
- [Game Creator Guide](../user-guides/game-creator-guide.md)
- [Game Customization Options](./game-customization-options.md)
- [Logging & Monitoring Overview](./system-architecture-logging-monitoring.md)
- [Moderation Policies](./microservices/logging-admin-service/moderation-policies.md)
- [Multi-Tenancy](./system-architecture-multi-tenancy.md)
- [Mud Client Protocol (MCP) Support](./system-architecture-mud-client-protocol.md)
- [System Architecture Overview](./system-architecture-overview.md)
- [System Context Diagram](./system-context-diagram.md)

