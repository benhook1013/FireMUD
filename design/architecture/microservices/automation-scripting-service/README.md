# Automation & Scripting Service

## Overview

The Automation & Scripting Service drives non-player character (NPC) behavior and world automation. It executes custom scripts and AI routines so worlds stay alive even when no players are online.

For details on how scripts are authored and executed safely, see [System Architecture: Scripting & Automation](../system-architecture-scripting.md).

## Architecture / Design Notes

- Event-driven execution engine triggered by world or player events.
- AI computations are optimized for large worlds using tick-based batching.
- NPCs that are far from active players are deprioritized and only "wake up" on interaction.

## Key Features

- Scriptable quests and event triggers.
- Persistent NPC memory and dynamic reactions.
- On-demand AI execution to reduce CPU load.

## Dependencies

- **Internal:** Game Logic Service for rule evaluation.
- **External:** Redis for queuing automation tasks.

## Future Enhancements

- Web UI for creating and testing scripts.
- Additional AI modules for advanced behaviors.
