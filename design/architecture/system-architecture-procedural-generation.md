# FireMUD System Architecture: Procedural Generation

This document describes the basic approach for procedurally generating simple dungeon layouts. The Automation & Scripting Service exposes a generator used during world creation to seed rooms before designers fine tune them.

## Algorithm Overview

1. Choose a random seed so generation is repeatable.
2. Create a starting room and iterate until the requested room count is reached.
3. For each new room, randomly pick an existing room and create a bidirectional exit between them.
4. Ensure exits never exceed four per room (N/E/S/W) to keep navigation simple.

## Responsibilities

- Runs inside the Automation & Scripting Service.
- Generates minimal room metadata which the World Management Service persists.
- Designed for extensibility so terrain features can be added later.

The generator is intentionally lightweight to keep early worlds simple while providing a template for future expansion.
