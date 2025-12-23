# TCP Proxy Service Doc Fixes (Temp)

This file tracks the agreed documentation fixes from an AI-assisted review. It is documentation-only and may be deleted once the tasks are merged into the canonical tracking doc (`design/project-management/todo-tcp-proxy-service-review.md`).

## Decisions (Agreed)

- **MCP termination**: TCP Proxy Service is transport-only; it negotiates MCP and forwards MCP control lines as sanitized text over the WebSocket bridge. MCP package semantics live on the backend gameplay route (via Spring Cloud Gateway → Game Session Service).
- **Gateway WebSocket bridge downtime**: fail-fast. If the TCP Proxy Service cannot establish/maintain its WebSocket bridge to Spring Cloud Gateway beyond a short reconnect window, it closes the Telnet socket with a clear user-facing message. No silent truncation or replay.
- **PROXY protocol**: enable only on a dedicated, internal-only listener/port that is reachable only from the Telnet edge proxy (HAProxy). Do not enable PROXY parsing on the public Telnet listener.
- **Oversized lines**: do not truncate and forward partial input. Reject the entire line, notify the user, increment a violation counter, and close after a budget is exceeded.
- **SESSION envelope tokens**: `sessionId` and `tenantId` are UUIDs. Only UUID formats are accepted in `SESSION <sessionId> <tenantId>` or `SESSION <sessionId>:<tenantId>`.
- **Dev echo / logging**: documentation must not encourage logging raw Telnet input (especially `LOGIN`). Examples must not include passwords. Any raw input logging must be explicitly opt-in and redact credentials.

## Doc Tasks (To Apply)

- [ ] Update `design/architecture/system-architecture-mud-client-protocol.md` to align MCP termination with “TCP Proxy forwards; backend interprets”, removing statements that the TCP Proxy maps MCP to game operations.
- [ ] Update `design/architecture/microservices/tcp-proxy-service/README.md` to:
  - Specify fail-fast behavior when the gateway WebSocket bridge is unavailable.
  - Replace “truncate” semantics for `TCP_PROXY_MAX_LINE_BYTES` with reject+notify semantics.
  - Define `SESSION` token formats as UUID/UUID.
  - Remove/adjust local echo guidance that logs raw input and includes password examples.
- [ ] Update `design/architecture/system-architecture-reconnection.md` to match fail-fast semantics for prolonged proxy↔gateway WebSocket outages (brief pause is fine; sustained outage closes Telnet sockets).
- [ ] Update `design/architecture/infrastructure/deployment-environments.md` to explicitly recommend PROXY protocol only on a dedicated internal-only listener/port behind HAProxy (separate Service + NetworkPolicy).

## Open Decision

- [ ] Decide how to standardize `tcpproxy.gateway.handshake.failures{reason="..."}`:
  - Define a small bounded enum for `reason` (possibly shared from `firemud-common` if an appropriate enum exists).
  - Document the allowed values and the mapping rules from low-level failures to those values.
