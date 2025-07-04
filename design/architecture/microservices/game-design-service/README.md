# Game Design Service

## Overview

Offers tools for building worlds, items, actions, and events that make up each game. Used by creators to design content without touching the underlying code. It also maintains versioned game configurations and templates so new game instances can be created with predefined rules and administrators.

## Architecture / Design Notes

- Provides REST/gRPC APIs for editing game data.
- Works closely with World Management and Automation & Scripting Service to apply changes.
- Stores versioned configuration data so new game instances can be generated from templates.

## Key Features

- World and room editors.
- Ability and action design tools.
- Scripting and event workflow creation.
- Game templates with predefined rulesets and administrators.
- Version and patch note management for published games.

## Dependencies

- **Internal:** World Management Service for map data, Automation & Scripting Service for scripts.
- **External:** PostgreSQL for storing design assets.

> See [**Gateway Architecture**](../../infrastructure/gateway-architecture.md),
[**Deployment Environments**](../../infrastructure/deployment-environments.md),
and [**Protocol Bridging**](../../infrastructure/protocol-bridging.md) for
details on shared infrastructure components.

## 📚 Related Documentation

See [Versioning & Runtime Configuration](../system-architecture-versioning-runtime.md) for how published versions are promoted to runtime.

## Future Enhancements

- Web-based visual design interface.
- Version control integration for design assets.
