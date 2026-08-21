# LOOK Smoke Tests

These lightweight scripts document the manual sequence of `LOGIN` → `PLAY` → `LOOK` commands for both WebSocket and Telnet transports so developers can verify the LOOK capability alongside the automated cross-service tests and the canonical service-local convenience flows. World supplies snapshot facts for Game Logic's typed `LookResult`; Game Session maps accepted outcomes to compact versioned `PlayerOutput` and owns final prose/rendering/delivery. The current implementation boundary is recorded in `design/project-management/implementation-tracking/player-experience-commands-and-communication.md`; the protocol contract remains canonical in `design/architecture/microservices/game-session-service/protocols.md`.

## Target-State Contract

Both smoke paths are manual convenience flows that fail closed on readiness, perform the required `WORLDS` discovery when applicable, then execute `LOGIN` → `PLAY` → `LOOK` and compare the player-visible result with the canonical transcript. WebSocket coverage includes controlled `ROOM_NOT_FOUND` continuity; Telnet coverage preserves transcript parity and adds the isolated failure case when implemented. Readiness requires exact 2xx and top-level `status=UP`; the TCP Proxy path additionally requires ready listener and downstream traffic-admission fields. These flows supplement cross-service proof; optional diagnostics never replace the client-visible assertions.

## Implementation Status

The WebSocket and Telnet helper executables are the canonical service-local convenience flows and reuse the shared `dev-tools/smoke` library. They are not canonical CI/runtime proof until the exact-2xx/top-level-JSON readiness predicate is implemented. Current implementation caveat: `dev-tools/smoke/smoke_common.py` accepts readiness when HTTP status is below 500 and a whitespace-normalized `"status":"UP"` substring appears anywhere in the body, including nested JSON; it retries readiness only, not the complete `WORLDS` → `LOGIN` → `PLAY` → `LOOK` flow. Therefore these helpers are not proof of the canonical exact-2xx/top-level-JSON readiness predicate or of the full flow until that implementation gap is fixed. Each helper must wait for the canonical readiness endpoints for the path under test and fail if readiness does not converge; it must not mask startup races by timing out and continuing or by rerunning the full smoke until the stack eventually stabilizes. The shared fail-closed readiness semantics are documented in [Deployment Environments](../../architecture/infrastructure/deployment-environments.md#docker-health-checks) and [TCP Proxy readiness](../../architecture/microservices/tcp-proxy-service/README.md#readiness-and-liveness).

Prerequisites: Bash, `curl`, `python3`, and a WebSocket client (`wscat` or equivalent). The Telnet path additionally requires `telnet` or `nc`.

## 1. WebSocket smoke script

Dependencies: Bash and `curl` (global prerequisites above), `wscat` (npm install -g wscat) or any WebSocket client, plus `python3` for readiness-response JSON parsing.

Before opening the socket or sending `LOGIN`, run the canonical readiness gates for the local Gateway flow: Account, Game Logic, Game Session, and Gateway. Each endpoint must return HTTP success with a JSON health body containing `"status":"UP"`; any timeout, transport error, non-success response, or other status is a hard failure and the manual flow must stop (adjust ports only when the local stack overrides them):

```bash
for endpoint in \
  http://localhost:8081/actuator/health/readiness \
  http://localhost:8085/actuator/health/readiness \
  http://localhost:8086/actuator/health/readiness \
  http://localhost:8080/actuator/health/readiness; do
  response=$(curl --silent --show-error --max-time 10 --write-out $'\n%{http_code}' "$endpoint") || {
    echo "Readiness failed: $endpoint" >&2
    exit 1
  }
  http_code=${response##*$'\n'}
  body=${response%$'\n'*}
  if [[ "$http_code" -lt 200 || "$http_code" -ge 300 ]]; then
    echo "Readiness failed: $endpoint (HTTP $http_code)" >&2
    exit 1
  fi
  if ! python3 -c '
import json
import sys

try:
    payload = json.load(sys.stdin)
except json.JSONDecodeError as exc:
    print(f"Readiness returned invalid JSON: {exc}", file=sys.stderr)
    raise SystemExit(1)

if not isinstance(payload, dict) or payload.get("status") != "UP":
    print("Readiness JSON top-level status is not UP", file=sys.stderr)
    raise SystemExit(1)
' <<<"$body"; then
    echo "Readiness did not report top-level status UP: $endpoint" >&2
    exit 1
  fi
done
```

Use the service-local WebSocket convenience executable (`services/game-session-service/websocket-login-look-smoke.sh`) as the non-interactive local alternative; the Implementation Status above applies.

1. Connect to the Gateway stub pointing at the Game Session service:

   ```bash
   wscat -c ws://localhost:8080/ws/game
   ```

2. Continue with the normal flow; typed attach metadata is not part of the player-facing protocol.
3. Send `LOGIN demo@example.com swordfish` and expect `OK LOGIN Logged in as demo@example.com`.
4. Send `PLAY demo` and expect `OK PLAY`.
5. Send `LOOK`. The response should match the canonical Game Session protocol and the current LOOK implementation record (`OK LOOK`, room/exit/entity lines). Save the transcript (command + response) as `look-ws-<timestamp>.log`.
6. (Optional) After the test, poll `/actuator/prometheus` or the Micrometer endpoint and confirm `gamesession.command.look.invocations` incremented once and any failure scenario incremented `gamesession.command.look.failures{error="..."}`.
7. Do not treat this manual path as reproducible failure proof. The controlled WebSocket World-stub `ROOM_NOT_FOUND` case is defined in the [WebSocket cross-service test](./look-cross-service-tests.md#websocket-test); `WORLD_UNAVAILABLE` and `ENTITY_UNAVAILABLE` remain unit/target mappings, and the Telnet missing-room case is deferred to the [Telnet cross-service test](./look-cross-service-tests.md#telnet-test).

Save the full transcript (commands + responses) to a file for regression comparison.

## 2. Telnet smoke script

Prerequisites: the TCP Proxy + Gateway stack running locally (see `services/tcp-proxy-service` startup docs).

Before opening the Telnet socket, run the shared fail-closed readiness gate from [Deployment Environments](../../architecture/infrastructure/deployment-environments.md#docker-health-checks) and [TCP Proxy readiness](../../architecture/microservices/tcp-proxy-service/README.md#readiness-and-liveness). For the local Compose ports, check Account (`8081`), Game Logic (`8085`), Game Session (`8086`), Gateway (`8080`), and TCP Proxy (`8089`) `/actuator/health/readiness` endpoints. Require an exact 2xx response with a top-level `"status":"UP"`; any timeout, transport error, non-success response, or other status is a hard failure. TCP Proxy must additionally report explicit ready fields for both the Telnet listener and downstream `gatewayGameplayPath` traffic admission; fail closed if either is absent, malformed, or not ready, and wait to open the client connection until both are ready.

1. Use a Telnet client such as `telnet` or `nc` to connect to the proxy port:

   ```bash
   telnet localhost 4000
   ```

2. For a fresh direct-text flow, issue `WORLDS` and wait for its successful discovery response before `LOGIN`; local runs with a preselected target may intentionally skip discovery. Send `LOGIN demo@example.com swordfish` and expect the same `OK LOGIN` line as the WebSocket script; this is the baseline flow and does not require a `SESSION` envelope.
3. Continue with `PLAY demo` after `LOGIN`; typed attach metadata is not part of the Telnet contract.
4. Send `LOOK` and copy the multiline response, verifying the text (room name/desc/exits/entities) matches the WebSocket transcript.
5. Telnet missing-room proof is not currently implemented. Keep this failure scenario deferred to a future isolated controlled World-stub case, as described in the [Telnet cross-service test](./look-cross-service-tests.md#telnet-test); when added, assert that the proxy relays the exact `ERROR ROOM_NOT_FOUND`, keeps the connection open, and retains the final transcript as `look-telnet-<timestamp>.log`.

Document every command/response pair so reproducible cross-service logs can be referenced in regression notes. Treat the Game Session protocol, the LOOK implementation record, and the named service-local convenience flows as local smoke references rather than committed transcript artifacts; the Implementation Status above applies. The service-local executables reuse the shared `dev-tools/smoke` library; hosted runs may use `dev-tools/hosted/shared/hosted-login-look-smoke.sh` as their wrapper.

For a non-interactive Telnet smoke check that performs `WORLDS` → `LOGIN` → `PLAY` → `LOOK` via the TCP Proxy and asserts `OK LOGIN`, `OK PLAY`, and `OK LOOK` appear in the responses, use the service-local Telnet convenience executable `services/tcp-proxy-service/telnet-login-look-smoke.sh`. It reuses the shared `dev-tools/smoke` library and is designed to complement the manual steps above; the Implementation Status above applies. Run it after starting the full Telnet → Gateway → Game Session stack.

## 3. Notes

- Store any ad hoc transcripts in your local workspace or attach them to the relevant investigation/PR notes; do not treat committed transcript artifacts as canonical repo content.
- Reference the `dev-tools/verify-fresh-bootstrap.sh`, `dev-tools/verify-restart-state.sh`, or `SMOKE_IMAGE_TAG=<tag> dev-tools/verify-smoke-images.sh` workflows when documenting current end-to-end smoke proof.
- The service-local WebSocket and Telnet convenience helpers currently reuse the same seeded demo account/runtime state and should be run sequentially unless the caller isolates account/session ids explicitly.
- When replaying the scripts, capture `gamesession.command.look.invocations`/`gamesession.command.look.failures` counters (via `/actuator/prometheus` or the Micrometer endpoint) and optional Game Session/Game Logic diagnostics for the `ERROR <CODE>` mappings, `PlayerOutput`, and deterministic text projection. These diagnostics supplement the player-visible transcript and are not automated cross-service proof.
- Keep the owner-bound diagnostic split: Game Logic may log the structured `LookResult` and typed failures, while Game Session owns the player-facing `PlayerOutput` and deterministic text projection. The current Game Logic `Rendered LOOK text` entry and `LookResultRenderer` fixtures remain local diagnostic evidence only.
- Consult [LOOK instrumentation](./look-instrumentation.md) for a deeper dive into the meters/logs that should light up during these runs and how to correlate them back to tenants, error codes, and smoke transcripts.
- Run `./gradlew crossServiceTest` to replay the automated WebSocket and Telnet LOOK transcripts and confirm the player-visible success/error responses and transport-continuity assertions; instrumentation counters/logs remain supplementary diagnostics.
