# Game Creator Guide

This guide helps game creators customize their worlds on the hosted FireMUD platform. It assumes the platform is already running and focuses on using the provided tools and APIs.

---

## Getting Started

1. **Create an Account** – Sign up through the Account Service and verify your email.
   See the [Account Service](../architecture/microservices/account-service/README.md)
   documentation for registration and verification steps.
2. **Provision a Game** – Use the Game Design Service to create your first game world.
   This creates a draft tenant first; public gameplay does not begin until you launch a production realm.
   Initial realm launch follows the [World Creation Workflow](../architecture/microservices/world-management-service/world-creation-workflow.md).
   A default world template is available. See the
   [Game Templates](../architecture/microservices/game-design-service/game-templates.md) guide.
3. **Open the Game Editor** – Launch the Game Editor from your dashboard to begin customizing
   zones, rooms, and entities. The
   [Web-Based Visual Design Interface][web-editor]
   provides an intuitive graphical editor.

## Configuring Hosted Games

- **World Management** – Import or create zones, rooms, and entities using the Game Editor.
  See
  [World Editing & Customization Tools][world-edit].
- **Runtime Settings** – Adjust tick intervals and feature flags through the Admin interface.
  See the
  [Role-Based Admin UI](../architecture/microservices/logging-admin-service/admin-ui.md)
  documentation.
- **Go-Live Prerequisites** – A tenant can remain in draft/edit-only mode while you build content. To expose a public production realm, publish a version and ensure the tenant's billing/entitlements allow gameplay.
- **Playtest Forks** – Before cutting production over to a new version, create an isolated fork realm from a source realm snapshot, invite testers, and validate the new ruleset against realistic state. Fork writes never merge back into production automatically.
- **Multi-Tenancy** – Each game is isolated by a unique identifier so you can manage multiple worlds from one account. See the [Multi-Tenancy design](../architecture/system-architecture-multi-tenancy.md).

## Scripting & Integration API

FireMUD exposes gRPC and REST endpoints for automation. Key APIs include:

- **Automation & Scripting Service** – Schedule actions, react to events, and control NPCs.
- **Game Session Service** – Start sessions, manage connections, and broadcast game events.
- **Entity Management Service** – CRUD operations for players, NPCs, and items.

Consult the generated [gRPC docs](../grpc-docs/grpc-api.md) for full protobuf definitions and message structures.

### Example Script Snippet

```java
// Pseudo-code for scheduling a greeting when a player enters a room
scriptService.schedule("onEnter", playerId, roomId, () -> {
    sessionService.sendMessage(playerId, "Welcome to the training grounds!");
});
```

## Related Documentation

- [System Architecture: Scripting & Automation](../architecture/system-architecture-scripting.md)
- [User Journeys](../architecture/user-journeys.md)

[world-edit]: ../architecture/microservices/game-design-service/world-editing-tools.md
[web-editor]: ../architecture/microservices/game-design-service/web-visual-interface.md
