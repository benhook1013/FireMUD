# Game Design Service

## Overview
Offers tools for building worlds, items, actions, and events that make up each game. Used by creators to design content without touching the underlying code.

## Architecture / Design Notes
- Provides REST/gRPC APIs for editing game data.
- Works closely with World Management and Automation services to apply changes.

## Key Features
- World and room editors.
- Ability and action design tools.
- Scripting and event workflow creation.

## Dependencies
- **Internal:** World Management Service for map data, Automation Service for scripts.
- **External:** PostgreSQL for storing design assets.

## Future Enhancements
- Web-based visual design interface.
- Version control integration for design assets.
