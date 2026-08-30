# Smoke Tests for Login + PLAY + LOOK

These steps exercise the same `WORLDS` + `LOGIN` + `PLAY` + `LOOK` flow emitted by the canonical scripts over both WebSocket (direct Game Session) and Telnet (via TCP Proxy + Gateway) transports. The optional item/container/equipment extension can exercise the first player-visible inventory loop over the same command surface when the validated mutation boundary is available. These examples deliberately use the `demo@example.com` / `swordfish` credentials that the Compose-backed smoke stack seeds explicitly for local verification.

The default smoke is the read-only `LOGIN` -> `PLAY` -> `LOOK` baseline. The item/container/equipment sequence is a mutating extension and is not part of the default two-transport wrappers. A standalone transport client may run that extension only inside an explicitly validated run-owned Compose project:

```bash
export FIREMUD_SMOKE_RUN_ID="local-$(date +%s)-$$"
export COMPOSE_PROJECT_NAME="firemud-smoke-${FIREMUD_SMOKE_RUN_ID}"
export FIREMUD_SMOKE_OWNERSHIP_TOKEN="$(openssl rand -hex 32)"
bash dev-tools/verify-fresh-bootstrap.sh
SMOKE_MUTATION_EXTENSION=true \
SMOKE_MUTATION_BOUNDARY=run-owned-compose \
bash services/game-session-service/websocket-login-look-smoke.sh
```

The fresh-bootstrap step establishes the claim and running stack; the standalone client reuses the exact same run ID, project name, and ownership token. The explicit ID-to-project binding and capability are defined by [Testing: player-flow smoke and reset boundaries](../architecture/system-architecture-testing.md#player-flow-smoke-and-reset-boundaries). Persistent/shared mutation remains unavailable until the restricted-synthetic identity, playable-state namespace, and fence verifier exists. Do not use the fresh-bootstrap or image-tag two-transport wrappers for mutating parity: they run baseline-only and reject the mutation extension until independent transport identities/state are proven.

## Requirements

1. Account Service stub or real credential provider must be running (`grpcurl` prefix `account-service:6565` by default).
2. For the Telnet-via-Gateway path, Game Session Service, Spring Cloud Gateway, and TCP Proxy must be running with the same tenant, or use the documented `GATEWAY_WS_URL` / `ACCOUNT_SERVICE_ENDPOINT` overrides to target your locally running instances. For the direct backend WebSocket path, Gateway and TCP Proxy are not prerequisites: Account Service, Game Logic Service, and Game Session Service are sufficient. The current direct smoke requires positive numeric tenant and game-instance identifiers; a separate positive session identifier is not required because the direct listener derives its transport session ID from an optional `X-Firemud-Transport-Session-Id` header and otherwise uses `X-Game-Instance-Id`. UUID identifiers are target-state only until the current backend wire parsers are migrated.
3. Before running the flow, wait for the canonical readiness endpoints of the path you are exercising. For the Telnet path, that means Account Service, Game Logic Service, Game Session Service, Spring Cloud Gateway, and TCP Proxy must all report `UP` from `/actuator/health/readiness`.
   For the direct WebSocket path, that means Account Service, Game Logic Service, and Game Session Service must all report `UP` from `/actuator/health/readiness`.
4. For the Compose-backed Telnet smoke, assert the pre-readiness admission behavior before waiting for readiness convergence: while TCP Proxy readiness is still false, new Telnet sockets must either be refused before the listener binds or receive the explicit `DISCONNECT startup_unavailable ...` response. Do not accept silent connection success during this window.
   Do not require an equivalent pre-readiness startup refusal assertion for direct Game Session WebSocket access in this slice: Telnet is the external player admission boundary, while direct WebSocket smoke is a parity and backend-path check for the currently exposed developer/test surface.

## 1. Direct WebSocket Smoke Flow (non-production backend path)

The direct Game Session WebSocket listener is a deliberate backend developer/test surface, not the production Gateway flow. It must remain non-production, network-isolated, and non-player-routable; it must not be published through Gateway or any public ingress. Direct access does not waive authentication or authorization: this smoke exercises Game Session's normal Account-backed credential `LOGIN` path, and its local reachability is not an auth exception or evidence that production clients may bypass Gateway admission. The direct path intentionally does not obtain a gameplay connect token because doing so would test the Gateway flow instead of this backend surface.

Use `websocat` (or your favorite WebSocket client) to connect directly to Game Session:

```bash
export FIREMUD_TRANSPORT_SESSION_ID="smoke-$(date +%s%N)-$$"
websocat -H "X-Game-Instance-Id: 1" -H "X-Tenant-Id: 1" -H "X-Firemud-Transport-Session-Id: ${FIREMUD_TRANSPORT_SESSION_ID}" ws://localhost:8086/ws/game
WORLDS
LOGIN demo@example.com swordfish
PLAY demo
LOOK
```

Replace the `X-Game-Instance-Id` and `X-Tenant-Id` header values with the positive numeric game-instance and tenant identifiers for your current environment. Generate a fresh `X-Firemud-Transport-Session-Id` for every run (as above), including concurrent runs; do not reuse it across clients. The direct listener uses `X-Game-Instance-Id` as the bootstrap game-instance and fallback transport-session identity; `X-Firemud-Transport-Session-Id` is preferred when testing an explicit transport session. UUID wire identifiers shown in target-state architecture documents are not accepted by this current direct listener yet.

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

Optional mutating item/container/equipment extension, only when the run-owned isolation boundary above is satisfied and the target environment has the required fixtures:

```text
INV HERE
GET Torch
INVENTORY
CONTAINER Backpack
PUT Torch INTO Backpack
TAKE Torch FROM Backpack
DROP Torch
INV HERE
EQUIPMENT
WEAR Leather Cap
EQUIPMENT
REMOVE HEAD
WEAR Iron Boots
```

Expected semantic checks:

- `INV HERE` shows room-ground `Torch` and `Backpack` fixtures.
- `GET Torch` reports `You pick up Torch.` and the refreshed `INVENTORY` shows the torch as carried.
- `CONTAINER Backpack` shows the seeded ration inside the nearby room-ground backpack.
- `PUT Torch INTO Backpack` reports success and refreshes the container view with the torch now inside.
- `TAKE Torch FROM Backpack` reports success and refreshes the container view with the torch removed again.
- `DROP Torch` reports `You drop Torch.` and the next `INV HERE` shows the torch back on the room ground.
- `WEAR Leather Cap` reports success, `EQUIPMENT` shows `HEAD: Leather Cap`, and `REMOVE HEAD` reports success.
- `WEAR Iron Boots` returns a clear `SLOT_INCOMPATIBLE` error in environments that carry the demo incompatible-item fixture.

The plain `bash ./websocket-login-look-smoke.sh` invocation is baseline-only. The supported scripted WebSocket mutation invocation documented here is the top-of-page run-owned Compose command block, which reuses the previously established exact claim with `SMOKE_MUTATION_EXTENSION=true` and `SMOKE_MUTATION_BOUNDARY=run-owned-compose`; do not invoke it against shared or stable state.

For Compose-backed blackbox verification, the current canonical script is:

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

Run the optional item/container/equipment extension from the WebSocket section only as a separately isolated Telnet leg with the same validated run-owned boundary. The Telnet transcript should be semantically identical to the WebSocket transcript apart from framing/prompt differences; the two-transport wrappers do not currently prove mutating parity because they intentionally reject shared-state mutation.

## 3. Verifying the Same Experience

Compare the WebSocket `PLAY` + `LOOK` response and the Telnet `PLAY` + `LOOK` response after normalizing each command response: remove transport framing, prompts, and other explicitly allowed presentation differences, then compare the semantic response fields and command outcomes. Raw transcript diffing alone is not sufficient to prove parity. Document substantive differences (for example, a different room payload, state outcome, or error) as regressions in [Player Experience, Commands, and Communication](../project-management/implementation-tracking/player-experience-commands-and-communication.md); harmless whitespace or framing differences remain allowed only when they are normalized explicitly.

When separately running the optional item/container/equipment extension with independent identities and isolated state, compare the `INV HERE`, `GET`, `INVENTORY`, `CONTAINER`, `PUT`, `TAKE`, `DROP`, `EQUIPMENT`, `WEAR`, and `REMOVE` results across WebSocket and Telnet. Until that isolation exists, this is an open parity proof rather than a current two-transport smoke claim. Differences in transport prompts are acceptable; differences in item state, container state, equipment state, or error codes are regressions in [Gameplay Rules, Entities, and Effects](../project-management/implementation-tracking/gameplay-rules-entities-and-effects.md).

If any readiness endpoint for the target path is still not `UP`, do not treat retries or waiting inside the client flow as a valid substitute. The stack is not yet ready for player traffic.

For the Telnet path specifically, the smoke should verify both sides of the contract:

- before readiness: player traffic is refused or explicitly rejected with `startup_unavailable`
- after readiness: first-attempt `LOGIN`, `PLAY`, and `LOOK` succeed without retries

For the direct WebSocket path in this slice, the smoke verifies post-readiness parity only:

- after readiness: first-attempt `LOGIN`, `PLAY`, and `LOOK` succeed without retries
- normalized `PLAY` + `LOOK` response fields and outcomes stay aligned with the Telnet path for the same game instance after allowed framing/presentation differences are removed
- the blackbox target is the direct Game Session WebSocket surface rather than Spring Cloud Gateway, so this smoke verifies backend gameplay-path parity rather than edge admission behavior
