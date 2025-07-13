# 🔄 FireMUD User Journeys

This guide summarizes typical workflows for creators and players. Each numbered step links to the microservice or design document that manages that portion of the flow.
Use it alongside the [System Architecture Overview](./system-architecture-overview.md) and [System Context Diagram](./system-context-diagram.md) to understand how users traverse the platform.
For a breakdown of every service see the [Microservices Overview](./microservices/README.md) and the [Service Responsibility Matrix](./service-responsibility-matrix.md).
Detailed tooling instructions live in the [Game Creator Guide](../user-guides/game-creator-guide.md).

## 🎯 Goals

- Provide a quick reference for how a user moves through the system.
- Map each step to the microservice that owns the logic or data.
- Link back to deeper design docs for anyone who needs additional context.

---

## 📑 Quick Reference

- [1. Sign Up](#1-sign-up)
- [2. Game Creation](#2-game-creation)
- [3. World and Entity Design](#3-world-and-entity-design)
- [4. Add Automation & Scripting](#4-add-automation--scripting)
- [5. Publish and Start a Game Instance](#5-publish-and-start-a-game-instance)
- [6. Character Creation & Selection](#6-character-creation--selection)
- [7. Player Login and Gameplay](#7-player-login-and-gameplay)
- [8. Social Interaction](#8-social-interaction)
- [9. Monitoring and Moderation](#9-monitoring-and-moderation)
- [10. Patch and Update a Live Game](#10-patch-and-update-a-live-game)
- [11. Purchases and Subscriptions](#11-purchases-and-subscriptions)
- [12. Password Resets & Account Recovery](#12-password-resets--account-recovery)
- [13. Switch Games or Manage Multiple Games](#13-switch-games-or-manage-multiple-games)
- [14. Operational Recovery](#14-operational-recovery)
- [15. Branding and Customization](#15-branding-and-customization)
- [16. Playtesting & Analytics](#16-playtesting--analytics)
- [17. Testing & Continuous Delivery](#17-testing--continuous-delivery)
- [18. Account Data Export & Deletion](#18-account-data-export--deletion)
- [19. Deployment & Environment Configuration](#19-deployment--environment-configuration)
- [20. Observability & Debugging](#20-observability--debugging)
- [21. Extensibility & External Tools](#21-extensibility--external-tools)
- [22. Platform Service Updates](#22-platform-service-updates)

---

## 1. Sign Up

Players register for an account through the [Account Service](./microservices/account-service/README.md). Email verification and login flows are outlined in [Authentication & Authorization](./system-architecture-authentication.md).
Admins and moderators can enable **two-factor authentication** (TOTP) as described in the [Security Architecture](./system-architecture-security.md).

```plaintext
Player → Account Service
```

---

## 2. Game Creation

After signing up, creators start a new project using the [Game Design Service](./microservices/game-design-service/README.md).

```plaintext
Account Service (user) → Game Design Service (new game)
```

---

## 3. World and Entity Design

Creators refine the world and its inhabitants using several services:

- **[Game Design Service](./microservices/game-design-service/README.md)** – Provides versioned templates, ability editors, and runtime flag definitions.
- **[World Management Service](./microservices/world-management-service/README.md)** – Stores zones and maps, generates new areas, and maintains pathfinding data. Scheduled world events notify other services when the environment changes.
- **[Entity Management Service](./microservices/entity-management-service/README.md)** – Manages characters, NPCs, items, and inventory with deferred writes coordinated by the Game Session Service.
- **Procedural Generation** – The [Automation & Scripting Service](./microservices/automation-scripting-service/README.md) provides dungeon seeds and templates. See [Procedural Generation](./system-architecture-procedural-generation.md).
- **MCP Editing** – Connect external tools via the [Mud Client Protocol](./system-architecture-mcp-support.md) to automate room and NPC creation.
- [Game Customization Options](./game-customization-options.md) covers themes and branding tweaks.
- **World Editing Tools** – Use the [World Editing & Customization Tools](./microservices/game-design-service/world-editing-tools.md) for room and region editing.
- **Ability & Action Tools** – Build combat mechanics with the [Ability & Action Design Tools](./microservices/game-design-service/ability-action-tools.md).
- **Item & Equipment Balancing** – Tune gear progression in the [Item & Equipment Balancing Tools](./microservices/game-design-service/item-equipment-balancing.md).
- **Visual Interface** – A [web-based visual editor](./microservices/game-design-service/web-visual-interface.md) provides drag-and-drop editing.
- **Version Control & Templates** – [Version Control](./microservices/game-design-service/version-control.md) and [Game Templates](./microservices/game-design-service/game-templates.md) streamline collaboration and new projects.

```plaintext
Game Design ↔ World Management ↔ Entity Management
```

---

## 4. Add Automation & Scripting

Dynamic behavior is implemented via the [Automation & Scripting Service](./microservices/automation-scripting-service/README.md):

- Script quests and NPC routines.
- Trigger world events in response to player actions.
- See [Scripting & Automation Framework](./system-architecture-scripting.md) for
  details on the component-based DSL and sandboxing model.
- [Modding Framework](./microservices/game-design-service/modding-framework.md)
  enables runtime plugins using the same scripting sandbox.

---

## 5. Publish and Start a Game Instance

Once the world is ready:

1. **Publish a Version** – Creators publish the current design in the Game Design Service.
2. **Start a Game Instance** – The [Game Session Service](./microservices/game-session-service/README.md) launches a live instance using that published version. The [World Creation Workflow](./microservices/world-management-service/world-creation-workflow.md) describes how design data is copied when a brand new world is created. For the full rollout process, see [Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md).

```plaintext
Game Design Service (publish) → Game Session Service (start instance)
```

---

## 6. Character Creation & Selection

Players create or select a character before entering the world:

1. **Account & Character Link** – The [Account Service](./microservices/account-service/README.md) tracks ownership of characters per account.
2. **Character Templates** – Starting attributes come from templates in the [Game Design Service](./microservices/game-design-service/README.md).
3. **Character Storage** – The [Entity Management Service](./microservices/entity-management-service/README.md) persists characters with deferred writes coordinated by the Game Session Service.

```plaintext
Account Service → Game Design Service → Entity Management Service
```

---

## 7. Player Login and Gameplay

Players connect through the networking layer:

1. **Gateway/Proxy** – Telnet clients connect via the [TCP Proxy Service](./microservices/tcp-proxy-service/README.md) while web clients use the [Spring Cloud Gateway](./microservices/spring-cloud-gateway/README.md). Both paths converge into a stateless WebSocket flow; see [Protocol Bridging](./system-architecture-protocol-bridging.md) for details.
2. **Session Management** – The [Game Session Service](./microservices/game-session-service/README.md) retrieves world and entity data over gRPC from other services and dispatches actions to the [Game Logic Service](./microservices/game-logic-service/README.md). Session state is stored in Redis as described in [Redis Architecture](./system-architecture-redis.md).
3. **Authentication** – Login credentials are validated by the [Game Session Service](./microservices/game-session-service/README.md).
   See [Authentication & Authorization](./system-architecture-authentication.md)
   for supported `LOGIN` commands and token handling.
4. **Frontend** – The React client connects through the Gateway using the same WebSocket flow. Component structure and state management are detailed in the [Frontend Architecture](./system-architecture-frontend.md).

```plaintext
Client → Proxy/Gateway → Game Session Service → Game Logic Service / Entity & World services
```

The [Game Session Service](./microservices/game-session-service/README.md) handles login, session recovery, and active gameplay. Game actions are resolved on a fixed tick loop as outlined in the [Tick System](./system-architecture-ticks.md). Players can reconnect seamlessly thanks to the layered approach described in [Reconnection Strategy](./system-architecture-reconnection.md).

---

## 8. Social Interaction

During gameplay, players form groups and communicate via the
[Social & Groups Service](./microservices/social-groups-service/README.md):

- Chat rooms, guilds, and friend lists are synchronized in real time.
- In-game chat commands (say, tell, guild chat, mail) are first validated by the
  [Game Logic Service](./microservices/game-logic-service/README.md) against the [World Management Service](./microservices/world-management-service/README.md) and [Entity Management Service](./microservices/entity-management-service/README.md).
- The Social & Groups Service performs profanity checks, logs communication, and
  delivers messages. Account-level friends automatically appear in-game when the
  feature is enabled.

---

## 9. Monitoring and Moderation

Operators monitor the game and enforce rules using the
[Logging & Admin Service](./microservices/logging-admin-service/README.md).
Logs, metrics, and traces flow into **Elasticsearch**, **Prometheus**, and **Jaeger** as described in
[Logging & Monitoring](./system-architecture-logging-monitoring.md) and
[Tracing](./system-architecture-tracing.md).
For usage examples see the
[Analytics Dashboards](./microservices/logging-admin-service/analytics-dashboards.md).
The service also exposes moderation tools such as bans and runtime feature toggles.
Policies are summarized in
[Moderation Policies](./microservices/logging-admin-service/moderation-policies.md)
and complex workflows are coordinated using the
[Admin Operations Saga](./microservices/logging-admin-service/admin-operations-saga.md).

---

## 10. Patch and Update a Live Game

1. **Iterate on Content** – Creators modify worlds, items, or rules using the [Game Design Service](./microservices/game-design-service/README.md).
2. **Publish a New Version** – The updated design is published with patch notes so players can review changes.
3. **Publish a Script Patch** – For quick fixes, the [Game Design Service](./microservices/game-design-service/README.md) emits a
   `scriptPatchVersion` like `v42-script.3` linked to the current version.
4. **Restart Game Instance** – Administrators instruct the [Game Session Service](./microservices/game-session-service/README.md)
   to load the new `version_id` when a full update is required. Script-only
   patches are applied live without restarting.
5. **Saga Coordination** – Cross-service updates are coordinated using sagas for atomic rollbacks. See [Transaction Strategies](./system-architecture-transactions.md).

6. **Verify Performance** – Check metrics after deployment; see [Performance Optimization Guidelines](./performance-optimization.md).

```plaintext
Game Design Service (publish) → Game Session Service (restart)
```

### Example Hotfix DSL

```yaml
- action: hotfix_script
  version: "v42"
  patchVersion: "v42-script.3"
  scripts:
    - "npc-barkeep"
    - "docks-rat-encounter"
  reason: "Live AI bug fix during event"
```

Hotfixes follow the steps in the [Hotfix Procedure](./system-architecture-runbooks.md#-hotfix-procedure) to ensure minimal downtime.

---

## 11. Purchases and Subscriptions

1. **Payment Processing** – The [Account Service](./microservices/account-service/README.md) handles purchases and subscription renewals via Stripe.
2. **Audit and Compliance** – Transactions are logged through the
   [Logging & Admin Service](./microservices/logging-admin-service/README.md) for
   reporting and refunds.

```plaintext
Player → Account Service → Logging & Admin Service
```

---

## 12. Password Resets & Account Recovery

Players occasionally lose access to their accounts. Recovery is performed
through the [Account Service](./microservices/account-service/README.md),
which issues password reset emails and temporary login tokens. Suspicious
attempts are logged by the
[Logging & Admin Service](./microservices/logging-admin-service/README.md).
If two-factor authentication was enabled, the service validates the TOTP code before issuing a new password.

```plaintext
Player → Account Service → Logging & Admin Service (audit)
```

---

## 13. Switch Games or Manage Multiple Games

Players can participate in multiple games using the same platform account. The
[Multi-Tenancy](./system-architecture-multi-tenancy.md) model stores character
progress per `tenantId`. Account selection and tenant setup are managed through the
[Account Service](./microservices/account-service/README.md) and
[Game Design Service](./microservices/game-design-service/README.md).

```plaintext
Account Service → Game Design Service (select tenant) → Game Session Service
```

---

## 14. Operational Recovery

When issues occur, operators follow the
[Operational Runbooks](./system-architecture-runbooks.md) to restore services.
See [Backup & Disaster Recovery](./system-architecture-backup-recovery.md) for
database snapshots and Redis persistence.

```plaintext
Admin → Runbooks → Kubernetes / Docker → Services Restored
```

---

## 15. Branding and Customization

Creators can change the look and feel of their games without altering the code base. Themes, logos, and layout tweaks are configured through the Game Design Service. The web client loads tenant-specific assets as described in [Frontend Architecture](./system-architecture-frontend.md). See [Game Customization Options](./game-customization-options.md) for details.

---

## 16. Playtesting & Analytics

Before launch or after major updates, creators invite testers to staged environments. Feedback is collected per the [Playtesting & Feedback Plan](../project-management/playtesting-feedback.md) and telemetry is reviewed using the [Analytics Dashboards](./microservices/logging-admin-service/analytics-dashboards.md).

---

## 17. Testing & Continuous Delivery

1. **Run Tests** – Each microservice executes unit and integration tests. See [Testing Strategy](./system-architecture-testing.md).
2. **CI/CD Pipeline** – Changes are built and deployed via GitHub Actions as described in [CI/CD Pipeline](./system-architecture-cicd.md).
3. **Database Migrations** – Schemas are migrated with Flyway on startup; see [Database Migrations](./system-architecture-database-migrations.md).

```plaintext
GitHub → CI Workflow → Container Registry → Kubernetes
```

---

## 18. Account Data Export & Deletion

Players may request a full data export or permanently delete an account through
the [Account Service](./microservices/account-service/README.md). Exported data
is provided in JSON format for portability. Deletions require confirmation and
are recorded by the
[Logging & Admin Service](./microservices/logging-admin-service/README.md) for
audit purposes.

```plaintext
Player → Account Service → Logging & Admin Service (audit)
```

---

## 19. Deployment & Environment Configuration

FireMUD can be deployed locally using **Docker Compose** or to production via **Kubernetes**:

1. **Local Development** – Run `./gradlew devUp` to start all services with Docker Compose. Configuration values are loaded from an `.env` file. See [Deployment Environments](./infrastructure/deployment-environments.md).
2. **Production** – Kubernetes manifests load configuration through `ConfigMap` and `Secret` objects. Refer to [Environment & Secrets Management](./infrastructure/environment-and-secrets.md) for details.
3. **Infrastructure Overview** – Shared networking and deployment patterns are summarized in [Infrastructure Overview](./infrastructure/README.md).

```plaintext
Developer → Docker Compose / Kubernetes → Running Services
```

---

## 20. Observability & Debugging

Operators troubleshoot issues and tune performance using the centralized
[Logging & Admin Service](./microservices/logging-admin-service/README.md) and
observability stack:

1. **Log Aggregation** – Fluent Bit forwards service logs to **Elasticsearch**,
   which are explored via **Kibana**. See
   [Logging & Monitoring](./system-architecture-logging-monitoring.md).
2. **Metrics & Dashboards** – **Prometheus** scrapes metrics and **Grafana**
   visualizes dashboards such as the
   [Service Overview](../observability/grafana/service-overview.json).
3. **Tracing** – Distributed traces are sent to **Jaeger** via the OpenTelemetry
   Collector as described in [Tracing](./system-architecture-tracing.md).

```plaintext
Service Logs → Elasticsearch → Kibana / Jaeger
```

Common troubleshooting steps are documented in the [Operational Runbooks](./system-architecture-runbooks.md).

---

## 21. Extensibility & External Tools

Creators extend gameplay using external editors and runtime plugins:

1. **Mud Client Protocol** – The [TCP Proxy Service](./microservices/tcp-proxy-service/README.md) negotiates MCP so tools can create rooms, items, and NPCs programmatically. See [MCP Support](./system-architecture-mcp-support.md).
2. **Modding Framework** – Plugins packaged through the [Game Design Service](./microservices/game-design-service/modding-framework.md) inject custom logic at runtime. The [Automation & Scripting Service](./microservices/automation-scripting-service/README.md) executes them in a sandbox.

```plaintext
Editor/Tool → TCP Proxy Service → Game Design Service → Automation & Scripting Service
```

---

## 22. Platform Service Updates

Updating FireMUD itself follows the standard CI/CD workflow:

1. **Build New Images** – GitHub Actions compiles each microservice and pushes
   updated container images. See the [CI/CD Pipeline](./system-architecture-cicd.md).
2. **Restart Services** – Kubernetes rolls the new images into the cluster,
   restarting pods one by one.
3. **Apply Schema Migrations** – Each service runs Flyway on startup to migrate
   its database before the Spring application launches.
4. **Verify Health** – Operators monitor metrics and logs to ensure the
   deployment succeeded.

```plaintext
GitHub → Container Registry → Kubernetes → Service Startup (Flyway)
```

---

These flows complement the architecture diagrams in [System Architecture Overview](./system-architecture-overview.md).

## 📚 Related Documentation

- [Ability & Action Design Tools](./microservices/game-design-service/ability-action-tools.md)
- [Analytics Dashboards](./microservices/logging-admin-service/analytics-dashboards.md)
- [Authentication & Authorization](./system-architecture-authentication.md)
- [Backup & Disaster Recovery](./system-architecture-backup-recovery.md)
- [CI/CD Pipeline](./system-architecture-cicd.md)
- [Database Migrations](./system-architecture-database-migrations.md)
- [Deployment Environments](./infrastructure/deployment-environments.md)
- [Environment & Secrets Management](./infrastructure/environment-and-secrets.md)
- [Frontend Architecture](./system-architecture-frontend.md)
- [Game Creator Guide](../user-guides/game-creator-guide.md)
- [Game Customization Options](./game-customization-options.md)
- [Game Templates](./microservices/game-design-service/game-templates.md)
- [Gateway Architecture](./system-architecture-gateway.md)
- [Infrastructure Overview](./infrastructure/README.md)
- [Item & Equipment Balancing Tools](./microservices/game-design-service/item-equipment-balancing.md)
- [Logging & Monitoring Overview](./system-architecture-logging-monitoring.md)
- [MCP Support](./system-architecture-mcp-support.md)
- [Microservices Overview](./microservices/README.md)
- [Modding Framework](./microservices/game-design-service/modding-framework.md)
- [Multi-Tenancy](./system-architecture-multi-tenancy.md)
- [Operational Runbooks](./system-architecture-runbooks.md)
- [Performance Optimization Guidelines](./performance-optimization.md)
- [Playtesting & Feedback Plan](../project-management/playtesting-feedback.md)
- [Procedural Generation](./system-architecture-procedural-generation.md)
- [Protocol Bridging](./system-architecture-protocol-bridging.md)
- [Reconnection Strategy](./system-architecture-reconnection.md)
- [Redis Architecture](./system-architecture-redis.md)
- [Repository Structure](./repository-structure.md)
- [Scripting & Automation Framework](./system-architecture-scripting.md)
- [Security Architecture](./system-architecture-security.md)
- [Service Responsibility Matrix](./service-responsibility-matrix.md)
- [Shared Libraries Overview](./system-architecture-shared-libraries.md)
- [System Architecture Diagram](./system-architecture-diagram.md)
- [System Architecture Overview](./system-architecture-overview.md)
- [System Context Diagram](./system-context-diagram.md)
- [Testing Strategy](./system-architecture-testing.md)
- [Tick System](./system-architecture-ticks.md)
- [Tracing](./system-architecture-tracing.md)
- [Transaction Strategies](./system-architecture-transactions.md)
- [Version Control](./microservices/game-design-service/version-control.md)
- [Web-Based Visual Design Interface](./microservices/game-design-service/web-visual-interface.md)
- [World Editing & Customization Tools](./microservices/game-design-service/world-editing-tools.md)
- [gRPC API Style & Versioning Guidelines](./system-architecture-grpc.md)
