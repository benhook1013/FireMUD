# 🔄 FireMUD User Journeys

This document outlines common flows for creators and players when interacting with the platform. Each step references the microservice responsible for that portion of the journey.
For step-by-step tooling instructions see the [Game Creator Guide](../user-guides/game-creator-guide.md).

---

## 1. Sign Up

Players register for an account through the [Account Service](./microservices/account-service/README.md).

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

- **Game Design Service** – Versioned templates, ability editors, and runtime flag definitions.
- **World Management Service** – Maps, regions, and procedural generation rules ([see procedural generation](./system-architecture-procedural-generation.md)).
- **Entity Management Service** – Player characters, NPCs, items, and inventory.
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

1. **Gateway/Proxy** – Telnet clients connect via the [TCP Proxy Service](./microservices/tcp-proxy-service/README.md) while web clients use the [Spring Cloud Gateway](./microservices/spring-cloud-gateway/README.md).
2. **Session Management** – The Game Session Service retrieves world and entity data over gRPC from other services and dispatches actions to the [Game Logic Service](./microservices/game-logic-service/README.md). Session state is stored in Redis as described in [Redis Architecture](./system-architecture-redis.md).
3. **Authentication** – Login credentials are validated by the Game Session Service.
   See [Authentication & Authorization](./system-architecture-authentication.md)
   for supported `LOGIN` commands and token handling.

```plaintext
Client → Proxy/Gateway → Game Session Service → Game Logic Service / Entity & World services
```

The Game Session Service handles login, session recovery, and active gameplay. Players can reconnect seamlessly thanks to the layered approach described in [Reconnection Strategy](./system-architecture-reconnection.md).

---

## 7. Social Interaction

During gameplay, players form groups and communicate via the
[Social & Groups Service](./microservices/social-groups-service/README.md):

- Chat rooms, guilds, and friend lists are synchronized in real time.
- In-game chat commands (say, tell, guild chat, mail) are first validated by the
  Game Logic Service against the World Management and Entity Management
  services.
- The Social & Groups Service performs profanity checks, logs communication, and
  delivers messages. Account-level friends automatically appear in-game when the
  feature is enabled.

---

## 8. Monitoring and Moderation

Operators monitor the game and enforce rules using the
[Logging & Admin Service](./microservices/logging-admin-service/README.md).
Logs and metrics flow into Elasticsearch and Prometheus as described in
[Logging & Monitoring](./system-architecture-logging-monitoring.md). For usage examples see the [Analytics Dashboards](./microservices/logging-admin-service/analytics-dashboards.md).
The service also exposes moderation tools such as bans or runtime feature toggles.

---

## 9. Patch and Update a Live Game

1. **Iterate on Content** – Creators modify worlds, items, or rules using the Game Design Service.
2. **Publish a New Version** – The updated design is published with patch notes so players can review changes.
3. **Publish a Script Patch** – For quick fixes, the Game Design Service emits a
   `scriptPatchVersion` like `v42-script.3` linked to the current version.
4. **Restart Game Instance** – Administrators instruct the Game Session Service
   to load the new `version_id` when a full update is required. Script-only
   patches are applied live without restarting.

5. **Verify Performance** – Check metrics after deployment; see [Performance Optimization Guidelines](./performance-optimization.md).

```plaintext
Game Design Service (publish) → Game Session Service (restart)
```

Example user-journey DSL entry for a hotfix:

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
progress per `tenantId`.

```plaintext
Account Service → Game Design Service (select tenant) → Game Session Service
```

---

## 13. Operational Recovery

When issues occur, operators follow the
[Operational Runbooks](./system-architecture-runbooks.md) to restore services.
Database snapshots and Redis persistence are described in
[Backup & Disaster Recovery](./system-architecture-backup-recovery.md).

```plaintext
Admin → Runbooks → Kubernetes / Docker → Services Restored
```

---

## 14. Branding and Customization

Creators can change the look and feel of their games without altering the code base. Themes, logos, and layout tweaks are configured through the Game Design Service. See [Game Customization Options](./game-customization-options.md) for details.

---

## 15. Playtesting & Analytics

Before launch or after major updates, creators invite testers to staged environments. Feedback is collected per the [Playtesting & Feedback Plan](../project-management/playtesting-feedback.md) and telemetry is reviewed using the [Analytics Dashboards](./microservices/logging-admin-service/analytics-dashboards.md).

## 16. Testing & Continuous Delivery

1. **Run Tests** – Each microservice executes unit and integration tests. See [Testing Strategy](./system-architecture-testing.md).
2. **CI/CD Pipeline** – Changes are built and deployed via GitHub Actions as described in [CI/CD Pipeline](./system-architecture-cicd.md).

```plaintext
GitHub → CI Workflow → Container Registry → Kubernetes
```

---

These flows complement the architecture diagrams in [System Architecture Overview](./system-architecture-overview.md).

## 📚 Related Documentation

- [System Architecture Overview](./system-architecture-overview.md)
- [Service Responsibility Matrix](./service-responsibility-matrix.md)
- [Microservices Overview](./microservices/README.md)
- [Game Creator Guide](../user-guides/game-creator-guide.md)
- [Playtesting & Feedback Plan](../project-management/playtesting-feedback.md)
- [Game Customization Options](./game-customization-options.md)
- [Analytics Dashboards](./microservices/logging-admin-service/analytics-dashboards.md)
- [Performance Optimization Guidelines](./performance-optimization.md)
- [CI/CD Pipeline](./system-architecture-cicd.md)
- [Testing Strategy](./system-architecture-testing.md)
- [System Context Diagram](./system-context-diagram.md)
