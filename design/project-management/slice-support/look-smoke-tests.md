# LOOK Smoke Tests

These lightweight scripts document the manual sequence of `LOGIN` → `PLAY` → `LOOK` commands for both WebSocket and Telnet transports so developers can verify the LOOK capability alongside the automated cross-service tests and canonical smoke helpers. World supplies snapshot facts for Game Logic's typed `LookResult`; Game Session maps accepted outcomes to compact versioned `PlayerOutput` and owns final prose/rendering/delivery. The current implementation boundary is recorded in `design/project-management/implementation-tracking/player-experience-commands-and-communication.md`; the protocol contract remains canonical in `design/architecture/microservices/game-session-service/protocols.md`.

## 1. WebSocket smoke script

Dependencies: `wscat` (npm install -g wscat) or any WebSocket client.

1. Connect to the Gateway stub pointing at the Game Session service:

   ```bash
   wscat -c ws://localhost:8080/ws/game
   ```

2. Continue with the normal flow; typed attach metadata is not part of the player-facing protocol.
3. Send `LOGIN demo@example.com swordfish` and expect `OK LOGIN Logged in as demo@example.com`.
4. Send `PLAY demo` and expect `OK PLAY`.
5. Send `LOOK`. The response should match the canonical Game Session protocol and the current LOOK implementation record (`OK LOOK`, room/exit/entity lines). Save the transcript (command + response) as `look-ws-<timestamp>.log`.
6. (Optional) After the test, poll `/actuator/prometheus` or the Micrometer endpoint and confirm `gamesession.command.look.invocations` incremented once and any failure scenario incremented `gamesession.command.look.failures{error="..."}`.
7. If the room does not exist or downstream services fail, verify the response matches one of the documented error codes (`ERROR ROOM_NOT_FOUND`, `ERROR WORLD_UNAVAILABLE`, `ERROR ENTITY_UNAVAILABLE`, `ERROR LOOK_UNAVAILABLE`).

Save the full transcript (commands + responses) to a file for regression comparison.

## 2. Telnet smoke script

Prerequisites: the TCP Proxy + Gateway stack running locally (see `services/tcp-proxy-service` startup docs).

1. Use a Telnet client such as `telnet` or `nc` to connect to the proxy port:

   ```bash
   telnet localhost 4000
   ```

2. Send `LOGIN demo@example.com swordfish` and expect the same `OK LOGIN` line as the WebSocket script; this is the baseline flow and does not require a `SESSION` envelope.
3. Continue with `PLAY demo` after `LOGIN`; typed attach metadata is not part of the Telnet contract.
4. Send `LOOK` and copy the multiline response, verifying the text (room name/desc/exits/entities) matches the WebSocket transcript.
5. To test failure handling, request `LOOK` with a missing room id (by instructing Game Logic to look at a non seeded room). The proxy should relay `ERROR ROOM_NOT_FOUND` or the appropriate downstream error without dropping the connection. Include the final transcript as `look-telnet-<timestamp>.log`.

Document every command/response pair so reproducible cross-service logs can be referenced in regression notes. Treat the Game Session protocol, the LOOK implementation record, and the canonical smoke scripts under `dev-tools/` as the current source of truth rather than committed transcript artifacts.

For a non-interactive Telnet smoke check that performs `WORLDS` → `LOGIN` → `PLAY` → `LOOK` via the TCP Proxy and asserts `OK LOGIN`, `OK PLAY`, and `OK LOOK` appear in the responses, use the helper script in `services/tcp-proxy-service/telnet-login-look-smoke.sh`. This script is designed to complement the manual steps above and can be wired into CI or run locally after starting the full Telnet → Gateway → Game Session stack. The helper must wait for the canonical readiness endpoints for the path under test and fail if readiness does not converge; it should not mask startup races by timing out and continuing anyway or by re-running the full smoke until the stack eventually stabilizes.

## 3. Notes

- Store any ad hoc transcripts in your local workspace or attach them to the relevant investigation/PR notes; do not treat committed transcript artifacts as canonical repo content.
- Reference the `dev-tools/verify-fresh-bootstrap.sh`, `dev-tools/verify-restart-state.sh`, or `SMOKE_IMAGE_TAG=<tag> dev-tools/verify-smoke-images.sh` workflows when documenting current end-to-end smoke proof.
- The canonical WebSocket and Telnet smoke helpers currently reuse the same seeded demo account/runtime state and should be run sequentially unless the caller isolates account/session ids explicitly.
- When replaying the scripts, capture `gamesession.command.look.invocations`/`gamesession.command.look.failures` counters (via `/actuator/prometheus` or the Micrometer endpoint) and Game Session log/proof output to confirm the metrics/`ERROR <CODE>` mappings, accepted `PlayerOutput`, and deterministic text projection for both success and failure scenarios.
- Keep the owner-bound proof split: Game Logic logs/asserts the structured `LookResult` and typed failures, while Game Session logs/asserts `PlayerOutput` and the deterministic text projection. The current Game Logic `Rendered LOOK text` entry and `LookResultRenderer` fixtures remain local diagnostic evidence only, not player-facing renderer ownership.
- Consult [LOOK instrumentation](./look-instrumentation.md) for a deeper dive into the meters/logs that should light up during these runs and how to correlate them back to tenants, error codes, and smoke transcripts.
- Run `./gradlew crossServiceTest` to replay the automated WebSocket and Telnet LOOK transcripts, confirm the new instrumentation counters/log entries, and eliminate manual setup barriers for regression validation.
