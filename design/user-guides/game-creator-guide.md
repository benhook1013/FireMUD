# Game Creator Guide

This guide helps game creators customize their worlds on the hosted FireMUD platform. It assumes the platform is already running and focuses on using the provided tools and APIs.

---

## Getting Started

1. **Create an Account** – Sign up through the Account Service and verify your email.
2. **Provision a Game** – Use the Game Design Service to create your first game world. A default world template is provided. See the [Game Templates](../architecture/microservices/game-design-service/game-templates.md) guide. (TODO: Not yet implemented)
3. **Open the Game Editor** – Launch the Game Editor from your dashboard to begin customizing zones, rooms, and entities. See the [Web-Based Visual Design Interface](../architecture/microservices/game-design-service/web-visual-interface.md) for planned features. (TODO: Not yet implemented)

## Configuring Hosted Games

- **World Management** – Import or create zones, rooms, and entities using the Game Editor. See [World Editing & Customization Tools](../architecture/microservices/game-design-service/world-editing-tools.md). (TODO: Not yet implemented)
- **Runtime Settings** – Adjust tick intervals and feature flags through the Admin interface. See the [Role-Based Admin UI](../architecture/microservices/logging-admin-service/admin-ui.md) documentation. (TODO: Not yet implemented)
- **Multi-Tenancy** – Each game is isolated by a unique identifier so you can manage multiple worlds from one account.

## Scripting & Integration API

FireMUD exposes gRPC and REST endpoints for automation. Key APIs include:

- **Automation & Scripting Service** – Schedule actions, react to events, and control NPCs.
- **Game Session Service** – Start sessions, manage connections, and broadcast game events.
- **Entity Management Service** – CRUD operations for players, NPCs, and items.

Consult the generated [gRPC docs](../grpc-docs/README.md) for full protobuf definitions and message structures.

### Example Script Snippet

```java
// Pseudo-code for scheduling a greeting when a player enters a room
scriptService.schedule("onEnter", playerId, roomId, () -> {
    sessionService.sendMessage(playerId, "Welcome to the training grounds!");
});
```

## 📚 Related Documentation

- [System Architecture: Scripting & Automation](../architecture/system-architecture-scripting.md)
- [User Journeys](../architecture/user-journeys.md)
