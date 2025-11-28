# Smoke Tests for Login + LOOK

These steps exercise the same `LOGIN` + `LOOK` flow that users take over both WebSocket (direct Game Session) and Telnet (via TCP Proxy + Gateway) transports. They deliberately use the `demo@example.com` / `swordfish` credentials that exist in the lightweight Account Service stub.

## Requirements

1. Account Service stub or real credential provider must be running (`grpcurl` prefix `account-service:6565` by default).
2. Game Session Service and Spring Cloud Gateway must be running with the same tenant (`tenantId=1`), or use `GATEWAY_WS_URL` / `ACCOUNT_SERVICE_ENDPOINT` overrides to target your locally running instances.

## 1. Direct WebSocket Smoke Flow

Use `websocat` (or your favorite WebSocket client) to connect directly to Game Session:

```bash
websocat -H "X-Session-Id: 1" -H "X-Tenant-Id: 1" ws://localhost:8080/ws/game
LOGIN demo@example.com swordfish
LOOK
```

Expected output (two newline-separated responses):

```
OK LOGIN Logged in as demo@example.com

OK LOOK
You are in a candle-lit antechamber carved into basalt.

```

Capture both responses so you can compare them to the Telnet flow.

## 2. Telnet Smoke Flow via TCP Proxy + Gateway

Open a Telnet session directly against the TCP Proxy (default port `2323`):

```bash
telnet localhost 2323
SESSION 1 1
LOGIN demo@example.com swordfish
LOOK
```

Telnet should display the redacted login acknowledgement followed by the same `LOOK` payload:

```
OK LOGIN Logged in as demo@example.com

OK LOOK
You are in a candle-lit antechamber carved into basalt.

```

## 3. Verifying the Same Experience

Compare the WebSocket `LOOK` response and the Telnet `LOOK` response; they should match exactly because both commands traverse `/ws/game/**` and are handled by the same Game Session login/tick pipeline. Recording the two output blocks above and diffing them is enough to prove parity. Document any differences (for example, missing blank lines) as regressions in `design/project-management/vertical-slices/02-task-list-login-and-session-vertical-slice.md`.
