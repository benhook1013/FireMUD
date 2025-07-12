# 🔄 FireMUD User Journeys

This document outlines common flows for creators and players when interacting with the platform. Each step references the microservice responsible for that portion of the journey.

---

## 1. Sign Up and Game Creation

1. **Sign Up** – Players create an account through the [Account Service](./microservices/account-service/README.md).
2. **Create a Game** – Newly registered creators use the [Game Design Service](./microservices/game-design-service/README.md) to start a fresh game project.

```plaintext
Player → Account Service → Game Design Service (new game)
```

---

## 2. World and Entity Design

Creators refine the world and its inhabitants using several services:

- **Game Design Service** – Versioned templates, ability editors, and runtime flag definitions.
- **World Management Service** – Maps, regions, and procedural generation rules.
- **Entity Management Service** – Player characters, NPCs, items, and inventory.

```plaintext
Game Design ↔ World Management ↔ Entity Management
```

---

## 3. Add Automation & Scripting

Dynamic behavior is implemented via the [Automation & Scripting Service](./microservices/automation-scripting-service/README.md):

- Script quests and NPC routines.
- Trigger world events in response to player actions.
- See [Scripting & Automation Framework](./system-architecture-scripting.md) for
  details on the component-based DSL and sandboxing model.

---

## 4. Publish and Start a Game Instance

Once the world is ready:

1. **Publish a Version** – Creators publish the current design in the Game Design Service.
2. **Start a Game Instance** – The [Game Session Service](./microservices/game-session-service/README.md) launches a live instance using that published version. For the full rollout process, see [Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md).

```plaintext
Game Design Service (publish) → Game Session Service (start instance)
```

---

## 5. Player Login and Gameplay

Players connect through the networking layer:

1. **Gateway/Proxy** – Telnet clients connect via the [TCP Proxy Service](./microservices/tcp-proxy-service/README.md) while web clients use the [Spring Cloud Gateway](./microservices/spring-cloud-gateway/README.md).
2. **Session Management** – The Game Session Service retrieves world and entity data over gRPC from other services and dispatches actions to the [Game Logic Service](./microservices/game-logic-service/README.md).
3. **Authentication** – Login credentials are validated by the Game Session Service.
   See [Authentication & Authorization](./system-architecture-authentication.md)
   for supported `LOGIN` commands and token handling.

```plaintext
Client → Proxy/Gateway → Game Session Service → Game Logic Service / Entity & World services
```

The Game Session Service handles login, session recovery, and active gameplay. Players can reconnect seamlessly thanks to the layered approach described in [Reconnection Strategy](./system-architecture-reconnection.md).

---

## 6. Social Interaction

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

## 7. Monitoring and Moderation

Operators monitor the game and enforce rules using the
[Logging & Admin Service](./microservices/logging-admin-service/README.md).
Logs and metrics flow into Elasticsearch and Prometheus as described in
[Logging & Monitoring](./system-architecture-logging-monitoring.md). The service
also exposes moderation tools such as bans or runtime feature toggles.

---

## 8. Patch and Update a Live Game

1. **Iterate on Content** – Creators modify worlds, items, or rules using the Game Design Service.
2. **Publish a New Version** – The updated design is published with patch notes so players can review changes.
3. **Restart Game Instance** – Administrators instruct the Game Session Service to load the new `version_id`.
   Build and deployment steps run through the
   [CI/CD Pipeline](./system-architecture-cicd.md).

```plaintext
Game Design Service (publish) → Game Session Service (restart)
```

---

## 9. Purchases and Subscriptions

1. **Payment Processing** – The [Account Service](./microservices/account-service/README.md) handles purchases and subscription renewals via Stripe.
2. **Audit and Compliance** – Transactions are logged through the
   [Logging & Admin Service](./microservices/logging-admin-service/README.md) for
   reporting and refunds.

```plaintext
Player → Account Service → Logging & Admin Service
```

---

## 10. Switch Games or Manage Multiple Games

Players can participate in multiple games using the same platform account. The
[Multi-Tenancy](./system-architecture-multi-tenancy.md) model stores character
progress per `tenantId`.

```plaintext
Account Service → Game Design Service (select tenant) → Game Session Service
```

---

## 11. Operational Recovery

When issues occur, operators follow the
[Operational Runbooks](./system-architecture-runbooks.md) to restore services.
Database snapshots and Redis persistence are described in
[Backup & Disaster Recovery](./system-architecture-backup-recovery.md).

```plaintext
Admin → Runbooks → Kubernetes / Docker → Services Restored
```

---

These flows complement the architecture diagrams in [System Architecture Overview](./system-architecture-overview.md).
