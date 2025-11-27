# Game Look & World State Vertical Slice

Context: We just finished wiring Telnet and WebSocket `LOGIN`/session behaviour end-to-end. The next small playable story worth capturing is how `LOOK` becomes data-driven (via World/Entity services) while keeping the same shared pipeline and reconnection guarantees.

## 1. World/Entity Integration for LOOK

- [ ] Expand the `LOOK` command handler (or `TextCommandInterpreter`) so it queries the World Service for the current room description and the Entity Service for nearby actors/items, merges that data into a single multi-line `OK LOOK` response, and keeps the format compatible with the lightweight smoke tests that already expect the old static message.
- [ ] Add a Spring component or client that encapsulates the REST/gRPC calls to World/Entity services, uses Micrometer timers for the remote calls, and falls back to a log-only stub implementation when `game-session.log-only=true`.
- [ ] Introduce unit tests for the new `LOOK` handler logic covering (a) success with real world/entity payloads, (b) failures from either dependency resulting in `ERROR WORLD_UNAVAILABLE`, and (c) command handling when the session is not yet authenticated.

## 2. Cross-Service PLAYABLE LOOK Scenario

- [ ] Create a cross-service integration test that starts Game Session, mock World and Entity services (or minimal stubs) along with the gateway stub and TCP Proxy Service, sends `SESSION` + `LOGIN` + `LOOK` over Telnet, and asserts that the resulting `OK LOOK` payload reflects the stubbed world/entity data while still matching the direct WebSocket flow.
- [ ] Ensure the new test records the `LOOK` command being enqueued (via the existing command service or a stub) so metrics/logging show the command traversed the shared pipeline and touched the new world/entity clients.
- [ ] Add instrumentation or log assertions verifying that stale/missing room data (e.g., when World Service returns 404) triggers the configured `ERROR WORLD_UNAVAILABLE` response without dropping the Telnet/WebSocket connection.

## 3. Developer Workflows & Documentation

- [ ] Update the World Service design doc to describe the new `/ws/game/**` look contract: what fields the service provides, the expected format of the response body, and how Game Session aggregates entity/context info before writing to the WebSocket.
- [ ] Add or extend a smoke-test script under `services/game-session-service` (or the shared workflow doc) that walks through a `LOGIN` + `LOOK` flow on a locally running Game Session connected to stubbed World/Entity services, showing how to inspect the combined response and what overrides (e.g., `WORLD_SERVICE_ENDPOINT`) are necessary.
- [ ] Refresh `design/project-management/task-list-game-session-service.md`, `task-list-world-management-service.md`, and `task-list-entity-management-service.md` so instead of duplicating the LOOK/story tasks they point to this vertical slice as the source of truth.

## 4. Optional Follow-up

- [ ] (If time permits) Follow through on more advanced reconnection behaviour by ensuring the latest `LOOK` response is cached in Redis and replayed to a reconnecting client before replaying buffered commands, so the bridge can display the room even if the first `LOOK` occurred before reconnect.

After completing these items, remember to reconcile any duplicated tasks in the service-specific lists and design docs so this vertical slice remains the canonical reference.
