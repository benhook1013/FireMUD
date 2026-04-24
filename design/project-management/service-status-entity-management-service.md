# Entity Management Service Status

## Current Coverage

- Character, NPC, item, inventory, equipment, container, crafting, and room-entity query surfaces are implemented.
- The data-driven `LOOK` slice is wired to entity visibility data through the service’s gRPC surface.
- Actor gameplay state has an initial read substrate: persisted resource rows and active conditions are queryable through the gameplay-attested `QueryActorState` RPC and are merged with baseline character stat fields for current effective reads.
- A first typed effect-evaluation seam exists for deterministic additive, multiplicative, clamp, flag, and condition projections; active condition payloads and equipped item-template payloads are wired through it for actor-state reads, while transient action-state producers remain pending.
- Runtime item/equipment/container mutation RPCs now carry session/effect context, use the current replay guard for duplicate effect delivery, and persist `item_transfer_audits` for successful holder movement.
- Character creation and gameplay-scoped character lookup now use the canonical resolved playable-state scope, character read surfaces echo that scope back to callers instead of hiding realm policy behind an internal state-key derivation, and the character-owned REST surfaces for friends, inventory, and equipment now require the resolved gameplay target rather than relying on bare tenant-plus-character IDs.
- Item instances have stable visible refs for management views and exact targeting, and explicitly stackable item definitions merge through holder-local stack records instead of collapsing ordinary item-instance identity.
- Container instances now carry canonical holder/location state across carried, equipped, and room-ground transitions, and container access validation follows the current reachable holder/room location rather than assuming carried-only access.
- Equipment runtime validation now supports game-authored slot definitions, item slot-group compatibility, and character body-layout slot membership while retaining a bootstrap fallback for versions with no authored equipment schema.
- Cross-game character listing and crafting-oriented domain structures exist in the current codebase.

## Current Role In The Platform

- Owns entity persistence, containment, and inventory state.
- Supplies visible room entities and gameplay-facing entity data to Game Logic and Game Session.
- Coordinates with Game Design for templates and with World Management for location context.

## Partial / Stubbed / Deferred Areas

- Cache strategy and reset-tolerant Redis behavior are defined architecturally but not fully reflected as finished runtime hardening.
- Actor-state mutation, timed expiry processing, authored stat/condition definitions, and equipment/action-state contribution wiring remain future slices.
- Some deeper cross-service and integration coverage remains lighter than the unit/service-level implementation itself.
- Broader character-ownership and publish-copy flows across services are still part of the larger app build-out.

## Planning Notes

- Use vertical-slice docs for active work.
- Keep this file limited to implemented/partial status so it stays readable.
