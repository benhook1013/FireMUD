# Game Design Service

## Overview

Offers tools for building worlds, items, actions, and events that make up each game. Used by creators to design content without touching the underlying code. It also maintains versioned game configurations and templates so new game instances can be created with predefined rules and administrators.

## Architecture / Design Notes

- Provides REST/gRPC APIs for editing game data.
- Works closely with World Management and Automation & Scripting Service to apply changes.
- Stores versioned configuration data so new game instances can be generated from templates.
- Maintains history of revisions so designers can roll back to prior versions.

## Key Features

- World and room editors.
- Ability and action design tools.
- Scripting and event workflow creation.
- Game templates with predefined rulesets and administrators.
- Version and patch note management for published games.
- Import/export of design assets for sharing between game worlds.

### Design Workflow

1. Creators use the web UI to craft worlds, items, and scripts.
2. Changes are staged as revisions with metadata and author information.
3. Revisions are grouped into versions that can be published to runtime.

### gRPC APIs

- `SaveRevision` – persists a new or updated design asset.
- `PublishVersion` – freezes a set of revisions and notifies downstream services.
- `ListVersions` – enumerates published versions for selection when creating a
  game instance.

## Dependencies

- **Internal:** World Management Service for map data, Automation & Scripting Service for scripts.
- **External:** PostgreSQL for storing design assets.

> See [**Gateway Architecture**](../../infrastructure/gateway-architecture.md),
[**Deployment Environments**](../../infrastructure/deployment-environments.md),
and [**Protocol Bridging**](../../infrastructure/protocol-bridging.md) for
details on shared infrastructure components.

## Proto Files

The service API contract resides in
[../../../../protos/game-design/v1](../../../../protos/game-design/v1). Generate
stubs with `./gradlew generateProto` whenever these files are updated.

## 📚 Related Documentation

See [Versioning & Runtime Configuration](../system-architecture-versioning-runtime.md) for how published versions are promoted to runtime.

## Future Enhancements

- Web-based visual design interface.
- Version control integration for design assets.
