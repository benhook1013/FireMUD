# 🔄 FireMUD User Journeys

This document outlines common flows for creators and players when interacting with the platform. Each step references the microservice responsible for that portion of the journey.
It complements the [System Architecture Overview](./system-architecture-overview.md) and [System Context Diagram](./system-context-diagram.md) to show how users traverse the overall platform.
For step-by-step tooling instructions see the [Game Creator Guide](../user-guides/game-creator-guide.md).

## 🎯 Goals

- Provide a quick reference for how a user moves through the system.
- Map each step to the microservice that owns the logic or data.
- Link back to deeper design docs for anyone who needs additional context.

---

## 1. Sign Up

Players register for an account through the [Account Service](./microservices/account-service/README.md). Email verification and login flows are outlined in [Authentication & Authorization](./system-architecture-authentication.md).

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

---

## 5. Publish and Start a Game Instance

Once the world is ready:

1. **Publish a Version** – Creators publish the current design in the Game Design Service.
2. **Start a Game Instance** – The [Game Session Service](./microservices/game-session-service/README.md) launches a live instance using that published version. For the full rollout process, see [Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md).

```plaintext
Game Design Service (publish) → Game Session Service (start instance)
```

---

## 6. Player Login and Gameplay

Players connect through the networking layer:

1. **Gateway/Proxy** – Telnet clients connect via the [TCP Proxy Service](./microservices/tcp-proxy-service/README.md) while web clients use the [Spring Cloud Gateway](./microservices/spring-cloud-gateway/README.md). Both paths converge into a stateless WebSocket flow; see [Protocol Bridging](./system-architecture-protocol-bridging.md) for details.
2. **Session Management** – The [Game Session Service](./microservices/game-session-service/README.md) retrieves world and entity data over gRPC from other services and dispatches actions to the [Game Logic Service](./microservices/game-logic-service/README.md). Session state is stored in Redis as described in [Redis Architecture](./system-architecture-redis.md).
3. **Authentication** – Login credentials are validated by the [Game Session Service](./microservices/game-session-service/README.md).
   See [Authentication & Authorization](./system-architecture-authentication.md)
   for supported `LOGIN` commands and token handling.

```plaintext
Client → Proxy/Gateway → Game Session Service → Game Logic Service / Entity & World services
```

The [Game Session Service](./microservices/game-session-service/README.md) handles login, session recovery, and active gameplay. Game actions are resolved on a fixed tick loop as outlined in the [Tick System](./system-architecture-ticks.md). Players can reconnect seamlessly thanks to the layered approach described in [Reconnection Strategy](./system-architecture-reconnection.md).

---

## 7. Social Interaction

During gameplay, players form groups and communicate via the
[Social & Groups Service](./microservices/social-groups-service/README.md):

- Chat rooms, guilds, and friend lists are synchronized in real time.
- In-game chat commands (say, tell, guild chat, mail) are first validated by the
  [Game Logic Service](./microservices/game-logic-service/README.md) against the [World Management Service](./microservices/world-management-service/README.md) and [Entity Management Service](./microservices/entity-management-service/README.md).
- The Social & Groups Service performs profanity checks, logs communication, and
  delivers messages. Account-level friends automatically appear in-game when the
  feature is enabled.

---

## 8. Monitoring and Moderation

Operators monitor the game and enforce rules using the
[Logging & Admin Service](./microservices/logging-admin-service/README.md).
Logs, metrics, and traces flow into **Elasticsearch**, **Prometheus**, and **Jaeger** as described in
[Logging & Monitoring](./system-architecture-logging-monitoring.md) and
[Tracing](./system-architecture-tracing.md).
For usage examples see the
[Analytics Dashboards](./microservices/logging-admin-service/analytics-dashboards.md).
The service also exposes moderation tools such as bans and runtime feature toggles.

---

## 9. Patch and Update a Live Game

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

---

## 10. Purchases and Subscriptions

1. **Payment Processing** – The [Account Service](./microservices/account-service/README.md) handles purchases and subscription renewals via Stripe.
2. **Audit and Compliance** – Transactions are logged through the
   [Logging & Admin Service](./microservices/logging-admin-service/README.md) for
   reporting and refunds.

```plaintext
Player → Account Service → Logging & Admin Service
```

---

## 11. Password Resets & Account Recovery

Players occasionally lose access to their accounts. Recovery is performed
through the [Account Service](./microservices/account-service/README.md),
which issues password reset emails and temporary login tokens. Suspicious
attempts are logged by the
[Logging & Admin Service](./microservices/logging-admin-service/README.md).

```plaintext
Player → Account Service → Logging & Admin Service (audit)
```

---

## 12. Switch Games or Manage Multiple Games

Players can participate in multiple games using the same platform account. The
[Multi-Tenancy](./system-architecture-multi-tenancy.md) model stores character
progress per `tenantId`. Account selection and tenant setup are managed through the
[Account Service](./microservices/account-service/README.md) and
[Game Design Service](./microservices/game-design-service/README.md).

```plaintext
Account Service → Game Design Service (select tenant) → Game Session Service
```

---

## 13. Operational Recovery

When issues occur, operators follow the
[Operational Runbooks](./system-architecture-runbooks.md) to restore services.
See [Backup & Disaster Recovery](./system-architecture-backup-recovery.md) for
database snapshots and Redis persistence.

```plaintext
Admin → Runbooks → Kubernetes / Docker → Services Restored
```

---

## 14. Branding and Customization

Creators can change the look and feel of their games without altering the code base. Themes, logos, and layout tweaks are configured through the Game Design Service. The web client loads tenant-specific assets as described in [Frontend Architecture](./system-architecture-frontend.md). See [Game Customization Options](./game-customization-options.md) for details.

---

## 15. Playtesting & Analytics

Before launch or after major updates, creators invite testers to staged environments. Feedback is collected per the [Playtesting & Feedback Plan](../project-management/playtesting-feedback.md) and telemetry is reviewed using the [Analytics Dashboards](./microservices/logging-admin-service/analytics-dashboards.md).

---

## 16. Testing & Continuous Delivery

1. **Run Tests** – Each microservice executes unit and integration tests. See [Testing Strategy](./system-architecture-testing.md).
2. **CI/CD Pipeline** – Changes are built and deployed via GitHub Actions as described in [CI/CD Pipeline](./system-architecture-cicd.md).

```plaintext
GitHub → CI Workflow → Container Registry → Kubernetes
```

---

## 17. Account Data Export & Deletion

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

## 18. Deployment & Environment Configuration

FireMUD can be deployed locally using **Docker Compose** or to production via **Kubernetes**:

1. **Local Development** – Run `./gradlew devUp` to start all services with Docker Compose. Configuration values are loaded from an `.env` file. See [Deployment Environments](./infrastructure/deployment-environments.md).
2. **Production** – Kubernetes manifests load configuration through `ConfigMap` and `Secret` objects. Refer to [Environment & Secrets Management](./infrastructure/environment-and-secrets.md) for details.

```plaintext
Developer → Docker Compose / Kubernetes → Running Services
```

---

These flows complement the architecture diagrams in [System Architecture Overview](./system-architecture-overview.md).

## 📚 Related Documentation

- [System Architecture Overview](./system-architecture-overview.md)
- [System Architecture Diagram](./system-architecture-diagram.md)
- [System Context Diagram](./system-context-diagram.md)
- [Service Responsibility Matrix](./service-responsibility-matrix.md)
- [Microservices Overview](./microservices/README.md)
- [Game Creator Guide](../user-guides/game-creator-guide.md)
- [Playtesting & Feedback Plan](../project-management/playtesting-feedback.md)
- [Game Customization Options](./game-customization-options.md)
- [Frontend Architecture](./system-architecture-frontend.md)
- [Repository Structure](./repository-structure.md)
- [Analytics Dashboards](./microservices/logging-admin-service/analytics-dashboards.md)
- [Performance Optimization Guidelines](./performance-optimization.md)
- [CI/CD Pipeline](./system-architecture-cicd.md)
- [Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md)
- [Transaction Strategies](./system-architecture-transactions.md)
- [Testing Strategy](./system-architecture-testing.md)
- [Procedural Generation](./system-architecture-procedural-generation.md)
- [gRPC API Style & Versioning Guidelines](./system-architecture-grpc.md)
- [Shared Libraries Overview](./system-architecture-shared-libraries.md)
- [Database Migrations](./system-architecture-database-migrations.md)
- [Multi-Tenancy](./system-architecture-multi-tenancy.md)
- [Reconnection Strategy](./system-architecture-reconnection.md)
- [Protocol Bridging](./system-architecture-protocol-bridging.md)
- [MCP Support](./system-architecture-mcp-support.md)
- [Logging & Monitoring Overview](./system-architecture-logging-monitoring.md)
- [Tracing](./system-architecture-tracing.md)
- [Operational Runbooks](./system-architecture-runbooks.md)
- [Backup & Disaster Recovery](./system-architecture-backup-recovery.md)
- [Gateway Architecture](./system-architecture-gateway.md)
- [Security Architecture](./system-architecture-security.md)
- [Deployment Environments](./infrastructure/deployment-environments.md)
- [Environment & Secrets Management](./infrastructure/environment-and-secrets.md)
