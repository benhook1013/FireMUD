# Game Templates and Configuration Tools

This document expands on how the Game Design Service provides reusable templates
for new games. Templates bundle world data, scripts and default settings so that
creators can quickly spin up new projects without starting from scratch.

## Template Contents

- **World Layout** – predefined regions and rooms loaded from the World
  Management Service. (TODO: Not yet implemented)
- **Starter Items and NPCs** – basic entity definitions for a new game. (TODO: Not yet implemented)
- **Default Rulesets** – gameplay rules and runtime flags stored with the
  template. (TODO: Not yet implemented)
- **Admin Accounts** – initial administrators configured at template creation. (TODO: Not yet implemented)

Templates are versioned like any other design asset. Publishing a version is intended to copy
these templates to the domain services using the `version_id` workflow described
in [Versioning & Runtime Configuration](../../../design/architecture/system-architecture-versioning-runtime.md). (TODO: Not yet implemented)

## Creating Templates

Creators submit a `GameTemplateDto` via the REST API:

```bash
curl -X POST http://localhost:8080/templates \
     -H 'Content-Type: application/json' \
     -d '{"tenantId":1,"name":"Default","config":"{}"}'
```

The service validates the payload and stores it in the `game_templates` table.
Templates can then be listed per `tenantId` to help bootstrap new games.

Update or delete operations for templates are not yet available. (TODO: Not yet implemented)

## 📚 Related Documentation

- [Game Design Service Architecture](../../../design/architecture/microservices/game-design-service/README.md)
- [Multi-Tenancy](../../../design/architecture/system-architecture-multi-tenancy.md)
- [Service Responsibility Matrix](../../../design/architecture/service-responsibility-matrix.md)
