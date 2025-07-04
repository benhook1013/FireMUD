# 🔄 FireMUD User Journeys

This document outlines a high level flow for creators and players when interacting with the FireMUD platform. Each step references the microservice responsible for that portion of the journey.

---

## 1. Sign Up and Game Creation

1. **Sign Up** – Players create an account through the [Account Service](./microservices/account-service/README.md).
2. **Create a Game** – Newly registered creators use the [Game Design Service](./microservices/game-design-service/README.md) to start a fresh game project.

```plaintext
Player → Account Service → Game Design Service (new game)
```

---

## 2. Editing World Data

Creators design and refine the world using tools from both services:

- **Game Design Service** – Versioned templates and item/ability editors.
- **World Management Service** – Stores maps, regions, and procedural generation rules.

Refer to [Game Design Service](./microservices/game-design-service/README.md) and [World Management Service](./microservices/world-management-service/README.md) for details.

```plaintext
Game Design Edits ↔ World Management Service (maps, rooms)
```

---

## 3. Publish and Start a Game Instance

Once the world is ready:

1. **Publish a Version** – Creators publish the current design in the Game Design Service.
2. **Start a Game Instance** – The [Game Session Service](./microservices/game-session-service/README.md) launches a live instance using that published version.
   For the full rollout process, see [Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md).

```plaintext
Game Design Service (publish) → Game Session Service (start instance)
```

---

## 4. Player Login and Gameplay

Players connect through the networking layer:

1. **Gateway/Proxy** – Telnet clients connect via the [TCP Proxy Service](./microservices/tcp-proxy-service/README.md) while web clients use the [Spring Cloud Gateway](./microservices/spring-cloud-gateway-service/README.md).
2. **Session Management** – Connections reach the Game Session Service, which retrieves entity and world data over gRPC from other services.

```plaintext
Client → Proxy/Gateway → Game Session Service → gRPC calls to Entity/World services
```

The Game Session Service handles login, session recovery, and active gameplay.

---

These flows complement the architecture diagrams in [System Architecture Overview](./system-architecture-overview.md).
