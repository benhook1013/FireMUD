# Smoke Tests for Login + LOOK

These steps exercise the same `LOGIN` + `LOOK` flow that users take over both WebSocket (direct Game Session) and Telnet (via TCP Proxy + Gateway) transports. They deliberately use the `demo@example.com` / `swordfish` credentials that exist in the lightweight Account Service stub.

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
LOGIN demo@example.com swordfish
LOOK
```

Replace the `X-Game-Instance-Id` and `X-Tenant-Id` header values with the game instance and tenant identifiers for your environment. `X-Session-Id` is a deprecated alias and should not be used in new tooling.

Expected output (two newline-separated responses):

```text
OK LOGIN Logged in as demo@example.com

OK LOOK
You are in a candle-lit antechamber carved into basalt.

```

Capture both responses so you can compare them to the Telnet flow.

For Compose-backed blackbox verification, the canonical script is:

```bash
cd services/game-session-service
bash ./websocket-login-look-smoke.sh
```

## 2. Telnet Smoke Flow via TCP Proxy + Gateway (LOGIN-only baseline)

This flow exercises the **baseline Telnet behaviour** where clients do **not**
send a `SESSION` envelope. It matches what a normal Telnet client would do in
the wild: connect, `LOGIN`, then issue gameplay commands.

Open a Telnet session directly against the TCP Proxy (default port `2323`):

```bash
telnet localhost 2323
LOGIN demo@example.com swordfish
LOOK
```

Telnet should display the redacted login acknowledgement followed by the same `LOOK` payload:

```text
OK LOGIN Logged in as demo@example.com

OK LOOK
You are in a candle-lit antechamber carved into basalt.

```

## 3. Telnet Smoke Flow via TCP Proxy + Gateway (advanced SESSION attach)

This flow demonstrates the **optional** `SESSION` envelope used by advanced
tools and scripts that already know the `sessionId`/`tenantId` pair (for
example after calling the Game Session REST API). It is an optimization for
attach-to-session scenarios; Telnet clients never need to send `SESSION` for
normal gameplay.

Open a Telnet session directly against the TCP Proxy (default port `2323`):

```bash
telnet localhost 2323
SESSION 00000000-0000-0000-0000-000000000001 00000000-0000-0000-0000-000000000001
LOGIN demo@example.com swordfish
LOOK
```

Replace the `SESSION` envelope identifiers with a real `{sessionId, tenantId}` pair for your environment.

Telnet should display the redacted login acknowledgement followed by the same `LOOK` payload:

```text
OK LOGIN Logged in as demo@example.com

OK LOOK
You are in a candle-lit antechamber carved into basalt.

```

## 4. Verifying the Same Experience

Compare the WebSocket `LOOK` response and each Telnet `LOOK` response; they
should match exactly because both commands traverse `/ws/game/**` and are
handled by the same Game Session login/tick pipeline. Recording the three
output blocks above and diffing them is enough to prove parity. Document any
differences (for example, missing blank lines) as regressions in
`design/project-management/vertical-slices/02-task-list-login-and-session-vertical-slice.md`.

If any readiness endpoint for the target path is still not `UP`, do not treat retries or waiting inside the client flow as a valid substitute. The stack is not yet ready for player traffic.

For the Telnet path specifically, the smoke should verify both sides of the contract:

- before readiness: player traffic is refused or explicitly rejected with `startup_unavailable`
- after readiness: first-attempt `LOGIN` and first `LOOK` succeed without retries

For the direct WebSocket path in this slice, the smoke verifies post-readiness parity only:

- after readiness: first-attempt `LOGIN` and first `LOOK` succeed without retries
- the returned `LOOK` transcript stays aligned with the Telnet path for the same game instance
