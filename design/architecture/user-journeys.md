# FireMUD User Journeys

This document is a hub for FireMUD user journeys across three personas: **players**, **creators**, and **operators**. It summarizes the major flows and points to detailed persona-specific guides. Use it alongside the [Architecture Overview](./README.md), the [System Architecture Overview](./system-architecture-overview.md), the [System Architecture Diagram](./system-architecture-diagram.md), and the [System Context Diagram](./system-context-diagram.md) to understand how users traverse the platform. For a breakdown of every service see the [Microservices Overview](./microservices/README.md) and the [Service Responsibility Matrix](./service-responsibility-matrix.md).

Accounts span multiple hosted games. The [Multi-Tenancy](./system-architecture-multi-tenancy.md) model explains how characters and worlds remain isolated under a single platform account.

## Table of Contents

- [Goals](#goals)
- [Quick Reference](#quick-reference)
- [Persona Guides](#persona-guides)
- [Related Documentation](#related-documentation)

---

## Goals

- Provide a quick reference for how different personas move through the system.
- Map each journey to the microservice(s) that own the logic or data via the persona guides.
- Link back to deeper design docs and diagrams for anyone who needs additional context.

---

## Quick Reference

### Players

Player journeys focus on account management, characters, gameplay, and social features:

- **Account Lifecycle** – Sign up, login, password resets, two-factor authentication, linked external accounts, subscriptions, purchases, and account data export/deletion are covered in [Player Journeys – Sign Up](./user-journeys-players.md#1-sign-up), [Player Journeys – Join a Game for the First Time](./user-journeys-players.md#2-join-a-game-for-the-first-time), and [Account Data Export & Deletion](./user-journeys-players.md#9-account-data-export--deletion). These flows rely primarily on the [Account Service](./microservices/account-service/README.md) and [Logging & Admin Service](./microservices/logging-admin-service/README.md).
- **Characters and Gameplay** – Character creation and selection, realm discovery, game switching, and moment-to-moment gameplay are described in [Join a Game for the First Time](./user-journeys-players.md#2-join-a-game-for-the-first-time), [Character Creation & Selection](./user-journeys-players.md#3-character-creation--selection), [Player Login and Gameplay](./user-journeys-players.md#4-player-login-and-gameplay), and [Switch Games or Manage Multiple Games](./user-journeys-players.md#8-switch-games-or-manage-multiple-games). These flows involve the Game Session, Entity Management, Game Design, and World Management services.
- **Social Interaction** – In-game chat, friends, guilds, reporting, and moderation outcomes are summarized in [Social Interaction & Safety](./user-journeys-players.md#5-social-interaction--safety) and feed into operator moderation flows via the Social & Groups Service and Logging & Admin Service.

### Creators

Creator journeys focus on designing games, publishing worlds, and iterating on content:

- **Game Design and Content Authoring** – Game creation, world and entity design, and scripted behavior are described in [Game Creation](./user-journeys-creators.md#1-game-creation), [World and Entity Design](./user-journeys-creators.md#2-world-and-entity-design), and [Add Automation & Scripting](./user-journeys-creators.md#3-add-automation--scripting). These flows rely on the Game Design, World Management, Entity Management, and Automation & Scripting services.
- **Publishing and Live Updates** – Launching production realms, patching content, and cutover/rollback flows are covered in [Publish and Start a Game Instance](./user-journeys-creators.md#4-publish-and-start-a-game-instance) and [Patch and Update a Live Game](./user-journeys-creators.md#5-patch-and-update-a-live-game). Operators share the same CI/CD and deployment pipelines, detailed in [Testing & Continuous Delivery](./user-journeys-operators.md#3-testing--continuous-delivery) and [Platform Service Updates](./user-journeys-operators.md#6-platform-service-updates).
- **Branding, Playtesting, and Extensibility** – Custom themes, UI branding, playtests, analytics, and extensibility via MCP and modding are summarized in [Branding and Customization](./user-journeys-creators.md#6-branding-and-customization), [Playtesting & Analytics](./user-journeys-creators.md#7-playtesting--analytics), and [Extensibility & External Tools](./user-journeys-creators.md#8-extensibility--external-tools).

### Operators

Operator journeys focus on keeping FireMUD healthy, observable, and up to date:

- **Monitoring and Moderation** – Dashboards, alerts, moderation tools, and policy enforcement are described in [Monitoring and Moderation](./user-journeys-operators.md#1-monitoring-and-moderation), built around the Logging & Admin Service and observability stack.
- **Operational Recovery** – Backup/restore, Redis and PostgreSQL recovery, and player-visible impact are summarized in [Operator Recovery Journeys](./user-journeys-operators.md#2-operator-recovery-journeys), which reference the Operational Runbooks and Backup & Disaster Recovery docs.
- **Deployments and Platform Updates** – CI/CD pipelines, environment configuration, observability-assisted debugging, and platform service upgrades are covered in [Testing & Continuous Delivery](./user-journeys-operators.md#3-testing--continuous-delivery), [Deployment & Environment Configuration](./user-journeys-operators.md#4-deployment--environment-configuration), [Observability & Debugging](./user-journeys-operators.md#5-observability--debugging), and [Platform Service Updates](./user-journeys-operators.md#6-platform-service-updates).

---

## Persona Guides

- **Players** – See [FireMUD User Journeys: Players](./user-journeys-players.md) for account lifecycle, characters, gameplay, social interaction, purchases/subscriptions, and account recovery/export flows.
- **Creators** – See [FireMUD User Journeys: Creators](./user-journeys-creators.md) for game creation, world and entity design, automation & scripting, publishing, live updates, branding, playtesting, and extensibility.
- **Operators** – See [FireMUD User Journeys: Operators](./user-journeys-operators.md) for monitoring, moderation, operational recovery, deployment, observability, and platform service updates.

Use these guides together with the system architecture documents to trace any journey from user action to underlying services and infrastructure.

---

## Related Documentation

- [Architecture Overview](./README.md)
- [System Architecture Overview](./system-architecture-overview.md)
- [System Architecture Diagram](./system-architecture-diagram.md)
- [System Context Diagram](./system-context-diagram.md)
- [Multi-Tenancy](./system-architecture-multi-tenancy.md)
- [Microservices Overview](./microservices/README.md)
- [Service Responsibility Matrix](./service-responsibility-matrix.md)
