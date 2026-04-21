# Smoke Tests for Login + PLAY + LOOK

These steps exercise the same `WORLDS` (optional) + `LOGIN` + `PLAY` + `LOOK` flow that users take over both WebSocket (direct Game Session) and Telnet (via TCP Proxy + Gateway) transports. The optional item/equipment extension then proves the first player-visible inventory loop over the same command surface. These examples deliberately use the `demo@example.com` / `swordfish` credentials that exist in the lightweight Account Service stub.

## Requirements

1. Account Service stub or real credential provider must be running (`grpcurl` prefix `account-service:6565` by default).
2. Game Session Service and Spring Cloud Gateway must be running with the same tenant, or use `GATEWAY_WS_URL` / `ACCOUNT_SERVICE_ENDPOINT` overrides to target your locally running instances. Use the tenant and session identifiers for your environment (in the target-state design these are UUIDs).
3. Before running the flow, wait for the canonical readiness endpoints of the path you are exercising. For the Telnet path, that means Account Service, Game Session Service, Spring Cloud Gateway, and TCP Proxy must all report `UP` from `/actuator/health/readiness`.
   For the direct WebSocket path, that means Account Service, Game Logic Service, and Game Session Service must all report `UP` from `/actuator/health/readiness`.
4. For the Compose-backed Telnet smoke, assert the pre-readiness admission behavior before waiting for readiness convergence: while TCP Proxy readiness is still false, new Telnet sockets must either be refused before the listener binds or receive the explicit `DISCONNECT startup_unavailable ...` response. Do not accept silent connection success during this window.
   Do not require an equivalent pre-readiness startup refusal assertion for direct Game Session WebSocket access in this slice: Telnet is the external player admission boundary, while direct WebSocket smoke is a parity and backend-path check for the currently exposed developer/test surface.

## 1. Direct WebSocket Smoke Flow

Use `websocat` (or your favorite WebSocket client) to connect directly to Game Session:

```bash
websocat -H "X-Game-Instance-Id: 00000000-0000-0000-0000-000000000001" -H "X-Tenant-Id: 00000000-0000-0000-0000-000000000001" ws://localhost:8080/ws/game
WORLDS
LOGIN demo@example.com swordfish
PLAY demo
LOOK
```

Replace the `X-Game-Instance-Id` and `X-Tenant-Id` header values with the game instance and tenant identifiers for your environment.

Expected output (four newline-separated responses):

```text
OK WORLDS
1) Demo World (demo)
2) Builder Sandbox (sandbox)

OK LOGIN Logged in as demo@example.com

OK PLAY Entered world: demo

OK LOOK
You are in a candle-lit antechamber carved into basalt.

```

Capture both responses so you can compare them to the Telnet flow.

Optional item/equipment extension, when the target environment has the demo item fixtures loaded:

```text
INV HERE
GET Torch
INVENTORY
DROP Torch
INV HERE
EQUIPMENT
WEAR Leather Cap
EQUIPMENT
REMOVE HEAD
WEAR Iron Boots
```

Expected semantic checks:

- `INV HERE` shows a room-ground `Torch`.
- `GET Torch` reports `You pick up Torch.` and the refreshed `INVENTORY` shows the torch as carried.
- `DROP Torch` reports `You drop Torch.` and the next `INV HERE` shows the torch back on the room ground.
- `WEAR Leather Cap` reports success, `EQUIPMENT` shows `HEAD: Leather Cap`, and `REMOVE HEAD` reports success.
- `WEAR Iron Boots` returns a clear `SLOT_INCOMPATIBLE` error in environments that carry the demo incompatible-item fixture.

For Compose-backed blackbox verification, the canonical script is:

```bash
cd services/game-session-service
bash ./websocket-login-look-smoke.sh
```

This direct WebSocket smoke uses the Game Session HTTP/WebSocket listener directly (`ws://localhost:8086/ws/game` by default), not the Gateway route. It also requires Python plus the `websocket-client` package because the canonical script is implemented as a small Python client rather than `websocat`.

## 2. Telnet Smoke Flow via TCP Proxy + Gateway

This flow exercises the baseline Telnet behaviour for real players: connect, optionally browse `WORLDS`, then `LOGIN`, `PLAY`, and issue gameplay commands. Hidden bootstrap metadata is proxy-internal; players do not type a `SESSION` envelope.

Open a Telnet session directly against the TCP Proxy (default port `2323`):

```bash
telnet localhost 2323
WORLDS
LOGIN demo@example.com swordfish
PLAY demo
LOOK
```

Telnet should display the world list, login acknowledgement, gameplay-entry acknowledgement, and then the same `LOOK` payload:

```text
OK WORLDS
1) Demo World (demo)
2) Builder Sandbox (sandbox)

OK LOGIN Logged in as demo@example.com

OK PLAY Entered world: demo

OK LOOK
You are in a candle-lit antechamber carved into basalt.

```

Run the same optional item/equipment extension from the WebSocket section if the environment has the demo item fixtures loaded. The Telnet transcript should be semantically identical to the WebSocket transcript apart from framing/prompt differences.

## 3. Verifying the Same Experience

Compare the WebSocket `PLAY` + `LOOK` response and the Telnet `PLAY` + `LOOK` response; they should match semantically because both commands traverse `/ws/game/**` and are handled by the same Game Session admission and gameplay pipeline. Recording the output blocks above and diffing them is enough to prove parity. Document any differences (for example, missing blank lines) as regressions in [02.2-task-list-gameplay-admission-ux-vertical-slice.md](../project-management/vertical-slices/02.2-task-list-gameplay-admission-ux-vertical-slice.md).

When running the optional item/equipment extension, compare the `INV HERE`, `GET`, `INVENTORY`, `DROP`, `EQUIPMENT`, `WEAR`, and `REMOVE` results across WebSocket and Telnet as the same parity proof. Differences in transport prompts are acceptable; differences in item state, equipment state, or error codes are regressions in [06-task-list-inventory-containers-equipment-vertical-slice.md](../project-management/vertical-slices/06-task-list-inventory-containers-equipment-vertical-slice.md).

If any readiness endpoint for the target path is still not `UP`, do not treat retries or waiting inside the client flow as a valid substitute. The stack is not yet ready for player traffic.

For the Telnet path specifically, the smoke should verify both sides of the contract:

- before readiness: player traffic is refused or explicitly rejected with `startup_unavailable`
- after readiness: first-attempt `LOGIN`, `PLAY`, and `LOOK` succeed without retries

For the direct WebSocket path in this slice, the smoke verifies post-readiness parity only:

- after readiness: first-attempt `LOGIN`, `PLAY`, and `LOOK` succeed without retries
- the returned `PLAY` + `LOOK` transcript stays aligned with the Telnet path for the same game instance
- the blackbox target is the direct Game Session WebSocket surface rather than Spring Cloud Gateway, so this smoke verifies backend gameplay-path parity rather than edge admission behavior
