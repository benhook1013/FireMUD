# LOOK Smoke Tests

These lightweight scripts document the manual sequence of `LOGIN` + `LOOK` commands for both WebSocket and Telnet transports so developers can verify the vertical slice while the wired cross-service tests are still in progress.

## 1. WebSocket smoke script

Dependencies: `wscat` (npm install -g wscat) or any WebSocket client.

1. Connect to the Gateway stub pointing at the Game Session service:

   ```bash
   wscat -c ws://localhost:8080/ws/game
   ```

2. (Optional) Send `SESSION <sessionId> <tenantId>` if you are explicitly testing the attach-to-existing-session path using a session created via the REST `POST /sessions` endpoint. For normal WebSocket smoke runs, skip this step and rely on `LOGIN` to create or bind the session.
3. Send `LOGIN demo@example.com swordfish` and expect `OK LOGIN Logged in as demo@example.com`.
4. Send `LOOK`. The response should match the canonical transcript in `design/project-management/vertical-slices/03-task-list-data-driven-look-vertical-slice.md#1-protocol-ux-and-design-alignment-for-look` (`OK LOOK`, room/exit/entity lines). Save the transcript (command + response) as `look-ws-<timestamp>.log`.
5. (Optional) After the test, poll `/actuator/prometheus` or the Micrometer endpoint and confirm `gamesession.command.look.invocations{tenantId="1"}` incremented once and any failure scenario incremented `gamesession.command.look.failures{error="..."}`.
6. If the room does not exist or downstream services fail, verify the response matches one of the documented error codes (`ERROR ROOM_NOT_FOUND`, `ERROR WORLD_UNAVAILABLE`, `ERROR ENTITY_UNAVAILABLE`, `ERROR LOOK_UNAVAILABLE`).

Save the full transcript (commands + responses) to a file for regression comparison.

## 2. Telnet smoke script

Prerequisites: the TCP Proxy + Gateway stack running locally (see `services/tcp-proxy-service` startup docs).

1. Use a Telnet client such as `telnet` or `nc` to connect to the proxy port:

   ```bash
   telnet localhost 4000
   ```

2. Send `LOGIN demo@example.com swordfish` and expect the same `OK LOGIN` line as the WebSocket script; this is the baseline flow and does not require a `SESSION` envelope.
3. (Optional) To test the attach-to-existing-session behavior, first create a session via the REST `POST /sessions` endpoint, then send `SESSION <sessionId> <tenantId>` followed by `LOGIN demo@example.com swordfish` to bind the Telnet connection to that session.
4. Send `LOOK` and copy the multiline response, verifying the text (room name/desc/exits/entities) matches the WebSocket transcript.
5. To test failure handling, request `LOOK` with a missing room id (by instructing Game Logic to look at a non seeded room). The proxy should relay `ERROR ROOM_NOT_FOUND` or the appropriate downstream error without dropping the connection. Include the final transcript as `look-telnet-<timestamp>.log`.

Document every command/response pair so reproducible cross-service logs can be referenced in regression notes. The sample transcripts under `design/project-management/smoke-tests/look/` (`look-ws-sample.log`, `look-telnet-sample.log`) show the expected formatting for happy-path runs.

For a non-interactive Telnet smoke check that performs `LOGIN` + `LOOK` via the TCP Proxy and asserts `OK LOGIN` / `OK LOOK` appear in the responses, use the helper script in `services/tcp-proxy-service/telnet-login-look-smoke.sh`. This script is designed to complement the manual steps above and can be wired into CI or run locally after starting the full Telnet → Gateway → Game Session stack. The helper must wait for the canonical readiness endpoints for the path under test and fail if readiness does not converge; it should not mask startup races by timing out and continuing anyway or by re-running the full smoke until the stack eventually stabilizes.

## 3. Notes

- Store the transcripts under `design/project-management/smoke-tests/look/` with filenames describing the transport and timestamp.
- Reference these scripts in the README/CI docs once the full automated cross-service tests exist.
- When replaying the scripts, capture `gamesession.command.look.invocations`/`gamesession.command.look.failures` counters (via `/actuator/prometheus` or the Micrometer endpoint) and log output from Game Session to confirm the metrics/`ERROR <CODE>` mappings fire for both success and failure scenarios.
- Keep an eye on Game Logic logs for the `Rendered LOOK text` entry emitted by `LookResultRenderer` so you can correlate the structured DTO with the textual transcript when diagnosing discrepancies.
- Consult `design/project-management/slice-support/look-instrumentation.md` for a deeper dive into the meters/logs that should light up during these runs and how to correlate them back to tenants, error codes, and smoke transcripts.
- Run `./gradlew crossServiceTest` to replay the automated WebSocket and Telnet LOOK transcripts, confirm the new instrumentation counters/log entries, and eliminate manual setup barriers for regression validation.
