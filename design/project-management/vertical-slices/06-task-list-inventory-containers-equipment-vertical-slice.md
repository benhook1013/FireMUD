# Inventory, Containers, and Equipment Vertical Slice Task List

## Goal and Status

Goal: extend the current playable loop beyond `LOOK`, `SAY`, and movement so players can inspect and manipulate items through a unified item-holder model, with room-ground inventory, first-class item instances, equipment bindings, richer management queries, and later auditable transfer history. Status: partially implemented.

This slice builds on the current authenticated gameplay path, authoritative room state, and movement support. It has already delivered the first real item-interaction loop rather than remaining a purely planned item-system rewrite.

Scope note: this slice should establish the canonical container/equipment/audit model and prove the first player-facing item actions such as `INVENTORY`, `GET`, `DROP`, and one equipment action. It should not try to solve crafting, shops, banks, loot generation, or deep scripted item behaviors in the same change.

Architectural note: inventory, equipment, room-ground items, and containers should remain one shared item-holder and transfer system with different holder kinds and presentation rules, not separate gameplay subsystems. The dedicated convergence follow-up is captured in `06.4-task-list-unified-item-holder-and-transfer-model-vertical-slice.md`.

## Implementation Notes

The current branch state is materially ahead of the original `06` plan:

- `INVENTORY`, `GET`, `DROP`, `EQUIPMENT`, `WEAR`, `REMOVE`, `CONTAINER`, `PUT`, and `TAKE` all exist as live gameplay command paths;
- room-ground management is now surfaced through `INV HERE` rather than forcing room prose to carry management semantics;
- inventory, equipment, room-ground items, and container contents are now backed by persisted `item_instances`;
- stable compact visible refs such as `satchel12` are now allocated and surfaced through management views and exact-item matching;
- the gameplay command layer has its first bounded unification pass through `ItemCommandHandler`;
- the remaining work under `06` is no longer "start item interactions", but tightening the canonical holder/transfer contract, adding explicit authored stackability, and aligning audit/validation semantics.
- successful inventory, room-ground, equipment, and container-holder transfers now persist canonical `item_transfer_audits` rows for both item-instance and stack-backed movement in the same local transaction boundary as the mutation.
- replay-safe gameplay mutation requests now also thread durable `effectId` plus attested gameplay `sessionId` into those transfer-audit rows, so operator audit history can line up with the first live Entity Management replay guard and the owning player session instead of treating durability and item audit as separate tracks.
- WebSocket cross-service coverage now proves `LOGIN` / `PLAY` / `LOOK` / `INV HERE` / `GET` / `INVENTORY` / `DROP` / `INV HERE` through the live Game Session command path, including parser preservation of `INV HERE`, room-ground state changes, inventory refreshes, Entity Management request context, quantity, and nonblank durable effect ids.
- WebSocket cross-service coverage also proves a carried-item equipment loop with `EQUIPMENT` / `WEAR` / `EQUIPMENT` / `REMOVE` / `EQUIPMENT`, plus a representative `SLOT_INCOMPATIBLE` failure, including Entity Management equipment request context and nonblank durable effect ids.
- Telnet cross-service coverage now proves the same player-visible item/equipment command surface through TCP Proxy and Gateway, including `LOOK`, room-ground pickup/drop, equipment bind/unbind, incompatible equipment failure, and Entity Management request/effect-id assertions.
- Game Session now records central item-command invocation/failure metrics through `gamesession.command.item.*` with `type` and `error` tags, so operators can distinguish expected player mistakes from backend or validation failures consistently across inventory, equipment, and container verbs.
- The developer smoke guide and player playtest checklist now include the WebSocket/Telnet item-equipment extension: `INV HERE`, `GET`, `INVENTORY`, `DROP`, `EQUIPMENT`, `WEAR`, `REMOVE`, and an incompatible-equipment error check where the fixture exists.
- Game Logic now exposes the first item/container/equipment runtime RPC seam, so Game Session text handlers dispatch `INVENTORY`, `INV HERE`, `GET`, `DROP`, `CONTAINER`, `PUT`, `TAKE`, `EQUIPMENT`, `WEAR`, and `REMOVE` through Game Logic before Entity Management persistence is touched.
- Game Logic now owns player-facing room-ground and carried-item selector resolution for `GET` and `DROP`: Game Session passes the current session/game/room context plus raw item reference and quantity, Game Logic lists the appropriate holder surface, resolves names/visible refs/container refs/stack refs, and then delegates the concrete mutation to Entity Management.
- Entity Management now has the first game-configured equipment schema substrate: versioned slot definitions, slot groups, body-layout slot membership, character body-layout keys, and runtime equipment validation against those authored concepts.
- the authored stackability/fungibility follow-up is now tracked explicitly in `06.3.2-task-list-authored-stackability-and-fungibility-vertical-slice.md`.

The most important remaining design work in this slice family is:

- make the canonical transfer contract explicit;
- decide whether the direct holder fields on `item_instances` are the canonical runtime model or only an implementation step toward a more abstract holder contract;
- tighten shared transfer-audit and validation language across all holder kinds;
- finish the explicit authored stackability follow-up on top of the now-stable item-instance truth.

## First Implementation Boundary

The first narrow implementation for `06` should start from the authoritative runtime model, not from parser or transcript polish.

Recommended first order:

1. strengthen the authoritative inventory/runtime contract in `entity-management-service`
2. add room-ground storage as the first visible non-inventory location
3. expose the first player-facing command loop:
   - `INVENTORY`
   - `GET <item>`
   - `DROP <item>`
4. add presentation/help coverage for that loop
5. defer general named containers, nested containers, and full equipment/body-layout behavior until the runtime path is solid

This ordering is now historical context rather than future plan:

- room-ground storage did become the first visible transfer target/source;
- `LOOK` remains room-context output rather than a replacement for inventory/equipment queries;
- equipment and named containers are already in the live `06` command surface;
- the active follow-up work has moved from MVP verb enablement to architectural convergence and instance identity.

## 1. Design Alignment for Containment, Equipment, and Audit

- [x] Re-read the [Entity Management Service](../../architecture/microservices/entity-management-service/README.md), [Entity Management runtime/data model](../../architecture/microservices/entity-management-service/runtime-and-data.md), [World Management Service](../../architecture/microservices/world-management-service/README.md), and [Game Logic Service](../../architecture/microservices/game-logic-service/README.md) docs to confirm the ownership split for room identity, room-ground containers, item instances, hidden inventory containers, and equipment bindings.
- [x] Update the inventory- and item-related design docs so they describe one canonical target-state model:
  - character/NPC inventory is a hidden container owned by that runtime entity;
  - room-ground inventory is a room-attached container identified from authoritative room instance identity;
  - equipped items use first-class equipment bindings rather than "bag position with a flag";
  - slot definitions and body layouts are game-configured rather than platform-global enums.
- [x] Keep the design explicit that inventory, equipment, room-ground, and container contents are all holder kinds within one transfer model, not different item ontologies or unrelated command subsystems.
- [x] Decide and document the minimum player-facing protocol surface for the first inventory slice, including at least `INVENTORY`, `GET <item>`, `DROP <item>`, and one equipment action such as `WEAR` / `EQUIP` or `REMOVE`.
- [x] Explicitly document that the first MVP command loop is expected to land as:
  - `INVENTORY`
  - `GET <item>`
  - `DROP <item>`
  and that named containers plus richer equip/unequip flows may remain one bounded follow-up if they jeopardize the first transfer proof.
- [x] Document the canonical success and failure transcript shapes for both WebSocket and Telnet, including at least one successful pickup from room ground, one successful drop to room ground, one successful equipment change, and one failure such as `ERROR ITEM_NOT_FOUND` or `ERROR SLOT_INCOMPATIBLE`.
- [x] Document the canonical audit requirement for inventory/equipment mutation so future implementation treats item movement as an auditable core invariant rather than optional observability.

## 2. Entity Management Service: Runtime Containment and Query Contract

- [x] Before changing this service for the slice, run `./gradlew :entity-management-service:test` and stabilize the baseline if necessary.
- [x] Replace or extend the current weak inventory-facing contract (`QueryInventory -> item_ids[]`) with a richer inventory query shape that can return item instance metadata, container/equipped state, quantity, and game-defined type/tag information needed for gameplay and future GUIs.
- [x] Treat this authoritative runtime contract as the real starting point for `06`; do not begin by adding command text without first landing the inventory/query/transfer model that the command path will call.
- [x] Introduce or refine explicit runtime records for:
  - containers;
  - containment entries;
  - equipment bindings;
  - room-ground containers attached to `(tenantId, gameInstanceId, roomInstanceId)`.
- [x] Keep hidden/internal inventory containers implementation-owned in the first pass rather than directly player-addressable.
- [x] Ensure equipped items are not simultaneously represented as normal inventory-container members while equipped unless the design is deliberately revised and documented. The default target state is one authoritative location/binding per item instance.
- [x] Define the first mutation contract(s) needed for the slice, such as transfer item between container and room-ground container, bind item to equipment slot, and unbind item back to inventory container.
- [x] Add or refine validation rules for:
  - missing item instance;
  - inaccessible source/destination container;
  - invalid or full slot/body-layout incompatibility;
  - room/instance mismatch;
  - stack split/merge invariants if stacking is in scope for MVP.
- [x] Add unit/integration tests for hidden inventory containers, room-ground containers, equipment bindings, and basic query filtering behavior.

## 3. Entity Management Service: Inventory Transfer Audit

- [x] Introduce a canonical audit/event record for inventory and equipment mutation covering:
  - item instance id;
  - item definition/template id;
  - quantity or stack delta;
  - source container/binding;
  - destination container/binding;
  - actor/session/effect/correlation identifiers;
  - tenant/game instance/room context;
  - action reason such as `pickup`, `drop`, `put`, `take`, `equip`, `unequip`, `create`, `destroy`, `split_stack`, `merge_stack`, or `admin_grant`.
- [x] Ensure the authoritative containment mutation and audit write happen in the same local transactional boundary where feasible so the system does not acknowledge state changes without a corresponding audit trail.
- [x] Document the intended operational use of this audit trail for item-duplication investigations, suspicious transfer analysis, and later invariant checks.
- [x] Add tests for at least: successful audited transfer, failed transfer producing no committed audit row, and deterministic correlation data on retried/idempotent operations.

Current implementation note:

- the live audit record is `item_transfer_audits`;
- current callers populate verb, actor character, item/item-instance identity, quantity or stack-family delta, and source/destination holder context;
- durable gameplay item/equipment/container mutations now populate both `effectId` and attested `sessionId`, and use `effectId` as the explicit audit correlation key when present;
- the default deterministic correlation key is derived from verb, actor, item identity, quantity/family, and holder endpoints so repeated identical calls produce stable audit correlation data without pretending to offer broader replay semantics.

## 4. Game Design Service and Configurable Equipment Model

- [x] Before changing this service for the slice, run `./gradlew :game-design-service:test` and stabilize the baseline if necessary.
- [x] Define or refine the design-time model for configurable slot definitions, body layouts, or equivalent equipment schemas so different games can define species/archetype-specific attachment points.
- [x] Avoid hardcoding a universal humanoid slot enum as the authoritative model. Familiar names like `head` or `left_hand` may appear in data, but they must be game-configured concepts rather than platform truth.
- [x] Define how item templates declare equipment compatibility, such as slot groups, attachment rules, or other design-defined compatibility metadata.
- [x] Document how runtime entities resolve which slots exist for a given character/NPC/body layout and how that interacts with future species/class rules without dragging the slice into a full progression rewrite.

## 5. Game Logic Service: Item Interaction Resolution

- [x] Before changing this service for the slice, run `./gradlew :game-logic-service:test` and stabilize the baseline if necessary.
- [x] Introduce or refine gameplay-oriented RPCs for the first inventory actions, for example:
  - `QueryVisibleInventory` / `QueryContainerContents`;
  - `PickupItem`;
  - `DropItem`;
  - `EquipItem`;
  - `UnequipItem`.
- [x] Prefer landing room-ground pickup/drop orchestration before broader container semantics so the first player-visible loop is narrow and auditable.
- [x] Keep the Game Logic layer responsible for gameplay-facing validation and orchestration, while Entity Management remains authoritative for item/container/equipment persistence.
- [x] Ensure Game Logic can combine room visibility, room-ground container identity, session/character identity, and item-filter/query semantics into stable player-facing results.
- [x] Add unit tests covering successful pickup/drop/equip flows, invalid item names/selectors, incompatible slots, inaccessible containers, and backend error propagation.

## 6. Game Session Service: Text Command Wiring and UX

- [x] Before changing this service for the slice, run `./gradlew :game-session-service:test` and stabilize the baseline if necessary.
- [x] Extend the text command interpreter so the first inventory commands are authenticated gameplay commands using the same session/context guard already used by `LOOK`, `SAY`, and movement.
- [x] Add handlers for the initial item command set, mapping structured runtime results into canonical text/WebSocket responses without inventing a second competing inventory format.
- [x] Keep the first parser/handler surface intentionally narrow:
  - `INVENTORY`
  - `GET <item>`
  - `DROP <item>`
  and only add broader container/equipment verbs once the underlying runtime path is stable.
- [x] Decide and document how players refer to items in the MVP:
  - simple name matching;
  - stable item selectors;
  - ordinal disambiguation such as `GET 2.SWORD`;
  - or another explicit pattern.
- [x] Emit item-command metrics/logs with high-level error tags so operators can distinguish player mistakes (`ITEM_NOT_FOUND`, `SLOT_INCOMPATIBLE`) from backend failures.
- [x] Add unit/integration tests covering successful `INVENTORY`, `GET`, `DROP`, one equip/unequip action, unauthenticated access, and representative failure responses.

## 7. Cross-Service End-to-End Coverage

- [x] Add a WebSocket cross-service regression that performs `LOGIN` / `PLAY` / `LOOK`, picks up an item from the room-ground container, verifies `INVENTORY`, drops the item, and verifies room state again.
- [x] Add a Telnet-focused variant through TCP Proxy and Gateway covering the same path and asserting transcript parity with WebSocket up to framing differences.
- [x] Add at least one equipment-focused cross-service regression showing a successful bind to a configurable slot and a representative failure case such as incompatible slot/body-layout.
- [x] Assert the item path traverses the intended service boundary (Game Session -> Game Logic -> Entity Management, with World Management room identity where relevant) using logs, metrics, or interceptors similar to the existing LOOK/SAY/movement slices.
- [x] Ensure the regression coverage also proves the audit trail is written for successful transfer/equip operations.

## 8. Developer Workflows, Docs, and Examples

- [x] Add or update a smoke/manual verification sequence demonstrating `LOGIN` / `PLAY` / `LOOK` / `GET` / `INVENTORY` / `DROP` over WebSocket.
- [x] Add a second Telnet-oriented example with the same flow and at least one equipment action.
- [x] Update the Entity Management, Game Logic, Game Session, and Game Design docs with short implementation-status notes once the slice starts landing so readers can tell what is live versus deferred.
- [x] Update any user-journey or gameplay examples that currently imply rooms only show static descriptions; after this slice they should also reflect visible room items, carrying state, and basic equipment state where relevant.

## 9. Final QA Checklist

- [x] Run the relevant Entity Management, Game Design, Game Logic, Game Session, and cross-service test targets for the item slice and confirm they pass.
- [ ] Manually verify one happy-path pickup/drop flow and one happy-path equipment flow over both WebSocket and Telnet.
- [x] Confirm the audit trail is written for successful item/equipment mutations and that representative failure paths do not leave partially applied state.
- [x] Confirm inventory queries can distinguish carried, equipped, and room-ground items in a way that is compatible with future filtered gameplay commands and richer GUIs.

---

## Deferred Follow-Up

- Later slices can expand beyond the MVP item loop into stacking depth, nested container UX polish, crafting/material flows, banking/vendor inventories, loot generation, scripted item behavior, equipment durability, and richer client-side filtered inventory views.
- Additional follow-up slices may also introduce stronger invariant/alert tooling over the now-live inventory transfer audit trail once the canonical movement and equipment flows are stable.
