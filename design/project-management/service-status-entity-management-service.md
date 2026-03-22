# Entity Management Service Status

## Current Coverage

- Character, NPC, item, inventory, crafting, and room-entity query surfaces are implemented.
- The data-driven `LOOK` slice is wired to entity visibility data through the service’s gRPC surface.
- Cross-game character listing and crafting-oriented domain structures exist in the current codebase.

## Current Role In The Platform

- Owns entity persistence, containment, and inventory state.
- Supplies visible room entities and gameplay-facing entity data to Game Logic and Game Session.
- Coordinates with Game Design for templates and with World Management for location context.

## Partial / Stubbed / Deferred Areas

- Cache strategy and reset-tolerant Redis behavior are defined architecturally but not fully reflected as finished runtime hardening.
- Some deeper cross-service and integration coverage remains lighter than the unit/service-level implementation itself.
- Broader character-ownership and publish-copy flows across services are still part of the larger app build-out.

## Planning Notes

- Use vertical-slice docs for active work.
- Keep this file limited to implemented/partial status so it stays readable.
